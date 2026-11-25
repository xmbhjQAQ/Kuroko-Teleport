package com.kuroko.teleport.xposed

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import android.telephony.ServiceState
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dual-process Telephony / Cell-Tower isolation & virtualization hooks for Android 12–17:
 *  1. com.android.phone (TeleService.apk):
 *     - PhoneInterfaceManager.onTransact: receives root daemon SHM v2 memfd & ping
 *     - SPOOF_CELL (overrides HIDE_CELL): synthesizes authentic LTE/5G NR CellInfo lists,
 *       serving CellIdentity / GsmCellLocation Bundle, ServiceState operator/cell rewrite,
 *       and per-UID network/SIM operator & country ISO getters
 *     - HIDE_CELL (without SPOOF_CELL): returns empty list / null
 *  2. system_server (services.jar):
 *     - TelephonyRegistry:
 *         * HIDE_CELL && !SPOOF_CELL -> checkFineLocationAccess / checkCoarseLocationAccess = false
 *         * SPOOF_CELL -> intercepts per-Record IPhoneStateListener callback dispatch
 *           (onCellInfoChanged, onCellLocationChanged, onServiceStateChanged) strictly by
 *           Record.callerUid without ever mutating global mCellInfo / mCellIdentity / mServiceState
 */
object TelephonyHook {
    private const val TAG = "Kuroko-Tel"
    private const val TELEPHONY_INTERFACE = "com.android.internal.telephony.ITelephony"

    private enum class CellMode { PASS, HIDE, SPOOF }

    private class TrackedTelCallback(
        val binder: IBinder,
        @Volatile var proxy: Any,
        @Volatile var uid: Int,
        @Volatile var wantsServiceState: Boolean = false,
        @Volatile var wantsCellInfo: Boolean = false,
        @Volatile var wantsCellLocation: Boolean = false,
        @Volatile var lastOrigServiceState: ServiceState? = null,
        @Volatile var lastPushedPlmnKey: String? = null,
        @Volatile var lastPushedCellEpoch: Int = -1
    )

    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()
    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val methodCache = ConcurrentHashMap<String, Method>()
    private val telPumpStarted = AtomicBoolean(false)

    // Weak map from IPhoneStateListener IBinder -> callerUid (auto-cleaned on listener GC)
    private val callbackBinderToUid = Collections.synchronizedMap(WeakHashMap<IBinder, Int>())
    private val trackedCallbacks = Collections.synchronizedMap(WeakHashMap<IBinder, TrackedTelCallback>())

    @Volatile
    private var latestRealServiceState: ServiceState? = null

    // Fallback context flag for TelephonyRegistry if checkFineLocationAccess/checkCoarseLocationAccess are absent
    private val inCellNotify = ThreadLocal.withInitial { false }

    // Re-entrancy guard for Kuroko-1Hz-Tel active push so we never overwrite real ServiceState with spoofed data
    private val inOurTelPump = ThreadLocal.withInitial { false }

    private fun cellModeForUid(uid: Int): CellMode {
        if (uid <= 0) return CellMode.PASS
        val policy = ShmReader.getTargetPolicy(uid)
        return when {
            (policy and ShmReader.KUROKO_POLICY_SPOOF_CELL) != 0 -> CellMode.SPOOF
            (policy and ShmReader.KUROKO_POLICY_HIDE_CELL) != 0 -> CellMode.HIDE
            else -> CellMode.PASS
        }
    }

    // ==================== Process 1: com.android.phone ====================

    fun initPhoneProcess(cl: ClassLoader) {
        // 1. Binder SHM memfd receiver on PhoneInterfaceManager ("phone")
        ShmReader.hookServiceOnTransact(
            "com.android.phone.PhoneInterfaceManager",
            cl,
            TELEPHONY_INTERFACE
        )

        val pimClass = XposedHelpers.findClassIfExists(
            "com.android.phone.PhoneInterfaceManager",
            cl
        )
        if (pimClass == null) {
            XposedBridge.log("$TAG: PhoneInterfaceManager not found in com.android.phone")
            return
        }

        // 2. getAllCellInfo -> virtual CellInfo list (SPOOF_CELL) or empty list (HIDE_CELL)
        hookGetAllCellInfo(pimClass)

        // 3. getNeighboringCellInfo (legacy, if present) -> empty list for HIDE/SPOOF
        hookLegacyEmptyList(pimClass, "getNeighboringCellInfo")

        // 4. getCellLocation / getLastKnownCellIdentity -> serving cell (SPOOF_CELL) or null (HIDE_CELL)
        hookServingCellMethod(pimClass, "getCellLocation")
        hookServingCellMethod(pimClass, "getLastKnownCellIdentity")

        // 5. requestCellInfoUpdate / requestCellInfoUpdateWithWorkSource
        hookRequestCellInfoUpdate(pimClass)

        // 6. Per-UID network/SIM operator & country ISO + ServiceState getters (SPOOF_CELL only)
        hookOperatorAndCountryGetters(pimClass)
    }

    private fun hookGetAllCellInfo(pimClass: Class<*>) {
        try {
            val hooks = XposedBridge.hookAllMethods(pimClass, "getAllCellInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.throwable != null) return
                        val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                        when (cellModeForUid(uid)) {
                            CellMode.PASS -> return
                            CellMode.HIDE -> param.result = Collections.emptyList<Any>()
                            CellMode.SPOOF -> {
                                val pair = ShmReader.readCellsWithHeader()
                                param.result = if (pair != null && pair.second.isNotEmpty()) {
                                    VirtualCellBuilder.buildCellInfoList(pair.first, pair.second)
                                } else {
                                    Collections.emptyList<Any>()
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        logOnce("tel_all_cell", "getAllCellInfo hook error: $t")
                    }
                }
            })
            if (hooks.isNotEmpty()) {
                XposedBridge.log("$TAG: Hooked PhoneInterfaceManager.getAllCellInfo (${hooks.size})")
            }
        } catch (t: Throwable) {
            logOnce("hook_all_cell", "Failed hooking getAllCellInfo: $t")
        }
    }

    private fun hookServingCellMethod(pimClass: Class<*>, methodName: String) {
        try {
            val hooks = XposedBridge.hookAllMethods(pimClass, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.throwable != null) return
                        val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                        when (cellModeForUid(uid)) {
                            CellMode.PASS -> return
                            CellMode.HIDE -> param.result = null
                            CellMode.SPOOF -> {
                                val pair = ShmReader.readCellsWithHeader()
                                if (pair == null || pair.second.isEmpty()) {
                                    param.result = null
                                    return
                                }
                                val retType = (param.method as? Method)?.returnType
                                param.result = if (retType != null && Bundle::class.java.isAssignableFrom(retType)) {
                                    VirtualCellBuilder.buildGsmCellLocationBundle(pair.second)
                                } else {
                                    VirtualCellBuilder.buildServingCellIdentity(pair.first, pair.second)
                                }
                            }
                        }
                    } catch (t: Throwable) {
                        logOnce("tel_serving_$methodName", "$methodName hook error: $t")
                    }
                }
            })
            if (hooks.isNotEmpty()) {
                XposedBridge.log("$TAG: Hooked PhoneInterfaceManager.$methodName (${hooks.size})")
            }
        } catch (t: Throwable) {
            logOnce("hook_tel_$methodName", "Failed hooking $methodName: $t")
        }
    }

    private fun hookLegacyEmptyList(cls: Class<*>, methodName: String) {
        try {
            val hooks = XposedBridge.hookAllMethods(cls, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.throwable != null) return
                        val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                        if (cellModeForUid(uid) != CellMode.PASS) {
                            param.result = Collections.emptyList<Any>()
                        }
                    } catch (t: Throwable) {
                        logOnce("tel_empty_$methodName", "$methodName hook error: $t")
                    }
                }
            })
            if (hooks.isNotEmpty()) {
                XposedBridge.log("$TAG: Hooked ${cls.simpleName}.$methodName (${hooks.size})")
            }
        } catch (t: Throwable) {
            logOnce("hook_tel_$methodName", "Failed hooking $methodName: $t")
        }
    }

    private fun hookRequestCellInfoUpdate(pimClass: Class<*>) {
        var hookedReqCount = 0
        try {
            for (m in pimClass.declaredMethods) {
                if (!m.name.startsWith("requestCellInfoUpdate")) continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val uid = Binder.getCallingUid()
                            val mode = cellModeForUid(uid)
                            if (mode == CellMode.PASS) return

                            val payload: List<Any> = if (mode == CellMode.SPOOF) {
                                val pair = ShmReader.readCellsWithHeader()
                                if (pair != null && pair.second.isNotEmpty()) {
                                    VirtualCellBuilder.buildCellInfoList(pair.first, pair.second)
                                } else {
                                    Collections.emptyList()
                                }
                            } else {
                                Collections.emptyList()
                            }

                            val args = param.args
                            if (args != null) {
                                for (arg in args) {
                                    if (arg == null) continue
                                    val onCellInfo = findMethodByName(arg.javaClass, "onCellInfo", 1)
                                    if (onCellInfo != null) {
                                        try {
                                            onCellInfo.invoke(arg, payload)
                                        } catch (_: Throwable) {
                                        }
                                        break
                                    }
                                }
                            }
                            param.result = null
                        } catch (t: Throwable) {
                            logOnce("req_cell_err", "requestCellInfoUpdate hook error: $t")
                        }
                    }
                })
                hookedReqCount++
            }
        } catch (t: Throwable) {
            logOnce("hook_req_cell", "Failed hooking requestCellInfoUpdate*: $t")
        }
        XposedBridge.log(
            "$TAG: Hooked PhoneInterfaceManager (requestCellInfoUpdate* methods=$hookedReqCount)"
        )
    }

    private fun hookOperatorAndCountryGetters(pimClass: Class<*>) {
        val netNumericNames = setOf("getNetworkOperator", "getNetworkOperatorForPhone")
        val netCountryNames = setOf(
            "getNetworkCountryIso",
            "getNetworkCountryIsoForPhone",
            "getNetworkCountryIsoForSubscription"
        )
        val netAlphaNames = setOf("getNetworkOperatorName", "getNetworkOperatorNameForPhone")
        val simCountryNames = setOf("getSimCountryIso", "getSimCountryIsoForPhone")
        val simNumericNames = setOf(
            "getSimOperator",
            "getSimOperatorNumeric",
            "getSimOperatorNumericForPhone"
        )
        val simAlphaNames = setOf(
            "getSimOperatorName",
            "getSimOperatorNameForPhone",
            "getSimOperatorNameForSubId"
        )
        val serviceStateNames = setOf("getServiceStateForSubscriber", "getServiceState")

        var hookedIdentityCount = 0
        try {
            val presentNames = HashSet<String>()
            var cur: Class<*>? = pimClass
            while (cur != null && cur != Any::class.java) {
                for (m in cur.declaredMethods) {
                    presentNames.add(m.name)
                }
                cur = cur.superclass
            }

            fun hookStringGetter(name: String, requireSimFlag: Boolean, extractor: (ShmReader.RfHeader) -> String) {
                if (name !in presentNames) return
                val hooks = XposedBridge.hookAllMethods(pimClass, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.throwable != null) return
                            val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                            if (cellModeForUid(uid) != CellMode.SPOOF) return
                            val header = ShmReader.readRfHeader() ?: return
                            if (requireSimFlag &&
                                (header.rfFlags and ShmReader.KUROKO_RF_FLAG_SPOOF_SIM_COUNTRY) == 0
                            ) {
                                return
                            }
                            val value = extractor(header)
                            if (value.isNotEmpty()) {
                                param.result = value
                            }
                        } catch (t: Throwable) {
                            logOnce("tel_id_$name", "$name hook error: $t")
                        }
                    }
                })
                hookedIdentityCount += hooks.size
            }

            for (n in netNumericNames) hookStringGetter(n, false) { it.operatorNumeric() }
            for (n in netCountryNames) hookStringGetter(n, false) { it.countryIso }
            for (n in netAlphaNames) hookStringGetter(n, false) { it.carrierAlpha }
            for (n in simCountryNames) hookStringGetter(n, true) { it.countryIso }
            for (n in simNumericNames) hookStringGetter(n, true) { it.operatorNumeric() }
            for (n in simAlphaNames) hookStringGetter(n, true) { it.carrierAlpha }

            for (n in serviceStateNames) {
                if (n !in presentNames) continue
                val hooks = XposedBridge.hookAllMethods(pimClass, n, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            if (param.throwable != null) return
                            val orig = param.result as? ServiceState ?: return
                            val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                            if (cellModeForUid(uid) != CellMode.SPOOF) return
                            val pair = ShmReader.readCellsWithHeader() ?: return
                            val serving = VirtualCellBuilder.buildServingCellIdentity(pair.first, pair.second)
                            param.result = VirtualCellBuilder.rewriteServiceState(orig, pair.first, serving)
                        } catch (t: Throwable) {
                            logOnce("tel_ss_$n", "$n hook error: $t")
                        }
                    }
                })
                hookedIdentityCount += hooks.size
            }
        } catch (t: Throwable) {
            logOnce("hook_tel_id", "Failed hooking PhoneInterfaceManager operator/country getters: $t")
        }
        XposedBridge.log("$TAG: Hooked PhoneInterfaceManager identity/ServiceState getters ($hookedIdentityCount)")
    }

    // ==================== Process 2: system_server ====================

    fun initSystemServer(cl: ClassLoader) {
        val regClass = XposedHelpers.findClassIfExists(
            "com.android.server.TelephonyRegistry",
            cl
        )
        if (regClass == null) {
            XposedBridge.log("$TAG: TelephonyRegistry not found in system_server")
            return
        }

        // 1. Per-Record location access gates: return false ONLY for HIDE_CELL && !SPOOF_CELL.
        //    SPOOF_CELL passes through so TelephonyRegistry invokes the Record's callback where
        //    our IPhoneStateListener proxy hook swaps in virtual cell/ServiceState data per-UID.
        var locationGateHooks = 0
        try {
            for (m in regClass.declaredMethods) {
                val isLocationGate =
                    m.name == "checkFineLocationAccess" ||
                        m.name == "checkCoarseLocationAccess" ||
                        (m.name.contains("LocationAccess") && m.returnType == Boolean::class.javaPrimitiveType)
                if (!isLocationGate || m.parameterCount < 1) continue

                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val record = param.args.firstOrNull() ?: return
                            val uid = extractRecordUid(record)
                            if (cellModeForUid(uid) == CellMode.HIDE) {
                                param.result = false
                            }
                        } catch (t: Throwable) {
                            logOnce("reg_loc_gate_${m.name}", "TelephonyRegistry.${m.name} hook error: $t")
                        }
                    }
                })
                locationGateHooks++
            }
        } catch (t: Throwable) {
            logOnce("hook_reg_gates", "Failed hooking TelephonyRegistry location gates: $t")
        }

        // 2. Track listener registration & unregistration so callback IBinder -> callerUid is known
        //    BEFORE initial replay, and evicted immediately when an app unregisters its callback.
        var listenHooks = 0
        for (listenName in listOf("listenWithEventList", "listenForSubscriber", "listen")) {
            try {
                val hooks = XposedBridge.hookAllMethods(regClass, listenName, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val uid = Binder.getCallingUid()
                            if (uid <= 0) return
                            val args = param.args ?: return
                            var cbObj: IInterface? = null
                            var eventsArr: IntArray? = null
                            var legacyEventMask: Int? = null
                            for (arg in args) {
                                when (arg) {
                                    is IInterface -> if (cbObj == null) cbObj = arg
                                    is IntArray -> if (eventsArr == null) eventsArr = arg
                                }
                            }
                            if (listenName != "listenWithEventList" && eventsArr == null) {
                                // Legacy listen(..., int events, ...) passes bitmask as the Int after callback
                                val cbIdx = args.indexOfFirst { it is IInterface }
                                if (cbIdx >= 0 && cbIdx + 1 < args.size) {
                                    legacyEventMask = args[cbIdx + 1] as? Int
                                }
                            }
                            val cb = cbObj ?: return
                            val binder = cb.asBinder() ?: return

                            val isUnregister = (eventsArr != null && eventsArr.isEmpty()) ||
                                (legacyEventMask != null && legacyEventMask == 0)
                            if (isUnregister) {
                                synchronized(trackedCallbacks) {
                                    trackedCallbacks.remove(binder)
                                }
                                callbackBinderToUid.remove(binder)
                                return
                            }

                            callbackBinderToUid[binder] = uid
                            if (latestRealServiceState == null) {
                                captureRegistryServiceState(param.thisObject)
                            }
                            if (eventsArr != null) {
                                val wantsSs = eventsArr.contains(1)       // EVENT_SERVICE_STATE_CHANGED
                                val wantsCellInfo = eventsArr.contains(11) // EVENT_CELL_INFO_CHANGED
                                val wantsCellLoc = eventsArr.contains(16)  // EVENT_CELL_LOCATION_CHANGED
                                if (wantsSs || wantsCellInfo || wantsCellLoc) {
                                    recordTrackedCallback(cb, uid)?.let { tracked ->
                                        tracked.wantsServiceState = wantsSs
                                        tracked.wantsCellInfo = wantsCellInfo
                                        tracked.wantsCellLocation = wantsCellLoc
                                    }
                                } else {
                                    synchronized(trackedCallbacks) {
                                        trackedCallbacks.remove(binder)
                                    }
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                })
                listenHooks += hooks.size
            } catch (_: Throwable) {
            }
        }
        try {
            XposedBridge.hookAllMethods(regClass, "remove", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val binder = param.args?.firstOrNull() as? IBinder ?: return
                        synchronized(trackedCallbacks) {
                            trackedCallbacks.remove(binder)
                        }
                        callbackBinderToUid.remove(binder)
                    } catch (_: Throwable) {
                    }
                }
            })
        } catch (_: Throwable) {
        }

        // 3. Sync mRecords -> callbackBinderToUid during notify broadcasts (for SPOOF_CELL)
        //    and track inCellNotify fallback (for HIDE_CELL)
        val notifyMethods = listOf(
            "notifyCellInfoForSubscriber",
            "notifyCellInfo",
            "notifyCellLocationForSubscriber",
            "notifyCellLocation",
            "notifyServiceStateForSubscriber"
        )
        var notifyHooks = 0
        for (name in notifyMethods) {
            try {
                val hooks = XposedBridge.hookAllMethods(regClass, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val args = param.args ?: return
                            if (name == "notifyServiceStateForSubscriber") {
                                for (arg in args) {
                                    if (arg is ServiceState) {
                                        latestRealServiceState = ServiceState(arg)
                                        break
                                    }
                                }
                            }
                            if (ShmReader.hasAnyTargetWithPolicy(ShmReader.KUROKO_POLICY_HIDE_CELL)) {
                                inCellNotify.set(true)
                            }
                            if (ShmReader.hasAnyTargetWithPolicy(ShmReader.KUROKO_POLICY_SPOOF_CELL)) {
                                syncRegistryRecords(param.thisObject)
                            }
                        } catch (_: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        inCellNotify.set(false)
                    }
                })
                notifyHooks += hooks.size
            } catch (_: Throwable) {
            }
        }

        // 4. Hook IPhoneStateListener$Stub$Proxy per-Record callback delivery (SPOOF_CELL)
        hookPhoneStateListenerProxy(cl)

        // 5. Start 1Hz live ServiceState / CellInfo / CellLocation change pump for SPOOF_CELL listeners
        //    so switching regions/carriers while an app (e.g. Cellular-Z) stays open immediately updates
        //    its Network Operator / MCC / MNC / ServiceState without waiting for a physical modem event.
        startTelephonyChangePump()

        if (locationGateHooks == 0) {
            hookLocationAccessPolicyFallback(cl)
        }

        XposedBridge.log(
            "$TAG: Hooked TelephonyRegistry (locationGates=$locationGateHooks, listenHooks=$listenHooks, notifyHooks=$notifyHooks)"
        )
    }

    private fun captureRegistryServiceState(registry: Any?) {
        if (registry == null) return
        try {
            val arr = getFieldValue(registry, "mServiceState") as? Array<*> ?: return
            for (item in arr) {
                if (item is ServiceState) {
                    latestRealServiceState = ServiceState(item)
                    return
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun syncRegistryRecords(registry: Any?) {
        if (registry == null) return
        try {
            val records = getFieldValue(registry, "mRecords") as? Iterable<*> ?: return
            synchronized(records) {
                for (rec in records) {
                    if (rec == null) continue
                    val uid = extractRecordUid(rec)
                    if (uid <= 0) continue
                    val cb = getFieldValue(rec, "callback") as? IInterface ?: continue
                    val binder = cb.asBinder() ?: continue
                    callbackBinderToUid[binder] = uid
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun hookPhoneStateListenerProxy(cl: ClassLoader) {
        val proxyCandidates = listOf(
            "com.android.internal.telephony.IPhoneStateListener\$Stub\$Proxy",
            "com.android.internal.telephony.ITelephonyCallback\$Stub\$Proxy"
        )
        for (clsName in proxyCandidates) {
            val proxyCls = XposedHelpers.findClassIfExists(clsName, cl) ?: continue
            try {
                XposedBridge.hookAllMethods(proxyCls, "onCellInfoChanged", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (inOurTelPump.get() == true) return
                        try {
                            val proxy = param.thisObject ?: return
                            val uid = lookupCallbackUid(proxy)
                            val tracked = recordTrackedCallback(proxy, uid)
                            tracked?.wantsCellInfo = true
                            if (cellModeForUid(uid) != CellMode.SPOOF) return
                            val pair = ShmReader.readCellsWithHeader() ?: return
                            if (pair.second.isEmpty()) return
                            param.args[0] = VirtualCellBuilder.buildCellInfoList(pair.first, pair.second)
                            tracked?.lastPushedCellEpoch = pair.first.rfEpoch
                        } catch (t: Throwable) {
                            logOnce("proxy_cell_info", "onCellInfoChanged spoof error: $t")
                        }
                    }
                })

                XposedBridge.hookAllMethods(proxyCls, "onCellLocationChanged", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (inOurTelPump.get() == true) return
                        try {
                            val proxy = param.thisObject ?: return
                            val uid = lookupCallbackUid(proxy)
                            val tracked = recordTrackedCallback(proxy, uid)
                            tracked?.wantsCellLocation = true
                            if (cellModeForUid(uid) != CellMode.SPOOF) return
                            val pair = ShmReader.readCellsWithHeader() ?: return
                            if (pair.second.isEmpty()) return
                            val paramType = (param.method as? Method)?.parameterTypes?.firstOrNull()
                            val replacement: Any? = if (paramType != null && Bundle::class.java.isAssignableFrom(paramType)) {
                                VirtualCellBuilder.buildGsmCellLocationBundle(pair.second)
                            } else {
                                VirtualCellBuilder.buildServingCellIdentity(pair.first, pair.second)
                            }
                            if (replacement != null) {
                                param.args[0] = replacement
                            }
                        } catch (t: Throwable) {
                            logOnce("proxy_cell_loc", "onCellLocationChanged spoof error: $t")
                        }
                    }
                })

                XposedBridge.hookAllMethods(proxyCls, "onServiceStateChanged", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (inOurTelPump.get() == true) return
                        try {
                            val orig = param.args.firstOrNull() as? ServiceState ?: return
                            val proxy = param.thisObject ?: return
                            val uid = lookupCallbackUid(proxy)
                            val origCopy = ServiceState(orig)
                            latestRealServiceState = origCopy
                            val tracked = recordTrackedCallback(proxy, uid)
                            if (tracked != null) {
                                tracked.wantsServiceState = true
                                tracked.lastOrigServiceState = origCopy
                            }
                            if (cellModeForUid(uid) != CellMode.SPOOF) return
                            val pair = ShmReader.readCellsWithHeader() ?: return
                            val serving = VirtualCellBuilder.buildServingCellIdentity(pair.first, pair.second)
                            param.args[0] = VirtualCellBuilder.rewriteServiceState(orig, pair.first, serving)
                            tracked?.lastPushedPlmnKey = plmnStateKey(pair.first, pair.second)
                        } catch (t: Throwable) {
                            logOnce("proxy_ss", "onServiceStateChanged spoof error: $t")
                        }
                    }
                })

                XposedBridge.log("$TAG: Hooked $clsName (onCellInfoChanged/onCellLocationChanged/onServiceStateChanged)")
            } catch (t: Throwable) {
                logOnce("hook_proxy_$clsName", "Failed hooking $clsName: $t")
            }
        }
    }

    private fun lookupCallbackUid(proxyObj: Any?): Int {
        val binder = (proxyObj as? IInterface)?.asBinder() ?: return -1
        return callbackBinderToUid[binder] ?: -1
    }

    private fun recordTrackedCallback(proxyObj: Any, uid: Int): TrackedTelCallback? {
        if (uid < 10000) return null
        val binder = (proxyObj as? IInterface)?.asBinder() ?: return null
        if (!binder.isBinderAlive) return null
        return synchronized(trackedCallbacks) {
            val existing = trackedCallbacks[binder]
            if (existing != null) {
                existing.proxy = proxyObj
                existing.uid = uid
                existing
            } else {
                TrackedTelCallback(binder = binder, proxy = proxyObj, uid = uid).also {
                    trackedCallbacks[binder] = it
                }
            }
        }
    }

    private fun plmnStateKey(header: ShmReader.RfHeader, cells: List<ShmReader.CellEntry>): String {
        val srv = cells.firstOrNull { it.registered } ?: cells.firstOrNull()
        val srvCi = srv?.ci ?: 0L
        val srvRat = srv?.rat ?: 0
        return "${header.carrierMcc}-${header.carrierMnc}-${header.mncDigits}-${header.countryIso}-${header.carrierAlpha}-$srvRat-$srvCi"
    }

    private fun startTelephonyChangePump() {
        if (!telPumpStarted.compareAndSet(false, true)) return
        val t = Thread({
            while (true) {
                try {
                    Thread.sleep(1000L)
                    runTelephonyPumpTick()
                } catch (_: InterruptedException) {
                    return@Thread
                } catch (tr: Throwable) {
                    logOnce("tel_pump_err", "Telephony change pump error: $tr")
                }
            }
        }, "Kuroko-1Hz-Tel")
        t.isDaemon = true
        t.start()
        XposedBridge.log("$TAG: 1Hz Telephony ServiceState/CellInfo change pump started")
    }

    private fun runTelephonyPumpTick() {
        val snapshotList: List<TrackedTelCallback> = synchronized(trackedCallbacks) {
            if (trackedCallbacks.isEmpty()) return
            ArrayList(trackedCallbacks.values)
        }
        val pair = ShmReader.readCellsWithHeader()
        val plmnKey = if (pair != null) plmnStateKey(pair.first, pair.second) else null

        var cachedCells: List<Any>? = null
        var cachedServingId: Any? = null
        var cachedGsmBundle: Bundle? = null
        val deadBinders = ArrayList<IBinder>()

        for (cb in snapshotList) {
            if (!cb.binder.isBinderAlive) {
                deadBinders.add(cb.binder)
                continue
            }
            val mode = cellModeForUid(cb.uid)
            if (mode != CellMode.SPOOF || pair == null) {
                // Simulation stopped or policy switched off: restore real ServiceState once if previously spoofed
                if (cb.lastPushedPlmnKey != null && cb.wantsServiceState) {
                    val realSs = cb.lastOrigServiceState ?: latestRealServiceState
                    if (realSs != null) {
                        val ok = invokeProxyMethod(cb.proxy, "onServiceStateChanged", ServiceState(realSs))
                        if (!ok) deadBinders.add(cb.binder)
                    }
                    cb.lastPushedPlmnKey = null
                    cb.lastPushedCellEpoch = -1
                }
                continue
            }

            val header = pair.first
            val cells = pair.second
            val plmnChanged = plmnKey != null && plmnKey != cb.lastPushedPlmnKey
            var pendingServiceState = false

            if (plmnChanged && cb.wantsServiceState) {
                val baseSs = cb.lastOrigServiceState ?: latestRealServiceState
                if (baseSs != null) {
                    if (cachedServingId == null && cells.isNotEmpty()) {
                        cachedServingId = VirtualCellBuilder.buildServingCellIdentity(header, cells)
                    }
                    val rewritten = VirtualCellBuilder.rewriteServiceState(
                        baseSs,
                        header,
                        cachedServingId as? android.telephony.CellIdentity
                    )
                    if (!invokeProxyMethod(cb.proxy, "onServiceStateChanged", rewritten)) {
                        deadBinders.add(cb.binder)
                        continue
                    }
                } else {
                    pendingServiceState = true
                }
            }

            if (plmnChanged && cb.wantsCellLocation && cells.isNotEmpty()) {
                val locMethod = findMethodByName(cb.proxy.javaClass, "onCellLocationChanged", 1)
                if (locMethod != null) {
                    val pType = locMethod.parameterTypes.firstOrNull()
                    val arg: Any? = if (pType != null && Bundle::class.java.isAssignableFrom(pType)) {
                        if (cachedGsmBundle == null) {
                            cachedGsmBundle = VirtualCellBuilder.buildGsmCellLocationBundle(cells)
                        }
                        cachedGsmBundle
                    } else {
                        if (cachedServingId == null) {
                            cachedServingId = VirtualCellBuilder.buildServingCellIdentity(header, cells)
                        }
                        cachedServingId
                    }
                    if (arg != null && !invokeProxyMethod(cb.proxy, "onCellLocationChanged", arg)) {
                        deadBinders.add(cb.binder)
                        continue
                    }
                }
            }

            if (plmnChanged && !pendingServiceState) {
                cb.lastPushedPlmnKey = plmnKey
            }

            if (cb.wantsCellInfo && cells.isNotEmpty() &&
                (plmnChanged || header.rfEpoch != cb.lastPushedCellEpoch)
            ) {
                if (cachedCells == null) {
                    cachedCells = VirtualCellBuilder.buildCellInfoList(header, cells)
                }
                if (cachedCells.isNotEmpty()) {
                    if (!invokeProxyMethod(cb.proxy, "onCellInfoChanged", cachedCells)) {
                        deadBinders.add(cb.binder)
                        continue
                    }
                    cb.lastPushedCellEpoch = header.rfEpoch
                }
            }
        }

        if (deadBinders.isNotEmpty()) {
            synchronized(trackedCallbacks) {
                for (b in deadBinders) {
                    trackedCallbacks.remove(b)
                }
            }
        }
    }

    private fun invokeProxyMethod(proxy: Any, methodName: String, arg: Any): Boolean {
        val m = findMethodByName(proxy.javaClass, methodName, 1) ?: return true
        inOurTelPump.set(true)
        return try {
            m.invoke(proxy, arg)
            true
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            if (cause is RemoteException) {
                false
            } else {
                logOnce("tel_pump_inv_$methodName", "Telephony pump $methodName error: $cause")
                true
            }
        } finally {
            inOurTelPump.set(false)
        }
    }

    private fun hookLocationAccessPolicyFallback(cl: ClassLoader) {
        try {
            val policyCls = XposedHelpers.findClassIfExists(
                "android.telephony.LocationAccessPolicy",
                cl
            ) ?: return
            val resultEnumCls = XposedHelpers.findClassIfExists(
                "android.telephony.LocationAccessPolicy\$LocationPermissionResult",
                cl
            ) ?: return
            val deniedSoft = resultEnumCls.enumConstants?.firstOrNull {
                it.toString() == "DENIED_SOFT" || it.toString() == "DENIED_HARD"
            } ?: return

            XposedBridge.hookAllMethods(policyCls, "checkLocationPermission", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        if (inCellNotify.get() != true) return
                        val query = param.args.getOrNull(1) ?: return
                        val uid = (getFieldValue(query, "callingUid") as? Int) ?: return
                        if (cellModeForUid(uid) == CellMode.HIDE) {
                            param.result = deniedSoft
                        }
                    } catch (_: Throwable) {
                    }
                }
            })
            XposedBridge.log("$TAG: Installed LocationAccessPolicy fallback hook for TelephonyRegistry")
        } catch (t: Throwable) {
            logOnce("hook_lap_fallback", "LocationAccessPolicy fallback hook failed: $t")
        }
    }

    private fun extractRecordUid(record: Any): Int {
        val uid = (getFieldValue(record, "callerUid") as? Int)
            ?: (getFieldValue(record, "mCallerUid") as? Int)
            ?: (getFieldValue(record, "uid") as? Int)
        return uid ?: -1
    }

    private fun findMethodByName(cls: Class<*>, name: String, paramCount: Int): Method? {
        val key = "${cls.name}#$name/$paramCount"
        methodCache[key]?.let { return it }
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
        return null
    }

    private fun getFieldValue(obj: Any, fieldName: String): Any? {
        val key = "${obj.javaClass.name}.$fieldName"
        var f = fieldCache[key]
        if (f == null) {
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
        }
        return try {
            f?.get(obj)
        } catch (_: Throwable) {
            null
        }
    }

    private fun logOnce(key: String, msg: String) {
        if (loggedOnceKeys.add(key)) {
            XposedBridge.log("$TAG: $msg")
        }
    }
}
