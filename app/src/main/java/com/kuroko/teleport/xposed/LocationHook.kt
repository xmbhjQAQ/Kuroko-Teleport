package com.kuroko.teleport.xposed

import android.content.AttributionSource
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.WorkSource
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Function
import kotlin.math.abs

/**
 * System-server location & GNSS virtualization hooks for Android 17 / HyperOS 4:
 *  - LocationManagerService.onTransact: receives root daemon SHM memfd & ping
 *  - LocationProviderManager: per-UID clean WGS-84 Location (zero mock flags) on
 *    getLastLocation, getCurrentLocation, onReportLocation, and Registration.acceptLocationChange
 *  - 1Hz Active Pump thread: delivers synthetic LocationResult, GnssStatus (GnssStatus.Builder),
 *    and NMEA sentences to target UID registrations in ListenerMultiplexer.mRegistrations even
 *    indoors with zero hardware GNSS signal
 *  - GnssMeasurementsProvider: suppresses conflicting raw GnssMeasurementsEvent for target UIDs
 *    with KUROKO_POLICY_SUPPRESS_MEAS
 */
object LocationHook {
    private const val TAG = "Kuroko-Loc"
    private const val LOCATION_INTERFACE = "android.location.ILocationManager"
    private const val PUMP_INTERVAL_MS = 1000L
    private const val RECENT_DELIVERY_GUARD_MS = 850L
    private const val FIRST_APPLICATION_UID = 10000
    private const val PER_USER_RANGE = 100_000

    private val locationManagers = CopyOnWriteArraySet<Any>()
    private val gnssStatusProviders = CopyOnWriteArraySet<Any>()
    private val gnssNmeaProviders = CopyOnWriteArraySet<Any>()

    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()
    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val missingFields = ConcurrentHashMap.newKeySet<String>()
    private val methodCache = ConcurrentHashMap<String, Method>()
    private val missingMethods = ConcurrentHashMap.newKeySet<String>()

    // Track last synthetic delivery per registration (WeakHashMap to avoid retaining dead registrations)
    private val lastLocationDeliveryMs = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val lastSvDeliveryMs = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val lastNmeaDeliveryMs = Collections.synchronizedMap(WeakHashMap<Any, Long>())
    private val firstFixSentRegs = Collections.synchronizedMap(WeakHashMap<Any, Boolean>())

    // Per-thread state for selective suppression & pump recursion guard
    private val inOurPump = ThreadLocal.withInitial { false }
    private val suppressPolicyForDeliver = ThreadLocal.withInitial { 0 }

    private val pumpStarted = AtomicBoolean(false)
    private val pumpWaitLock = Object()

    @Volatile
    private var listenerMultiplexerClass: Class<*>? = null

    @Volatile
    private var deliverToListenersFnMethod: Method? = null

    @Volatile
    private var listenerOperationClass: Class<*>? = null

    @Volatile
    private var locationResultClass: Class<*>? = null

    @Volatile
    private var locationResultCreateVarargs: Method? = null

    @Volatile
    private var locationResultCreateList: Method? = null

    @Volatile
    private var locationResultGetLast: Method? = null

    fun initSystemServer(cl: ClassLoader) {
        // 1. Binder SHM memfd receiver on LocationManagerService ("location")
        ShmReader.hookServiceOnTransact(
            "com.android.server.location.LocationManagerService",
            cl,
            LOCATION_INTERFACE
        )

        // 2. Resolve LocationResult reflection helpers
        resolveLocationResultHelpers(cl)

        // 3. Hook LocationProviderManager & discover ListenerMultiplexer + ListenerOperation dynamically
        hookLocationProviderManager(cl)

        // 4. Hook ListenerMultiplexer.deliverToListeners for per-UID suppression of real conflicting GNSS events
        hookListenerMultiplexer(cl)

        // 5. Hook GnssStatusProvider, GnssNmeaProvider, GnssMeasurementsProvider
        hookGnssStatusProvider(cl)
        hookGnssNmeaProvider(cl)
        hookGnssMeasurementsProvider(cl)

        // 6. Start 1Hz Active Pump daemon thread
        startActivePumpThread()
    }

    private fun logOnce(key: String, msg: String) {
        if (loggedOnceKeys.add(key)) {
            XposedBridge.log("$TAG: $msg")
        }
    }

    private fun resolveLocationResultHelpers(cl: ClassLoader) {
        try {
            val lrClass = XposedHelpers.findClassIfExists("android.location.LocationResult", cl) ?: return
            locationResultClass = lrClass
            locationResultCreateVarargs = try {
                lrClass.getDeclaredMethod("create", Array<Location>::class.java).apply { isAccessible = true }
            } catch (_: Throwable) {
                try {
                    lrClass.getDeclaredMethod("wrap", Array<Location>::class.java).apply { isAccessible = true }
                } catch (_: Throwable) {
                    null
                }
            }
            locationResultCreateList = try {
                lrClass.getDeclaredMethod("create", List::class.java).apply { isAccessible = true }
            } catch (_: Throwable) {
                try {
                    lrClass.getDeclaredMethod("wrap", List::class.java).apply { isAccessible = true }
                } catch (_: Throwable) {
                    null
                }
            }
            locationResultGetLast = try {
                lrClass.getDeclaredMethod("getLastLocation").apply { isAccessible = true }
            } catch (_: Throwable) {
                null
            }
        } catch (t: Throwable) {
            logOnce("resolve_lr", "LocationResult helper init warning: $t")
        }
    }

    private fun hookLocationProviderManager(cl: ClassLoader) {
        val lpmClass = XposedHelpers.findClassIfExists(
            "com.android.server.location.provider.LocationProviderManager",
            cl
        )
        if (lpmClass == null) {
            XposedBridge.log("$TAG: LocationProviderManager not found")
            return
        }

        // Discover ListenerMultiplexer via superclass walk (resilient to package moves)
        discoverMultiplexerTypes(lpmClass, cl)

        // Capture all LocationProviderManager instances (gps, fused, network, passive)
        try {
            XposedBridge.hookAllConstructors(lpmClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val inst = param.thisObject ?: return
                    locationManagers.add(inst)
                }
            })
        } catch (t: Throwable) {
            logOnce("lpm_ctor", "LocationProviderManager ctor hook error: $t")
        }

        // Hook getLastLocation(LastLocationRequest, CallerIdentity, int)
        try {
            XposedBridge.hookAllMethods(lpmClass, "getLastLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        param.thisObject?.let { locationManagers.add(it) }
                        val uid = extractUidFromArgs(param.args)
                        param.setObjectExtra("kuroko_uid", uid)
                        param.setObjectExtra("kuroko_ws", extractWorkSourceFromArgs(param.args))
                        param.setObjectExtra("kuroko_attr", extractAttributionSourceFromArgs(param.args))
                        param.setObjectExtra("kuroko_perm", extractPermissionLevel(param.args))
                    } catch (_: Throwable) {
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.throwable != null) return
                        val permLevel = (param.getObjectExtra("kuroko_perm") as? Int) ?: 2
                        if (permLevel == 0) return
                        val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                        val ws = param.getObjectExtra("kuroko_ws") as? WorkSource
                        val attr = param.getObjectExtra("kuroko_attr") as? AttributionSource
                        val snap = ShmReader.readLocationSnapshot() ?: return
                        if (resolveEffectiveTargetPolicyUid(
                                primaryUid = uid,
                                workSource = ws,
                                snap = snap,
                                policyFlag = ShmReader.KUROKO_POLICY_LOCATION,
                                attrSource = attr
                            ) <= 0
                        ) {
                            return
                        }
                        val provider = getProviderName(param.thisObject)
                        param.result = buildSyntheticLocation(provider, snap)
                    } catch (t: Throwable) {
                        logOnce("lpm_last_loc", "getLastLocation hook error: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            logOnce("hook_last_loc", "Failed hooking getLastLocation: $t")
        }

        // Hook getCurrentLocation(LocationRequest, CallerIdentity, int, ILocationCallback)
        try {
            XposedBridge.hookAllMethods(lpmClass, "getCurrentLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        param.thisObject?.let { locationManagers.add(it) }
                        val uid = extractUidFromArgs(param.args)
                        param.setObjectExtra("kuroko_uid", uid)
                        param.setObjectExtra("kuroko_ws", extractWorkSourceFromArgs(param.args))
                        param.setObjectExtra("kuroko_attr", extractAttributionSourceFromArgs(param.args))
                    } catch (_: Throwable) {
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.throwable != null) return
                        val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                        val ws = param.getObjectExtra("kuroko_ws") as? WorkSource
                        val attr = param.getObjectExtra("kuroko_attr") as? AttributionSource
                        if (resolveEffectiveTargetPolicyUid(
                                primaryUid = uid,
                                workSource = ws,
                                snap = null,
                                policyFlag = ShmReader.KUROKO_POLICY_LOCATION,
                                attrSource = attr
                            ) > 0
                        ) {
                            // Immediately wake the active pump so GetCurrentLocationListenerRegistration
                            // receives a synthetic fix indoors without waiting up to 1s.
                            nudgePump()
                        }
                    } catch (t: Throwable) {
                        logOnce("lpm_cur_loc", "getCurrentLocation hook error: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            logOnce("hook_cur_loc", "Failed hooking getCurrentLocation: $t")
        }

        // Hook onReportLocation(LocationResult) to capture manager instances (do not clobber non-targets!)
        try {
            XposedBridge.hookAllMethods(lpmClass, "onReportLocation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let { locationManagers.add(it) }
                }
            })
        } catch (t: Throwable) {
            logOnce("hook_report_loc", "Failed hooking onReportLocation: $t")
        }

        // Also capture instances on registerLocationRequest
        try {
            XposedBridge.hookAllMethods(lpmClass, "registerLocationRequest", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let { locationManagers.add(it) }
                }
            })
        } catch (_: Throwable) {
        }

        // Hook acceptLocationChange(LocationResult) on Registration / LocationRegistration / GetCurrentLocationListenerRegistration
        val candidateClasses = LinkedHashSet<Class<*>>()
        try {
            candidateClasses.addAll(lpmClass.declaredClasses)
        } catch (_: Throwable) {
        }
        for (innerName in listOf(
            "com.android.server.location.provider.LocationProviderManager\$Registration",
            "com.android.server.location.provider.LocationProviderManager\$LocationRegistration",
            "com.android.server.location.provider.LocationProviderManager\$GetCurrentLocationListenerRegistration",
            "com.android.server.location.provider.LocationProviderManager\$LocationListenerRegistration",
            "com.android.server.location.provider.LocationProviderManager\$LocationPendingIntentRegistration"
        )) {
            XposedHelpers.findClassIfExists(innerName, cl)?.let { candidateClasses.add(it) }
        }

        var hookedAcceptCount = 0
        for (inner in candidateClasses) {
            try {
                for (m in inner.declaredMethods) {
                    if (m.name != "acceptLocationChange") continue
                    if (Modifier.isAbstract(m.modifiers)) continue
                    if (m.parameterCount < 1) continue
                    XposedBridge.hookMethod(m, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                if (inOurPump.get() == true) return
                                val reg = param.thisObject ?: return
                                val snap = ShmReader.readLocationSnapshot() ?: return
                                if (resolveRegistrationTargetUid(reg, ShmReader.KUROKO_POLICY_LOCATION, snap) <= 0) {
                                    return
                                }
                                val provider = resolveProviderFromRegistrationOrResult(reg, param.args[0])
                                val synthLoc = buildSyntheticLocation(provider, snap)
                                val synthRes = createLocationResult(synthLoc) ?: return
                                param.args[0] = synthRes
                                lastLocationDeliveryMs[reg] = SystemClock.elapsedRealtime()
                            } catch (t: Throwable) {
                                logOnce("accept_loc_err", "acceptLocationChange hook error: $t")
                            }
                        }
                    })
                    hookedAcceptCount++
                }
            } catch (t: Throwable) {
                logOnce("hook_accept_${inner.simpleName}", "Failed hooking acceptLocationChange on ${inner.name}: $t")
            }
        }
        XposedBridge.log("$TAG: Hooked LocationProviderManager (acceptLocationChange methods=$hookedAcceptCount)")
    }

    private fun discoverMultiplexerTypes(lpmClass: Class<*>, cl: ClassLoader) {
        try {
            var cur: Class<*>? = lpmClass.superclass
            while (cur != null && cur != Any::class.java) {
                if (cur.simpleName == "ListenerMultiplexer") {
                    listenerMultiplexerClass = cur
                    break
                }
                cur = cur.superclass
            }
            if (listenerMultiplexerClass == null) {
                listenerMultiplexerClass = XposedHelpers.findClassIfExists(
                    "com.android.server.location.listeners.ListenerMultiplexer",
                    cl
                )
            }
            val muxClass = listenerMultiplexerClass ?: return
            for (m in muxClass.declaredMethods) {
                if (m.name != "deliverToListeners" || m.parameterCount != 1) continue
                val pType = m.parameterTypes[0]
                if (pType == Function::class.java) {
                    m.isAccessible = true
                    deliverToListenersFnMethod = m
                } else if (pType.isInterface) {
                    listenerOperationClass = pType
                }
            }
            if (listenerOperationClass == null) {
                listenerOperationClass = XposedHelpers.findClassIfExists(
                    "com.android.internal.listeners.ListenerExecutor\$ListenerOperation",
                    cl
                ) ?: XposedHelpers.findClassIfExists(
                    "com.android.server.location.listeners.ListenerExecutor\$ListenerOperation",
                    cl
                )
            }
        } catch (t: Throwable) {
            logOnce("discover_mux", "discoverMultiplexerTypes warning: $t")
        }
    }

    private fun hookListenerMultiplexer(cl: ClassLoader) {
        val muxClass = listenerMultiplexerClass
            ?: XposedHelpers.findClassIfExists("com.android.server.location.listeners.ListenerMultiplexer", cl)
            ?: return

        try {
            XposedBridge.hookAllMethods(muxClass, "deliverToListeners", object : XC_MethodHook() {
                @Suppress("UNCHECKED_CAST")
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (inOurPump.get() == true) return
                        val policyFlag = suppressPolicyForDeliver.get() ?: 0
                        if (policyFlag == 0) return
                        if (param.args.size != 1) return
                        val arg = param.args[0] ?: return

                        if (arg is Function<*, *>) {
                            val origFn = arg as Function<Any?, Any?>
                            param.args[0] = Function<Any?, Any?> { reg ->
                                if (resolveRegistrationTargetUid(reg, policyFlag) > 0) {
                                    null
                                } else {
                                    origFn.apply(reg)
                                }
                            }
                        } else {
                            // Overload deliverToListeners(ListenerOperation): redirect through deliverToListeners(Function)
                            val fnMethod = deliverToListenersFnMethod ?: return
                            val origOp = arg
                            val prev = suppressPolicyForDeliver.get() ?: 0
                            suppressPolicyForDeliver.set(0)
                            try {
                                fnMethod.invoke(param.thisObject, Function<Any?, Any?> { reg ->
                                    if (resolveRegistrationTargetUid(reg, policyFlag) > 0) {
                                        null
                                    } else {
                                        origOp
                                    }
                                })
                                param.result = null
                            } finally {
                                suppressPolicyForDeliver.set(prev)
                            }
                        }
                    } catch (t: Throwable) {
                        logOnce("mux_deliver_err", "deliverToListeners filter error: $t")
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked ${muxClass.name}.deliverToListeners")
        } catch (t: Throwable) {
            logOnce("hook_mux", "Failed hooking ListenerMultiplexer.deliverToListeners: $t")
        }
    }

    private fun hookGnssStatusProvider(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(
            "com.android.server.location.gnss.GnssStatusProvider",
            cl
        ) ?: return

        try {
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let { gnssStatusProviders.add(it) }
                }
            })
        } catch (_: Throwable) {
        }

        val suppressHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    param.thisObject?.let { gnssStatusProviders.add(it) }
                    if (ShmReader.hasAnyTargetWithPolicy(ShmReader.KUROKO_POLICY_LOCATION)) {
                        suppressPolicyForDeliver.set(ShmReader.KUROKO_POLICY_LOCATION)
                    }
                } catch (_: Throwable) {
                }
            }

            override fun afterHookedMethod(param: MethodHookParam) {
                suppressPolicyForDeliver.set(0)
            }
        }

        try {
            XposedBridge.hookAllMethods(cls, "onReportSvStatus", suppressHook)
            XposedBridge.hookAllMethods(cls, "onReportFirstFix", suppressHook)
            XposedBridge.hookAllMethods(cls, "addListener", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let { gnssStatusProviders.add(it) }
                }
            })
            XposedBridge.log("$TAG: Hooked GnssStatusProvider")
        } catch (t: Throwable) {
            logOnce("hook_gnss_status", "Failed hooking GnssStatusProvider: $t")
        }
    }

    private fun hookGnssNmeaProvider(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(
            "com.android.server.location.gnss.GnssNmeaProvider",
            cl
        ) ?: return

        try {
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let { gnssNmeaProviders.add(it) }
                }
            })
        } catch (_: Throwable) {
        }

        try {
            XposedBridge.hookAllMethods(cls, "onReportNmea", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        param.thisObject?.let { gnssNmeaProviders.add(it) }
                        if (ShmReader.hasAnyTargetWithPolicy(ShmReader.KUROKO_POLICY_LOCATION)) {
                            suppressPolicyForDeliver.set(ShmReader.KUROKO_POLICY_LOCATION)
                        }
                    } catch (_: Throwable) {
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    suppressPolicyForDeliver.set(0)
                }
            })
            XposedBridge.hookAllMethods(cls, "addListener", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    param.thisObject?.let { gnssNmeaProviders.add(it) }
                }
            })
            XposedBridge.log("$TAG: Hooked GnssNmeaProvider")
        } catch (t: Throwable) {
            logOnce("hook_gnss_nmea", "Failed hooking GnssNmeaProvider: $t")
        }
    }

    private fun hookGnssMeasurementsProvider(cl: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(
            "com.android.server.location.gnss.GnssMeasurementsProvider",
            cl
        ) ?: return

        try {
            XposedBridge.hookAllMethods(cls, "onReportMeasurements", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (ShmReader.hasAnyTargetWithPolicy(ShmReader.KUROKO_POLICY_SUPPRESS_MEAS)) {
                            suppressPolicyForDeliver.set(ShmReader.KUROKO_POLICY_SUPPRESS_MEAS)
                        }
                    } catch (_: Throwable) {
                    }
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    suppressPolicyForDeliver.set(0)
                }
            })
            XposedBridge.log("$TAG: Hooked GnssMeasurementsProvider (KUROKO_POLICY_SUPPRESS_MEAS)")
        } catch (t: Throwable) {
            logOnce("hook_gnss_meas", "Failed hooking GnssMeasurementsProvider: $t")
        }
    }

    // ==================== 1Hz Active Pump Thread ====================

    private fun startActivePumpThread() {
        if (!pumpStarted.compareAndSet(false, true)) return
        val thread = Thread({
            XposedBridge.log("$TAG: 1Hz Active Pump thread started")
            while (true) {
                try {
                    runPumpTick()
                } catch (t: Throwable) {
                    logOnce("pump_tick_err", "1Hz Active Pump tick error: $t")
                }
                synchronized(pumpWaitLock) {
                    try {
                        pumpWaitLock.wait(PUMP_INTERVAL_MS)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }, "Kuroko-1Hz-Pump")
        thread.isDaemon = true
        thread.start()
    }

    private fun nudgePump() {
        synchronized(pumpWaitLock) {
            pumpWaitLock.notifyAll()
        }
    }

    private fun runPumpTick() {
        val fullSnap = ShmReader.readFullSnapshot() ?: return
        val locSnap = fullSnap.location
        if (!locSnap.hasAnyTargetWithPolicy(ShmReader.KUROKO_POLICY_LOCATION)) return

        val nowElapsedMs = SystemClock.elapsedRealtime()
        val token = Binder.clearCallingIdentity()
        inOurPump.set(true)
        try {
            // 1. Pump LocationProviderManager registrations (gps / fused / network / passive)
            for (manager in locationManagers) {
                try {
                    pumpLocationProviderManager(manager, locSnap, nowElapsedMs)
                } catch (t: Throwable) {
                    logOnce("pump_lpm_${manager.javaClass.simpleName}", "Pump LPM error: $t")
                }
            }

            // 2. Pump synthetic GnssStatus and NMEA if enabled
            if ((locSnap.globalFlags and ShmReader.KUROKO_FLAG_GNSS_SYNTH) != 0) {
                if (gnssStatusProviders.isNotEmpty() && fullSnap.svs.isNotEmpty()) {
                    val synthStatus = buildSyntheticGnssStatus(fullSnap.svs)
                    if (synthStatus != null) {
                        for (sp in gnssStatusProviders) {
                            try {
                                pumpGnssStatusProvider(sp, locSnap, synthStatus, nowElapsedMs)
                            } catch (t: Throwable) {
                                logOnce("pump_sv", "Pump GnssStatus error: $t")
                            }
                        }
                    }
                }

                if (gnssNmeaProviders.isNotEmpty() && fullSnap.nmeaText.isNotEmpty()) {
                    val lines = parseNmeaLines(fullSnap.nmeaText)
                    if (lines.isNotEmpty()) {
                        val utcMs = System.currentTimeMillis()
                        for (np in gnssNmeaProviders) {
                            try {
                                pumpGnssNmeaProvider(np, locSnap, utcMs, lines, nowElapsedMs)
                            } catch (t: Throwable) {
                                logOnce("pump_nmea", "Pump GnssNmea error: $t")
                            }
                        }
                    }
                }
            }
        } finally {
            inOurPump.set(false)
            Binder.restoreCallingIdentity(token)
        }
    }

    private fun hasMatchingRegistration(multiplexer: Any, snap: ShmReader.LocationSnapshot): Boolean {
        val regsObj = getFieldValue(multiplexer, "mRegistrations") as? Map<*, *> ?: return true
        val lock = getFieldValue(multiplexer, "mMultiplexerLock") ?: multiplexer
        synchronized(lock) {
            if (regsObj.isEmpty()) return false
            for (reg in regsObj.values) {
                if (reg == null) continue
                if (resolveRegistrationTargetUid(reg, ShmReader.KUROKO_POLICY_LOCATION, snap) > 0) {
                    return true
                }
            }
            return false
        }
    }

    private fun pumpLocationProviderManager(
        manager: Any,
        snap: ShmReader.LocationSnapshot,
        nowElapsedMs: Long
    ) {
        if (!hasMatchingRegistration(manager, snap)) return

        val provider = getProviderName(manager)
        val synthLoc = buildSyntheticLocation(provider, snap)
        val synthResult = createLocationResult(synthLoc) ?: return

        val fnMethod = deliverToListenersFnMethod
        if (fnMethod != null) {
            fnMethod.invoke(manager, Function<Any?, Any?> { reg ->
                if (reg == null) return@Function null
                if (resolveRegistrationTargetUid(reg, ShmReader.KUROKO_POLICY_LOCATION, snap) <= 0) {
                    return@Function null
                }
                if (wasRecentlyDelivered(lastLocationDeliveryMs, reg, nowElapsedMs)) {
                    return@Function null
                }
                val acceptMethod = findAcceptLocationChangeMethod(reg.javaClass) ?: return@Function null
                try {
                    val op = acceptMethod.invoke(reg, synthResult)
                    if (op != null) {
                        lastLocationDeliveryMs[reg] = nowElapsedMs
                    }
                    op
                } catch (t: Throwable) {
                    logOnce("pump_accept_${reg.javaClass.simpleName}", "Pump acceptLocationChange failed: $t")
                    null
                }
            })
            return
        }

        // Fallback if deliverToListeners(Function) was unavailable: iterate mRegistrations directly
        val regsMap = getFieldValue(manager, "mRegistrations") as? Map<*, *> ?: return
        val lock = getFieldValue(manager, "mMultiplexerLock") ?: manager
        val snapshotRegs = ArrayList<Any>()
        synchronized(lock) {
            for (reg in regsMap.values) {
                if (reg != null) snapshotRegs.add(reg)
            }
        }
        for (reg in snapshotRegs) {
            if (resolveRegistrationTargetUid(reg, ShmReader.KUROKO_POLICY_LOCATION, snap) <= 0) continue
            if (wasRecentlyDelivered(lastLocationDeliveryMs, reg, nowElapsedMs)) continue
            if (!isRegistrationActive(reg)) continue
            val transport = getRegistrationListener(reg) ?: continue
            val deliverMethod = findMethodByName(transport.javaClass, "deliverOnLocationChanged", 2) ?: continue
            try {
                deliverMethod.invoke(transport, synthResult, null)
                lastLocationDeliveryMs[reg] = nowElapsedMs
            } catch (t: Throwable) {
                logOnce("pump_transport_fallback", "Fallback deliverOnLocationChanged error: $t")
            }
        }
    }

    private fun pumpGnssStatusProvider(
        provider: Any,
        snap: ShmReader.LocationSnapshot,
        synthStatus: GnssStatus,
        nowElapsedMs: Long
    ) {
        if (!hasMatchingRegistration(provider, snap)) return
        val fnMethod = deliverToListenersFnMethod
        val opInterface = listenerOperationClass

        if (fnMethod != null && opInterface != null) {
            fnMethod.invoke(provider, Function<Any?, Any?> { reg ->
                if (reg == null) return@Function null
                if (resolveRegistrationTargetUid(reg, ShmReader.KUROKO_POLICY_LOCATION, snap) <= 0) {
                    return@Function null
                }
                if (wasRecentlyDelivered(lastSvDeliveryMs, reg, nowElapsedMs)) {
                    return@Function null
                }
                lastSvDeliveryMs[reg] = nowElapsedMs
                val sendFirstFix = firstFixSentRegs.put(reg, true) == null
                createListenerOperationProxy(opInterface) { listener ->
                    if (sendFirstFix) {
                        invokeNoThrow(listener, "onGnssStarted")
                        invokeNoThrow(listener, "onFirstFix", 1150)
                    }
                    invokeNoThrow(listener, "onSvStatusChanged", synthStatus)
                }
            })
            return
        }

        // Direct listener fallback
        val regsMap = getFieldValue(provider, "mRegistrations") as? Map<*, *> ?: return
        val lock = getFieldValue(provider, "mMultiplexerLock") ?: provider
        val snapshotRegs = ArrayList<Any>()
        synchronized(lock) {
            for (reg in regsMap.values) {
                if (reg != null) snapshotRegs.add(reg)
            }
        }
        for (reg in snapshotRegs) {
            if (resolveRegistrationTargetUid(reg, ShmReader.KUROKO_POLICY_LOCATION, snap) <= 0) continue
            if (wasRecentlyDelivered(lastSvDeliveryMs, reg, nowElapsedMs)) continue
            if (!isRegistrationActive(reg)) continue
            val listener = getRegistrationListener(reg) ?: continue
            lastSvDeliveryMs[reg] = nowElapsedMs
            if (firstFixSentRegs.put(reg, true) == null) {
                invokeNoThrow(listener, "onGnssStarted")
                invokeNoThrow(listener, "onFirstFix", 1150)
            }
            invokeNoThrow(listener, "onSvStatusChanged", synthStatus)
        }
    }

    private fun pumpGnssNmeaProvider(
        provider: Any,
        snap: ShmReader.LocationSnapshot,
        timestampMs: Long,
        lines: List<String>,
        nowElapsedMs: Long
    ) {
        if (!hasMatchingRegistration(provider, snap)) return
        val fnMethod = deliverToListenersFnMethod
        val opInterface = listenerOperationClass

        if (fnMethod != null && opInterface != null) {
            fnMethod.invoke(provider, Function<Any?, Any?> { reg ->
                if (reg == null) return@Function null
                if (resolveRegistrationTargetUid(reg, ShmReader.KUROKO_POLICY_LOCATION, snap) <= 0) {
                    return@Function null
                }
                if (wasRecentlyDelivered(lastNmeaDeliveryMs, reg, nowElapsedMs)) {
                    return@Function null
                }
                lastNmeaDeliveryMs[reg] = nowElapsedMs
                createListenerOperationProxy(opInterface) { listener ->
                    for (line in lines) {
                        invokeNoThrow(listener, "onNmeaReceived", timestampMs, line)
                    }
                }
            })
            return
        }

        // Direct listener fallback
        val regsMap = getFieldValue(provider, "mRegistrations") as? Map<*, *> ?: return
        val lock = getFieldValue(provider, "mMultiplexerLock") ?: provider
        val snapshotRegs = ArrayList<Any>()
        synchronized(lock) {
            for (reg in regsMap.values) {
                if (reg != null) snapshotRegs.add(reg)
            }
        }
        for (reg in snapshotRegs) {
            if (resolveRegistrationTargetUid(reg, ShmReader.KUROKO_POLICY_LOCATION, snap) <= 0) continue
            if (wasRecentlyDelivered(lastNmeaDeliveryMs, reg, nowElapsedMs)) continue
            if (!isRegistrationActive(reg)) continue
            val listener = getRegistrationListener(reg) ?: continue
            lastNmeaDeliveryMs[reg] = nowElapsedMs
            for (line in lines) {
                invokeNoThrow(listener, "onNmeaReceived", timestampMs, line)
            }
        }
    }

    // ==================== Synthetic Object Builders ====================

    fun buildSyntheticLocation(provider: String, snap: ShmReader.LocationSnapshot): Location {
        val loc = Location(provider.ifEmpty { LocationManager.GPS_PROVIDER })
        val nowUtcMs = System.currentTimeMillis()
        val nowBootNs = SystemClock.elapsedRealtimeNanos()

        loc.time = if (snap.snapshotUtcMs > 0L && abs(nowUtcMs - snap.snapshotUtcMs) < 3_000L) {
            snap.snapshotUtcMs
        } else {
            nowUtcMs
        }
        loc.elapsedRealtimeNanos = if (
            snap.snapshotBootNs > 0L && abs(nowBootNs - snap.snapshotBootNs) < 3_000_000_000L
        ) {
            snap.snapshotBootNs
        } else {
            nowBootNs
        }

        loc.latitude = snap.latWgs84
        loc.longitude = snap.lonWgs84
        loc.altitude = snap.altWgs84
        loc.accuracy = snap.hAccuracyM.coerceIn(1.0f, 50.0f)
        loc.verticalAccuracyMeters = snap.vAccuracyM.coerceIn(1.0f, 80.0f)
        loc.speed = snap.speedMps.coerceAtLeast(0.0f)
        loc.speedAccuracyMetersPerSecond = snap.speedAccuracyMps.coerceIn(0.05f, 10.0f)
        if (snap.speedMps > 0.15f) {
            loc.bearing = ((snap.bearingDeg % 360.0f) + 360.0f) % 360.0f
            loc.bearingAccuracyDegrees = snap.bearingAccuracyDeg.coerceIn(1.0f, 180.0f)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try {
                loc.mslAltitudeMeters = snap.mslAlt
                loc.mslAltitudeAccuracyMeters = snap.vAccuracyM.coerceIn(1.0f, 80.0f)
            } catch (_: Throwable) {
            }
        }

        // Explicitly guarantee ZERO mock flags
        try {
            loc.isMock = false
        } catch (_: Throwable) {
        }

        val extras = Bundle()
        extras.putInt("satellites", snap.svCount.coerceIn(8, 24))
        if (provider == LocationManager.GPS_PROVIDER) {
            extras.putInt("meanCn0", 35)
            extras.putInt("maxCn0", 41)
        }
        loc.extras = extras
        return loc
    }

    private fun buildSyntheticGnssStatus(svs: List<ShmReader.SvEntry>): GnssStatus? {
        return try {
            val builder = GnssStatus.Builder()
            for (sv in svs) {
                val hasEph = (sv.flags and ShmReader.KUROKO_SV_HAS_EPHEMERIS) != 0
                val hasAlm = (sv.flags and ShmReader.KUROKO_SV_HAS_ALMANAC) != 0
                val usedInFix = (sv.flags and ShmReader.KUROKO_SV_USED_IN_FIX) != 0
                val hasCarrier = (sv.flags and ShmReader.KUROKO_SV_HAS_CARRIER_FREQ) != 0
                val hasBaseband = (sv.flags and ShmReader.KUROKO_SV_HAS_BASEBAND_CN0) != 0

                val cn0 = if (sv.cn0DbHz.isFinite()) sv.cn0DbHz.coerceIn(0f, 63f) else 30f
                val el = if (sv.elevationDeg.isFinite()) sv.elevationDeg.coerceIn(-90f, 90f) else 45f
                val az = if (sv.azimuthDeg.isFinite()) ((sv.azimuthDeg % 360f) + 360f) % 360f else 0f
                val freq = if (hasCarrier && sv.carrierFreqHz.isFinite() && sv.carrierFreqHz > 0f) {
                    sv.carrierFreqHz
                } else {
                    0f
                }
                val bbCn0 = if (hasBaseband && sv.basebandCn0DbHz.isFinite()) {
                    sv.basebandCn0DbHz.coerceIn(0f, 63f)
                } else {
                    0f
                }

                builder.addSatellite(
                    sv.constellation,
                    sv.svid,
                    cn0,
                    el,
                    az,
                    hasEph,
                    hasAlm,
                    usedInFix,
                    hasCarrier && freq > 0f,
                    freq,
                    hasBaseband,
                    bbCn0
                )
            }
            builder.build()
        } catch (t: Throwable) {
            logOnce("build_gnss_status", "GnssStatus.Builder failed: $t")
            null
        }
    }

    private fun parseNmeaLines(nmeaText: String): List<String> {
        val result = ArrayList<String>(6)
        var start = 0
        val len = nmeaText.length
        while (start < len) {
            var end = nmeaText.indexOf('\n', start)
            if (end < 0) end = len
            val line = nmeaText.substring(start, end).trim()
            if (line.startsWith("$") && line.contains("*")) {
                result.add("$line\r\n")
            }
            start = end + 1
        }
        return result
    }

    private fun createLocationResult(location: Location): Any? {
        return try {
            locationResultCreateVarargs?.invoke(null, arrayOf(location) as Any)
                ?: locationResultCreateList?.invoke(null, listOf(location))
        } catch (t: Throwable) {
            logOnce("create_lr", "createLocationResult failed: $t")
            null
        }
    }

    private fun createListenerOperationProxy(
        opInterface: Class<*>,
        action: (Any) -> Unit
    ): Any? {
        return try {
            Proxy.newProxyInstance(opInterface.classLoader, arrayOf(opInterface)) { proxy, method, args ->
                when (method.name) {
                    "operate" -> {
                        val listener = args?.firstOrNull()
                        if (listener != null) {
                            try {
                                action(listener)
                            } catch (t: Throwable) {
                                logOnce("op_exec_${listener.javaClass.simpleName}", "ListenerOperation error: $t")
                            }
                        }
                        null
                    }
                    "equals" -> proxy === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxy)
                    "toString" -> "KurokoListenerOperation"
                    else -> defaultPrimitiveReturn(method.returnType)
                }
            }
        } catch (t: Throwable) {
            logOnce("proxy_op", "Failed to create ListenerOperation proxy: $t")
            null
        }
    }

    private fun defaultPrimitiveReturn(type: Class<*>): Any? {
        return when (type) {
            Boolean::class.javaPrimitiveType -> false
            Int::class.javaPrimitiveType -> 0
            Long::class.javaPrimitiveType -> 0L
            Float::class.javaPrimitiveType -> 0f
            Double::class.javaPrimitiveType -> 0.0
            Byte::class.javaPrimitiveType -> 0.toByte()
            Short::class.javaPrimitiveType -> 0.toShort()
            Char::class.javaPrimitiveType -> '\u0000'
            else -> null
        }
    }

    // ==================== Strict Zero-Crosstalk WorkSource Attribution ====================

    private fun isAppUid(uid: Int): Boolean {
        return uid >= FIRST_APPLICATION_UID && (uid % PER_USER_RANGE) >= FIRST_APPLICATION_UID
    }

    private fun uidHasPolicy(uid: Int, policyFlag: Int, snap: ShmReader.LocationSnapshot?): Boolean {
        if (uid <= 0) return false
        return if (snap != null) {
            (snap.policyForUid(uid) and policyFlag) != 0
        } else {
            ShmReader.hasPolicy(uid, policyFlag)
        }
    }

    /**
     * Strict zero-crosstalk target UID resolution for framework location proxies:
     *  - Fast path: if [primaryUid] itself has [policyFlag], return [primaryUid].
     *  - Proxy path: inspect [workSource] (both top-level UIDs and WorkChains, ignoring
     *    [primaryUid] and non-app UIDs < 10000). Return the first matching target UID ONLY
     *    if at least 1 app UID exists AND EVERY app UID in [workSource] has [policyFlag].
     *    If ANY app UID is NOT targeted (mixed multi-client proxy), return -1 immediately!
     *  - AttributionSource fallback: only if [workSource] had zero delegated app UIDs, apply
     *    the same strict all-match rule to [attrSource].next chain.
     */
    fun resolveEffectiveTargetPolicyUid(
        primaryUid: Int,
        workSource: WorkSource?,
        snap: ShmReader.LocationSnapshot? = null,
        policyFlag: Int = ShmReader.KUROKO_POLICY_LOCATION,
        attrSource: AttributionSource? = null
    ): Int {
        if (uidHasPolicy(primaryUid, policyFlag, snap)) {
            return primaryUid
        }

        if (workSource != null) {
            val wsResult = evaluateWorkSourceZeroCrosstalk(primaryUid, workSource, policyFlag, snap)
            if (wsResult != 0) {
                return wsResult // >0: unanimous target match; -1: mixed/non-target -> never spoof
            }
        }

        if (attrSource != null) {
            val attrResult = evaluateAttributionChainZeroCrosstalk(primaryUid, attrSource, policyFlag, snap)
            if (attrResult > 0) {
                return attrResult
            }
        }
        return -1
    }

    /**
     * Returns:
     *  - `> 0`: first matching target UID (at least 1 app UID present and ALL app UIDs targeted)
     *  - `-1`: at least 1 app UID present and at least one is NOT targeted (mixed/non-target)
     *  - `0`: WorkSource contains zero delegated app UIDs (empty or only primaryUid/system UIDs)
     */
    private fun evaluateWorkSourceZeroCrosstalk(
        primaryUid: Int,
        ws: WorkSource,
        policyFlag: Int,
        snap: ShmReader.LocationSnapshot?
    ): Int {
        var firstTargetUid = -1
        var appUidCount = 0

        try {
            // 1. Top-level WorkSource UIDs
            val size = getWorkSourceSize(ws)
            for (i in 0 until size) {
                val u = getWorkSourceUidAt(ws, i)
                if (u == primaryUid || !isAppUid(u)) continue
                appUidCount++
                if (!uidHasPolicy(u, policyFlag, snap)) {
                    return -1 // Mixed multi-client or non-target client -> strict zero-crosstalk abort
                }
                if (firstTargetUid < 0) firstTargetUid = u
            }

            // 2. WorkChains (Android 9+ WorkSource.WorkChain)
            val chains = getWorkSourceChains(ws)
            if (chains != null) {
                for (chain in chains) {
                    if (chain == null) continue
                    val chainUids = extractWorkChainUids(chain)
                    for (u in chainUids) {
                        if (u == primaryUid || !isAppUid(u)) continue
                        appUidCount++
                        if (!uidHasPolicy(u, policyFlag, snap)) {
                            return -1 // Strict zero-crosstalk abort
                        }
                        if (firstTargetUid < 0) firstTargetUid = u
                    }
                }
            }
        } catch (t: Throwable) {
            logOnce("ws_eval_err", "WorkSource attribution evaluation warning: $t")
            return -1
        }

        return if (appUidCount > 0 && firstTargetUid > 0) firstTargetUid else 0
    }

    private fun evaluateAttributionChainZeroCrosstalk(
        primaryUid: Int,
        rootAttr: AttributionSource,
        policyFlag: Int,
        snap: ShmReader.LocationSnapshot?
    ): Int {
        var firstTargetUid = -1
        var appUidCount = 0
        try {
            var cur: AttributionSource? = rootAttr.next
            var depth = 0
            while (cur != null && depth < 8) {
                val u = cur.uid
                if (u != primaryUid && isAppUid(u)) {
                    appUidCount++
                    if (!uidHasPolicy(u, policyFlag, snap)) {
                        return -1
                    }
                    if (firstTargetUid < 0) firstTargetUid = u
                }
                cur = cur.next
                depth++
            }
        } catch (_: Throwable) {
            return -1
        }
        return if (appUidCount > 0 && firstTargetUid > 0) firstTargetUid else -1
    }

    private fun resolveRegistrationTargetUid(
        reg: Any?,
        policyFlag: Int,
        snap: ShmReader.LocationSnapshot? = null
    ): Int {
        if (reg == null) return -1
        val identity = extractIdentityFromRegistration(reg)
        val primaryUid = extractUidFromIdentity(identity)
        if (uidHasPolicy(primaryUid, policyFlag, snap)) {
            return primaryUid
        }
        val ws = extractWorkSourceFromRegistration(reg)
        val attr = extractAttributionSourceFromIdentity(identity)
        if (ws == null && attr == null) return -1
        return resolveEffectiveTargetPolicyUid(
            primaryUid = primaryUid,
            workSource = ws,
            snap = snap,
            policyFlag = policyFlag,
            attrSource = attr
        )
    }

    private fun getWorkSourceSize(ws: WorkSource): Int {
        val m = findMethodByName(ws.javaClass, "size", 0)
        if (m != null) {
            val s = (m.invoke(ws) as? Int) ?: 0
            return s.coerceIn(0, 256)
        }
        val num = (getFieldValue(ws, "mNum") as? Int) ?: 0
        return num.coerceIn(0, 256)
    }

    private fun getWorkSourceUidAt(ws: WorkSource, index: Int): Int {
        val mGetUid = findMethodByName(ws.javaClass, "getUid", 1)
        if (mGetUid != null && mGetUid.parameterTypes[0] == Int::class.javaPrimitiveType) {
            return (mGetUid.invoke(ws, index) as? Int) ?: -1
        }
        val mGet = findMethodByName(ws.javaClass, "get", 1)
        if (mGet != null && mGet.parameterTypes[0] == Int::class.javaPrimitiveType) {
            return (mGet.invoke(ws, index) as? Int) ?: -1
        }
        val uids = getFieldValue(ws, "mUids") as? IntArray ?: return -1
        return if (index in uids.indices) uids[index] else -1
    }

    private fun getWorkSourceChains(ws: WorkSource): List<*>? {
        val m = findMethodByName(ws.javaClass, "getWorkChains", 0)
        if (m != null) {
            return m.invoke(ws) as? List<*>
        }
        return getFieldValue(ws, "mChains") as? List<*>
    }

    private fun extractWorkChainUids(chain: Any): IntArray {
        val uidsArr = (findMethodByName(chain.javaClass, "getUids", 0)?.invoke(chain) as? IntArray)
            ?: (getFieldValue(chain, "mUids") as? IntArray)
        if (uidsArr != null) {
            val chainSize = (findMethodByName(chain.javaClass, "getSize", 0)?.invoke(chain) as? Int)
                ?: (getFieldValue(chain, "mSize") as? Int)
                ?: uidsArr.size
            val n = chainSize.coerceIn(0, uidsArr.size)
            return if (n == uidsArr.size) uidsArr else uidsArr.copyOf(n)
        }
        val attrUid = (findMethodByName(chain.javaClass, "getAttributionUid", 0)?.invoke(chain) as? Int) ?: -1
        return if (attrUid > 0) intArrayOf(attrUid) else IntArray(0)
    }

    private fun extractWorkSourceFromObject(obj: Any?): WorkSource? {
        if (obj == null) return null
        if (obj is WorkSource) return obj
        return try {
            val m = findMethodByName(obj.javaClass, "getWorkSource", 0)
            if (m != null) {
                m.invoke(obj) as? WorkSource
            } else {
                getFieldValue(obj, "mWorkSource") as? WorkSource
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractWorkSourceFromArgs(args: Array<Any?>?): WorkSource? {
        if (args == null) return null
        for (arg in args) {
            if (arg == null) continue
            if (arg is WorkSource) return arg
            val clsName = arg.javaClass.name
            if (clsName.contains("Request") || clsName.endsWith("CallerIdentity")) {
                val ws = extractWorkSourceFromObject(arg)
                if (ws != null) return ws
            }
        }
        return null
    }

    private fun extractWorkSourceFromRegistration(reg: Any): WorkSource? {
        extractWorkSourceFromObject(reg)?.let { return it }
        return try {
            val req = findMethodByName(reg.javaClass, "getRequest", 0)?.invoke(reg)
                ?: getFieldValue(reg, "mRequest")
                ?: getFieldValue(reg, "mBaseRequest")
                ?: getFieldValue(reg, "mProviderLocationRequest")
            extractWorkSourceFromObject(req)
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractAttributionSourceFromIdentity(identity: Any?): AttributionSource? {
        if (identity == null) return null
        if (identity is AttributionSource) return identity
        return try {
            val m = findMethodByName(identity.javaClass, "getAttributionSource", 0)
            if (m != null) {
                m.invoke(identity) as? AttributionSource
            } else {
                getFieldValue(identity, "mAttributionSource") as? AttributionSource
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractAttributionSourceFromArgs(args: Array<Any?>?): AttributionSource? {
        if (args == null) return null
        for (arg in args) {
            if (arg == null) continue
            if (arg is AttributionSource) return arg
            if (arg.javaClass.name.endsWith("CallerIdentity")) {
                extractAttributionSourceFromIdentity(arg)?.let { return it }
            }
        }
        return null
    }

    private fun extractIdentityFromRegistration(reg: Any?): Any? {
        if (reg == null) return null
        return try {
            val m = findMethodByName(reg.javaClass, "getIdentity", 0)
            if (m != null) m.invoke(reg) else getFieldValue(reg, "mIdentity")
        } catch (_: Throwable) {
            null
        }
    }

    // ==================== Reflection & Identity Helpers ====================

    private fun wasRecentlyDelivered(map: MutableMap<Any, Long>, reg: Any, nowElapsedMs: Long): Boolean {
        val last = map[reg] ?: return false
        return (nowElapsedMs - last) < RECENT_DELIVERY_GUARD_MS
    }

    private fun extractUidFromArgs(args: Array<Any?>?): Int {
        if (args != null) {
            for (arg in args) {
                if (arg == null) continue
                if (arg.javaClass.name.endsWith("CallerIdentity")) {
                    val uid = extractUidFromIdentity(arg)
                    if (uid > 0) return uid
                } else if (arg is AttributionSource) {
                    val uid = arg.uid
                    if (uid > 0) return uid
                }
            }
        }
        return Binder.getCallingUid()
    }

    private fun extractPermissionLevel(args: Array<Any?>?): Int {
        if (args != null) {
            for (arg in args) {
                if (arg is Int) return arg
            }
        }
        return 2
    }

    private fun extractUidFromIdentity(identity: Any?): Int {
        if (identity == null) return -1
        return try {
            val m = findMethodByName(identity.javaClass, "getUid", 0)
            if (m != null) {
                (m.invoke(identity) as? Int) ?: -1
            } else {
                (getFieldValue(identity, "mUid") as? Int) ?: -1
            }
        } catch (_: Throwable) {
            -1
        }
    }

    private fun getProviderName(manager: Any?): String {
        if (manager == null) return LocationManager.GPS_PROVIDER
        return try {
            val m = findMethodByName(manager.javaClass, "getName", 0)
            val res = (m?.invoke(manager) as? String) ?: (getFieldValue(manager, "mName") as? String)
            res?.takeIf { it.isNotEmpty() } ?: LocationManager.GPS_PROVIDER
        } catch (_: Throwable) {
            LocationManager.GPS_PROVIDER
        }
    }

    private fun resolveProviderFromRegistrationOrResult(reg: Any, locationResult: Any?): String {
        try {
            if (locationResult != null && locationResultGetLast != null) {
                val loc = locationResultGetLast?.invoke(locationResult) as? Location
                val p = loc?.provider
                if (!p.isNullOrEmpty()) return p
            }
        } catch (_: Throwable) {
        }
        try {
            val outer = getFieldValue(reg, "this\$0")
            if (outer != null) {
                return getProviderName(outer)
            }
        } catch (_: Throwable) {
        }
        return LocationManager.GPS_PROVIDER
    }

    private fun isRegistrationActive(reg: Any): Boolean {
        return try {
            val m = findMethodByName(reg.javaClass, "isActive", 0)
            if (m != null) (m.invoke(reg) as? Boolean) ?: true else true
        } catch (_: Throwable) {
            true
        }
    }

    private fun getRegistrationListener(reg: Any): Any? {
        return try {
            val m = findMethodByName(reg.javaClass, "getListener", 0)
            if (m != null) m.invoke(reg) else getFieldValue(reg, "mListener")
        } catch (_: Throwable) {
            null
        }
    }

    private fun findAcceptLocationChangeMethod(cls: Class<*>): Method? {
        val key = "${cls.name}#acceptLocationChange"
        methodCache[key]?.let { return it }
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            for (m in cur.declaredMethods) {
                if (m.name == "acceptLocationChange" && m.parameterCount == 1 && !Modifier.isAbstract(m.modifiers)) {
                    m.isAccessible = true
                    methodCache[key] = m
                    return m
                }
            }
            cur = cur.superclass
        }
        return null
    }

    private fun findMethodByName(cls: Class<*>, name: String, paramCount: Int): Method? {
        val key = "${cls.name}#$name/$paramCount"
        methodCache[key]?.let { return it }
        if (missingMethods.contains(key)) return null
        var cur: Class<*>? = cls
        while (cur != null && cur != Any::class.java) {
            for (m in cur.declaredMethods) {
                if (m.name == name && m.parameterCount == paramCount) {
                    m.isAccessible = true
                    methodCache[key] = m
                    return m
                }
            }
            cur = cur.superclass
        }
        missingMethods.add(key)
        return null
    }

    private fun getFieldValue(obj: Any, fieldName: String): Any? {
        val key = "${obj.javaClass.name}.$fieldName"
        var f = fieldCache[key]
        if (f == null) {
            if (missingFields.contains(key)) return null
            var cur: Class<*>? = obj.javaClass
            while (cur != null && cur != Any::class.java) {
                try {
                    val found = cur.getDeclaredField(fieldName)
                    found.isAccessible = true
                    fieldCache[key] = found
                    f = found
                    break
                } catch (_: NoSuchFieldException) {
                    cur = cur.superclass
                }
            }
            if (f == null) {
                missingFields.add(key)
                return null
            }
        }
        return try {
            f.get(obj)
        } catch (_: Throwable) {
            null
        }
    }

    private fun invokeNoThrow(target: Any, methodName: String, vararg args: Any?) {
        try {
            val m = findMethodByName(target.javaClass, methodName, args.size) ?: return
            m.invoke(target, *args)
        } catch (_: Throwable) {
        }
    }
}
