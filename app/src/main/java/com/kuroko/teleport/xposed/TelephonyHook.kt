package com.kuroko.teleport.xposed

import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import android.telephony.CellIdentity
import android.telephony.ServiceState
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
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

    // AOSP TelephonyCallback.EVENT_* constants (stored in TelephonyRegistry.Record.eventList & listenWithEventList int[])
    private const val EVENT_SERVICE_STATE_CHANGED = 1
    private const val EVENT_CELL_LOCATION_CHANGED = 5
    private const val EVENT_CELL_INFO_CHANGED = 11

    // Legacy PhoneStateListener.LISTEN_* bitmask constants (listen / listenForSubscriber)
    private const val LISTEN_SERVICE_STATE = 0x01
    private const val LISTEN_CELL_LOCATION = 0x10
    private const val LISTEN_CELL_INFO = 0x400

    private enum class CellMode { PASS, HIDE, SPOOF }

    private class TrackedTelCallback(
        val binder: IBinder,
        @Volatile var proxy: Any,
        @Volatile var uid: Int,
        @Volatile var registryRef: WeakReference<Any>,
        @Volatile var recordRef: WeakReference<Any>,
        @Volatile var subId: Int = -1,
        @Volatile var phoneId: Int = -1,
        @Volatile var wantsServiceState: Boolean = false,
        @Volatile var wantsCellInfo: Boolean = false,
        @Volatile var wantsCellLocation: Boolean = false,
        @Volatile var lastOrigServiceState: ServiceState? = null,
        @Volatile var lastPushedPlmnKey: String? = null,
        @Volatile var lastPushedCellEpoch: Int = -1
    )

    private class TrackedSubCallback(
        val binder: IBinder,
        @Volatile var proxy: Any,
        @Volatile var uid: Int,
        @Volatile var registryRef: WeakReference<Any>,
        @Volatile var recordRef: WeakReference<Any>,
        @Volatile var lastPushedSimKey: String? = null
    )

    private class InitialReplayState(
        @Volatile var sanitizedOrigSs: ServiceState? = null,
        @Volatile var pushedPlmnKey: String? = null,
        @Volatile var pushedCellEpoch: Int = -1
    )

    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()
    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val missingFields = ConcurrentHashMap.newKeySet<String>()
    private val methodCache = ConcurrentHashMap<String, Method>()
    private val missingMethods = ConcurrentHashMap.newKeySet<String>()
    private val telPumpStarted = AtomicBoolean(false)

    // Live TelephonyRegistry instance(s) in system_server
    private val activeRegistries = CopyOnWriteArraySet<Any>()

    // Weak maps from Binder -> callerUid / accepted callback / transient initial-replay cache (auto-cleaned on GC)
    private val callbackBinderToUid = Collections.synchronizedMap(WeakHashMap<IBinder, Int>())
    private val trackedCallbacks = Collections.synchronizedMap(WeakHashMap<IBinder, TrackedTelCallback>())
    private val trackedSubCallbacks = Collections.synchronizedMap(WeakHashMap<IBinder, TrackedSubCallback>())
    private val initialReplayCache = Collections.synchronizedMap(WeakHashMap<IBinder, InitialReplayState>())

    // Fallback context flag for TelephonyRegistry if checkFineLocationAccess/checkCoarseLocationAccess are absent
    private val inCellNotify = ThreadLocal.withInitial { false }

    // Re-entrancy guard for Kuroko-1Hz-Tel active push so we never overwrite real ServiceState with spoofed data
    private val inOurTelPump = ThreadLocal.withInitial { false }

    // Guard so our own CellMode.HIDE hook does not distort true permission probing in isRecordLocationPermitted()
    private val inPermProbe = ThreadLocal.withInitial { false }

    // Guard so initialReplayCache is populated ONLY during synchronous initial replay inside listen*()
    private val inListenCall = ThreadLocal.withInitial { false }

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

        // 7. Per-UID SubscriptionInfo rewriting on ISub (SubscriptionManagerService / SubscriptionController)
        //    so enabling Local SIM Country Sync rewrites SIM/Carrier name, country ISO & MCC/MNC per-UID.
        hookSubscriptionService(cl)
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
            "getSimOperatorNameForSubId",
            "getSimCarrierIdName",
            "getSimSpecificCarrierIdName",
            "getSubscriptionCarrierIdName",
            "getCarrierName"
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

    private fun hookSubscriptionService(cl: ClassLoader) {
        val candidateClasses = listOf(
            "com.android.internal.telephony.subscription.SubscriptionManagerService", // Android 14–17
            "com.android.internal.telephony.SubscriptionController"                   // Android 12–13
        )
        val subGetterNames = setOf(
            "getActiveSubscriptionInfoList",
            "getActiveSubInfoList",
            "getActiveSubscriptionInfo",
            "getActiveSubscriptionInfoForSimSlotIndex",
            "getActiveSubscriptionInfoForIccId",
            "getAllSubInfoList",
            "getAllSubscriptionInfoList",
            "getAccessibleSubscriptionInfoList",
            "getAvailableSubscriptionInfoList",
            "getCompleteActiveSubscriptionInfoList",
            "getSubscriptionInfo"
        )
        var totalHooked = 0
        for (clsName in candidateClasses) {
            val svcCls = XposedHelpers.findClassIfExists(clsName, cl) ?: continue
            val presentNames = HashSet<String>()
            var cur: Class<*>? = svcCls
            while (cur != null && cur != Any::class.java) {
                for (m in cur.declaredMethods) {
                    presentNames.add(m.name)
                }
                cur = cur.superclass
            }
            var clsHooked = 0
            for (methodName in subGetterNames) {
                if (methodName !in presentNames) continue
                try {
                    val hooks = XposedBridge.hookAllMethods(svcCls, methodName, object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                        }

                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                if (param.throwable != null) return
                                val res = param.result ?: return
                                val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                                if (cellModeForUid(uid) != CellMode.SPOOF) return
                                val header = ShmReader.readRfHeader() ?: return
                                if ((header.rfFlags and ShmReader.KUROKO_RF_FLAG_SPOOF_SIM_COUNTRY) == 0) return

                                when (res) {
                                    is SubscriptionInfo -> {
                                        param.result = VirtualCellBuilder.rewriteSubscriptionInfo(res, header)
                                    }
                                    is List<*> -> {
                                        if (res.isEmpty()) return
                                        var changed = false
                                        val out = ArrayList<Any?>(res.size)
                                        for (item in res) {
                                            if (item is SubscriptionInfo) {
                                                out.add(VirtualCellBuilder.rewriteSubscriptionInfo(item, header))
                                                changed = true
                                            } else {
                                                out.add(item)
                                            }
                                        }
                                        if (changed) {
                                            param.result = out
                                        }
                                    }
                                }
                            } catch (t: Throwable) {
                                logOnce("sub_svc_$methodName", "$methodName hook error: $t")
                            }
                        }
                    })
                    clsHooked += hooks.size
                } catch (t: Throwable) {
                    logOnce("hook_sub_$methodName", "Failed hooking $clsName.$methodName: $t")
                }
            }
            if (clsHooked > 0) {
                totalHooked += clsHooked
                XposedBridge.log("$TAG: Hooked ${svcCls.simpleName} SubscriptionInfo getters ($clsHooked)")
            }
        }
        if (totalHooked == 0) {
            XposedBridge.log("$TAG: No SubscriptionManagerService/SubscriptionController getters hooked")
        }
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

        try {
            XposedBridge.hookAllConstructors(regClass, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    registerActiveRegistry(param.thisObject)
                }
            })
        } catch (_: Throwable) {
        }

        // 1. Per-Record location access gates: return false ONLY for HIDE_CELL && !SPOOF_CELL.
        //    SPOOF_CELL passes through so TelephonyRegistry invokes the Record's callback where
        //    our IPhoneStateListener proxy hook swaps in virtual cell/ServiceState data per-UID.
        //    Respect inPermProbe so isRecordLocationPermitted() probes AOSP's true permission state.
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
                            registerActiveRegistry(param.thisObject)
                            if (inPermProbe.get() == true) return
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

        // 2. Track ONLY system-accepted listener registrations (verified against mRecords in
        //    afterHookedMethod). beforeHookedMethod ONLY handles immediate unregistration or sets
        //    a transient callbackBinderToUid hint for synchronous initial replay inside listen*.
        var listenHooks = 0
        for (listenName in listOf("listenWithEventList", "listenForSubscriber", "listen")) {
            try {
                val hooks = XposedBridge.hookAllMethods(regClass, listenName, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            registerActiveRegistry(param.thisObject)
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
                                val cbIdx = args.indexOfFirst { it is IInterface }
                                if (cbIdx >= 0 && cbIdx + 1 < args.size) {
                                    legacyEventMask = args[cbIdx + 1] as? Int
                                }
                            }
                            val cb = cbObj ?: return
                            val binder = cb.asBinder() ?: return

                            val isUnregister = (eventsArr != null && eventsArr.isEmpty()) ||
                                (legacyEventMask != null && legacyEventMask == 0)
                            param.setObjectExtra("kuroko_tel_uid", uid)
                            param.setObjectExtra("kuroko_tel_cb", cb)
                            param.setObjectExtra("kuroko_tel_binder", binder)
                            param.setObjectExtra("kuroko_tel_events", eventsArr)
                            param.setObjectExtra("kuroko_tel_mask", legacyEventMask)
                            param.setObjectExtra("kuroko_tel_unreg", isUnregister)

                            if (isUnregister) {
                                evictCallbackBinder(binder)
                                return
                            }

                            // Transient UID hint only (do NOT add to trackedCallbacks until accepted in afterHookedMethod!)
                            callbackBinderToUid[binder] = uid
                            initialReplayCache.remove(binder)
                            val prevInListen = inListenCall.get() == true
                            param.setObjectExtra("kuroko_prev_listen", prevInListen)
                            inListenCall.set(true)
                        } catch (_: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        val prevInListen = param.getObjectExtra("kuroko_prev_listen") as? Boolean
                        try {
                            val binder = param.getObjectExtra("kuroko_tel_binder") as? IBinder ?: return
                            val isUnregister = (param.getObjectExtra("kuroko_tel_unreg") as? Boolean) ?: false
                            if (isUnregister) return

                            val replay = initialReplayCache.remove(binder)
                            if (param.throwable != null) {
                                evictCallbackBinder(binder)
                                return
                            }

                            val registry = param.thisObject ?: run {
                                evictCallbackBinder(binder)
                                return
                            }
                            val liveRecord = findLiveRecord(registry, binder)
                            if (liveRecord == null) {
                                // TelephonyRegistry rejected or did not retain this registration
                                evictCallbackBinder(binder)
                                return
                            }

                            val cb = param.getObjectExtra("kuroko_tel_cb") as? IInterface ?: return
                            val callingUid = (param.getObjectExtra("kuroko_tel_uid") as? Int) ?: -1
                            val recUid = extractRecordUid(liveRecord).takeIf { it > 0 } ?: callingUid
                            if (recUid <= 0) {
                                evictCallbackBinder(binder)
                                return
                            }
                            callbackBinderToUid[binder] = recUid
                            if (recUid < 10000 || !binder.isBinderAlive) {
                                synchronized(trackedCallbacks) {
                                    trackedCallbacks.remove(binder)
                                }
                                return
                            }

                            val eventsArr = param.getObjectExtra("kuroko_tel_events") as? IntArray
                            val legacyMask = param.getObjectExtra("kuroko_tel_mask") as? Int
                            val (wantsSs, wantsCellInfo, wantsCellLoc) =
                                extractAcceptedEvents(registry, liveRecord, eventsArr, legacyMask)
                            if (!wantsSs && !wantsCellInfo && !wantsCellLoc) {
                                synchronized(trackedCallbacks) {
                                    trackedCallbacks.remove(binder)
                                }
                                return
                            }

                            val subId = extractRecordSubId(liveRecord)
                            val phoneId = extractRecordPhoneId(registry, liveRecord)
                            synchronized(trackedCallbacks) {
                                val existing = trackedCallbacks[binder]
                                if (existing != null) {
                                    existing.proxy = cb
                                    existing.uid = recUid
                                    existing.registryRef = WeakReference(registry)
                                    existing.recordRef = WeakReference(liveRecord)
                                    existing.subId = subId
                                    existing.phoneId = phoneId
                                    existing.wantsServiceState = wantsSs
                                    existing.wantsCellInfo = wantsCellInfo
                                    existing.wantsCellLocation = wantsCellLoc
                                    if (replay?.sanitizedOrigSs != null) {
                                        existing.lastOrigServiceState = replay.sanitizedOrigSs
                                    }
                                    if (replay?.pushedPlmnKey != null) {
                                        existing.lastPushedPlmnKey = replay.pushedPlmnKey
                                    }
                                    if (replay != null && replay.pushedCellEpoch >= 0) {
                                        existing.lastPushedCellEpoch = replay.pushedCellEpoch
                                    }
                                } else {
                                    trackedCallbacks[binder] = TrackedTelCallback(
                                        binder = binder,
                                        proxy = cb,
                                        uid = recUid,
                                        registryRef = WeakReference(registry),
                                        recordRef = WeakReference(liveRecord),
                                        subId = subId,
                                        phoneId = phoneId,
                                        wantsServiceState = wantsSs,
                                        wantsCellInfo = wantsCellInfo,
                                        wantsCellLocation = wantsCellLoc,
                                        lastOrigServiceState = replay?.sanitizedOrigSs,
                                        lastPushedPlmnKey = replay?.pushedPlmnKey,
                                        lastPushedCellEpoch = replay?.pushedCellEpoch ?: -1
                                    )
                                }
                            }
                        } catch (_: Throwable) {
                        } finally {
                            if (prevInListen != null) {
                                inListenCall.set(prevInListen)
                            }
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
                        evictCallbackBinder(binder)
                        synchronized(trackedSubCallbacks) {
                            trackedSubCallbacks.remove(binder)
                        }
                    } catch (_: Throwable) {
                    }
                }
            })
        } catch (_: Throwable) {
        }

        // 2b. Track ONLY system-accepted IOnSubscriptionsChangedListener registrations (verified in
        //     afterHookedMethod against mRecords) so toggling Local SIM Country Sync or switching
        //     regions notifies accepted apps (e.g. cell monitor apps) to re-fetch SubscriptionInfo live.
        for (addSubName in listOf(
            "addOnSubscriptionsChangedListener",
            "addOnSubscriptionsChangedListenerWithFeature"
        )) {
            try {
                XposedBridge.hookAllMethods(regClass, addSubName, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            registerActiveRegistry(param.thisObject)
                            val uid = Binder.getCallingUid()
                            if (uid < 10000) return
                            val args = param.args ?: return
                            val cb = args.firstOrNull { it is IInterface } as? IInterface ?: return
                            val binder = cb.asBinder() ?: return
                            param.setObjectExtra("kuroko_sub_uid", uid)
                            param.setObjectExtra("kuroko_sub_cb", cb)
                            param.setObjectExtra("kuroko_sub_binder", binder)
                        } catch (_: Throwable) {
                        }
                    }

                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val binder = param.getObjectExtra("kuroko_sub_binder") as? IBinder ?: return
                            if (param.throwable != null) {
                                synchronized(trackedSubCallbacks) {
                                    trackedSubCallbacks.remove(binder)
                                }
                                return
                            }
                            val registry = param.thisObject ?: return
                            val liveRecord = findLiveRecord(registry, binder)
                            if (liveRecord == null) {
                                synchronized(trackedSubCallbacks) {
                                    trackedSubCallbacks.remove(binder)
                                }
                                return
                            }
                            val cb = param.getObjectExtra("kuroko_sub_cb") as? IInterface ?: return
                            val callingUid = (param.getObjectExtra("kuroko_sub_uid") as? Int) ?: -1
                            val recUid = extractRecordUid(liveRecord).takeIf { it > 0 } ?: callingUid
                            if (recUid < 10000 || !binder.isBinderAlive) {
                                synchronized(trackedSubCallbacks) {
                                    trackedSubCallbacks.remove(binder)
                                }
                                return
                            }
                            synchronized(trackedSubCallbacks) {
                                val existing = trackedSubCallbacks[binder]
                                if (existing != null) {
                                    existing.proxy = cb
                                    existing.uid = recUid
                                    existing.registryRef = WeakReference(registry)
                                    existing.recordRef = WeakReference(liveRecord)
                                } else {
                                    trackedSubCallbacks[binder] = TrackedSubCallback(
                                        binder = binder,
                                        proxy = cb,
                                        uid = recUid,
                                        registryRef = WeakReference(registry),
                                        recordRef = WeakReference(liveRecord)
                                    )
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                })
            } catch (_: Throwable) {
            }
        }
        try {
            XposedBridge.hookAllMethods(regClass, "removeOnSubscriptionsChangedListener", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val args = param.args ?: return
                        val cb = args.firstOrNull { it is IInterface } as? IInterface ?: return
                        val binder = cb.asBinder() ?: return
                        synchronized(trackedSubCallbacks) {
                            trackedSubCallbacks.remove(binder)
                        }
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
                            registerActiveRegistry(param.thisObject)
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
        //    so switching regions/carriers while an app (e.g. cell monitor apps) stays open immediately updates
        //    its Network Operator / MCC / MNC / ServiceState without waiting for a physical modem event.
        startTelephonyChangePump()

        if (locationGateHooks == 0) {
            hookLocationAccessPolicyFallback(cl)
        }

        XposedBridge.log(
            "$TAG: Hooked TelephonyRegistry (locationGates=$locationGateHooks, listenHooks=$listenHooks, notifyHooks=$notifyHooks)"
        )
    }

    private fun registerActiveRegistry(registry: Any?) {
        if (registry != null && !activeRegistries.contains(registry)) {
            activeRegistries.add(registry)
        }
    }

    private fun evictCallbackBinder(binder: IBinder) {
        synchronized(trackedCallbacks) {
            trackedCallbacks.remove(binder)
        }
        callbackBinderToUid.remove(binder)
        initialReplayCache.remove(binder)
    }

    private fun findLiveRecord(registry: Any, binder: IBinder): Any? {
        return try {
            val records = getFieldValue(registry, "mRecords") as? Iterable<*> ?: return null
            synchronized(records) {
                for (rec in records) {
                    if (rec == null) continue
                    val recBinder = getFieldValue(rec, "binder") as? IBinder
                    if (recBinder != null && (recBinder === binder || recBinder == binder)) {
                        return rec
                    }
                    val cbBinder = (getFieldValue(rec, "callback") as? IInterface)?.asBinder()
                    if (cbBinder != null && (cbBinder === binder || cbBinder == binder)) {
                        return rec
                    }
                    val subCbBinder =
                        (getFieldValue(rec, "onSubscriptionsChangedListenerCallback") as? IInterface)?.asBinder()
                    if (subCbBinder != null && (subCbBinder === binder || subCbBinder == binder)) {
                        return rec
                    }
                    val oppCbBinder =
                        (getFieldValue(rec, "onOpportunisticSubscriptionsChangedListenerCallback") as? IInterface)
                            ?.asBinder()
                    if (oppCbBinder != null && (oppCbBinder === binder || oppCbBinder == binder)) {
                        return rec
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun findLiveRegistryAndRecord(binder: IBinder): Pair<Any, Any>? {
        for (reg in activeRegistries) {
            val rec = findLiveRecord(reg, binder)
            if (rec != null) return Pair(reg, rec)
        }
        return null
    }

    private fun findLocationAccessMethod(registryCls: Class<*>, recordCls: Class<*>, name: String): Method? {
        val key = "${registryCls.name}#$name(${recordCls.name},int)"
        methodCache[key]?.let { return it }
        if (missingMethods.contains(key)) return null
        var cur: Class<*>? = registryCls
        while (cur != null && cur != Any::class.java) {
            for (m in cur.declaredMethods) {
                if (m.name == name &&
                    m.parameterCount == 2 &&
                    m.parameterTypes[0].isAssignableFrom(recordCls) &&
                    m.parameterTypes[1] == Int::class.javaPrimitiveType &&
                    m.returnType == Boolean::class.javaPrimitiveType
                ) {
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

    /**
     * Probes AOSP TelephonyRegistry's live per-Record location permission checks
     * (checkFineLocationAccess & checkCoarseLocationAccess at Build.VERSION_CODES.Q).
     * Fails closed (false to false) if either method cannot be invoked.
     */
    private fun isRecordLocationPermitted(registry: Any, record: Any): Pair<Boolean, Boolean> {
        val prevProbe = inPermProbe.get() == true
        inPermProbe.set(true)
        val token = Binder.clearCallingIdentity()
        return try {
            val fineMethod = findLocationAccessMethod(registry.javaClass, record.javaClass, "checkFineLocationAccess")
            val coarseMethod =
                findLocationAccessMethod(registry.javaClass, record.javaClass, "checkCoarseLocationAccess")
            val hasFine = try {
                if (fineMethod != null) {
                    (fineMethod.invoke(registry, record, Build.VERSION_CODES.Q) as? Boolean) ?: false
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
            val hasCoarse = try {
                if (coarseMethod != null) {
                    (coarseMethod.invoke(registry, record, Build.VERSION_CODES.Q) as? Boolean) ?: false
                } else {
                    false
                }
            } catch (_: Throwable) {
                false
            }
            Pair(hasFine, hasCoarse)
        } finally {
            Binder.restoreCallingIdentity(token)
            inPermProbe.set(prevProbe)
        }
    }

    /**
     * Applies AOSP TelephonyRegistry's exact per-receiver ServiceState location sanitization:
     *  - hasFine == true -> full copy
     *  - !hasFine && hasCoarse -> createLocationInfoSanitizedCopy(false)
     *  - !hasFine && !hasCoarse -> createLocationInfoSanitizedCopy(true)
     * Fails closed (returns null) if sanitization cannot be invoked when !hasFine.
     */
    private fun sanitizeServiceStateForReceiver(
        ss: ServiceState,
        hasFine: Boolean,
        hasCoarse: Boolean
    ): ServiceState? {
        if (hasFine) {
            return ServiceState(ss)
        }
        return try {
            val m = findMethodByName(ss.javaClass, "createLocationInfoSanitizedCopy", 1)
            if (m != null && m.parameterTypes[0] == Boolean::class.javaPrimitiveType) {
                val removeCoarse = !hasCoarse
                m.invoke(ss, removeCoarse) as? ServiceState
            } else {
                null
            }
        } catch (t: Throwable) {
            logOnce("sanitize_ss_err", "ServiceState.createLocationInfoSanitizedCopy failed (fail-closed): $t")
            null
        }
    }

    private fun extractRecordSubId(record: Any): Int {
        return (getFieldValue(record, "subId") as? Int) ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    private fun extractRecordPhoneId(registry: Any, record: Any): Int {
        val rawPhoneId = (getFieldValue(record, "phoneId") as? Int) ?: -1
        val subId = extractRecordSubId(record)
        val isDefaultSub = (subId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID)
        val isDefaultPhone = (rawPhoneId == Int.MAX_VALUE) // SubscriptionManager.DEFAULT_PHONE_INDEX

        // 1. Default-subscription listeners follow TelephonyRegistry.mDefaultPhoneId
        if (isDefaultSub) {
            val defaultPhoneId = (getFieldValue(registry, "mDefaultPhoneId") as? Int) ?: -1
            if (defaultPhoneId >= 0 && defaultPhoneId != Int.MAX_VALUE) return defaultPhoneId
            return if (rawPhoneId >= 0 && !isDefaultPhone) rawPhoneId else -1
        }

        // 2. Concrete slot index on Record
        if (rawPhoneId >= 0 && !isDefaultPhone) {
            return rawPhoneId
        }

        // 3. Explicit DEFAULT_PHONE_INDEX -> mDefaultPhoneId
        if (isDefaultPhone) {
            val defaultPhoneId = (getFieldValue(registry, "mDefaultPhoneId") as? Int) ?: -1
            return if (defaultPhoneId >= 0 && defaultPhoneId != Int.MAX_VALUE) defaultPhoneId else -1
        }

        // 4. Concrete subscription ID -> resolve strictly via SubscriptionManager.getPhoneId(subId).
        //    Never fall back to mDefaultPhoneId if an explicit subId fails resolution!
        if (subId >= 0) {
            try {
                val m = findMethodByName(SubscriptionManager::class.java, "getPhoneId", 1)
                if (m != null && m.parameterTypes[0] == Int::class.javaPrimitiveType) {
                    val resolved = (m.invoke(null, subId) as? Int) ?: -1
                    if (resolved >= 0 && resolved != Int.MAX_VALUE) return resolved
                }
            } catch (_: Throwable) {
            }
        }

        return -1
    }

    /**
     * Reads an atomic defensive copy of the real ServiceState strictly for THIS Record's own phoneId slot.
     * Never falls back to another SIM slot or any global unsanitized cache.
     */
    private fun readSlotRealServiceState(registry: Any, record: Any): ServiceState? {
        return try {
            val phoneId = extractRecordPhoneId(registry, record)
            if (phoneId < 0) return null
            val recordsLock = getFieldValue(registry, "mRecords")
            if (recordsLock != null) {
                synchronized(recordsLock) {
                    val arr = getFieldValue(registry, "mServiceState") as? Array<*>
                    val raw = if (arr != null && phoneId in arr.indices) arr[phoneId] as? ServiceState else null
                    raw?.let { ServiceState(it) }
                }
            } else {
                val arr = getFieldValue(registry, "mServiceState") as? Array<*>
                val raw = if (arr != null && phoneId in arr.indices) arr[phoneId] as? ServiceState else null
                raw?.let { ServiceState(it) }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun readLiveEventSetFlags(registry: Any, record: Any): Triple<Boolean, Boolean, Boolean>? {
        return try {
            val recordsLock = getFieldValue(registry, "mRecords")
            if (recordsLock != null) {
                synchronized(recordsLock) {
                    val s = getFieldValue(record, "eventList") as? Set<*> ?: return null
                    Triple(
                        s.contains(EVENT_SERVICE_STATE_CHANGED),
                        s.contains(EVENT_CELL_INFO_CHANGED),
                        s.contains(EVENT_CELL_LOCATION_CHANGED)
                    )
                }
            } else {
                val s = getFieldValue(record, "eventList") as? Set<*> ?: return null
                Triple(
                    s.contains(EVENT_SERVICE_STATE_CHANGED),
                    s.contains(EVENT_CELL_INFO_CHANGED),
                    s.contains(EVENT_CELL_LOCATION_CHANGED)
                )
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun extractAcceptedEvents(
        registry: Any,
        record: Any,
        fallbackEventsArr: IntArray?,
        fallbackLegacyMask: Int?
    ): Triple<Boolean, Boolean, Boolean> {
        readLiveEventSetFlags(registry, record)?.let { return it }
        if (fallbackEventsArr != null) {
            return Triple(
                fallbackEventsArr.contains(EVENT_SERVICE_STATE_CHANGED),
                fallbackEventsArr.contains(EVENT_CELL_INFO_CHANGED),
                fallbackEventsArr.contains(EVENT_CELL_LOCATION_CHANGED)
            )
        }
        if (fallbackLegacyMask != null) {
            return Triple(
                (fallbackLegacyMask and LISTEN_SERVICE_STATE) != 0,
                (fallbackLegacyMask and LISTEN_CELL_INFO) != 0,
                (fallbackLegacyMask and LISTEN_CELL_LOCATION) != 0
            )
        }
        return Triple(false, false, false)
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
                            val binder = (proxy as? IInterface)?.asBinder() ?: return
                            val tracked = synchronized(trackedCallbacks) { trackedCallbacks[binder] }
                            val hintUid = tracked?.uid ?: lookupCallbackUid(proxy)
                            if (hintUid > 0 && cellModeForUid(hintUid) != CellMode.SPOOF) return
                            val regAndRec = resolveLiveRegistryAndRecord(binder, tracked) ?: return
                            val uid = extractRecordUid(regAndRec.second).takeIf { it > 0 } ?: hintUid
                            if (cellModeForUid(uid) != CellMode.SPOOF) return
                            val (hasFine, _) = isRecordLocationPermitted(regAndRec.first, regAndRec.second)
                            if (!hasFine) return
                            val pair = ShmReader.readCellsWithHeader() ?: return
                            if (pair.second.isEmpty()) return
                            param.args[0] = VirtualCellBuilder.buildCellInfoList(pair.first, pair.second)
                            if (tracked != null) {
                                tracked.lastPushedCellEpoch = pair.first.rfEpoch
                            } else if (inListenCall.get() == true) {
                                getOrCreateReplayState(binder).pushedCellEpoch = pair.first.rfEpoch
                            }
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
                            val binder = (proxy as? IInterface)?.asBinder() ?: return
                            val tracked = synchronized(trackedCallbacks) { trackedCallbacks[binder] }
                            val hintUid = tracked?.uid ?: lookupCallbackUid(proxy)
                            if (hintUid > 0 && cellModeForUid(hintUid) != CellMode.SPOOF) return
                            val regAndRec = resolveLiveRegistryAndRecord(binder, tracked) ?: return
                            val uid = extractRecordUid(regAndRec.second).takeIf { it > 0 } ?: hintUid
                            if (cellModeForUid(uid) != CellMode.SPOOF) return
                            val (hasFine, _) = isRecordLocationPermitted(regAndRec.first, regAndRec.second)
                            if (!hasFine) return
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
                            val binder = (proxy as? IInterface)?.asBinder() ?: return
                            val tracked = synchronized(trackedCallbacks) { trackedCallbacks[binder] }
                            val isInitialReplay = (tracked == null && inListenCall.get() == true)
                            val hintUid = tracked?.uid ?: lookupCallbackUid(proxy)
                            if (tracked == null && !isInitialReplay &&
                                hintUid > 0 && cellModeForUid(hintUid) != CellMode.SPOOF
                            ) {
                                return
                            }
                            val regAndRec = resolveLiveRegistryAndRecord(binder, tracked) ?: return
                            val uid = extractRecordUid(regAndRec.second).takeIf { it > 0 } ?: hintUid

                            // orig was already sanitized by TelephonyRegistry for THIS receiver — cache strictly per-callback!
                            if (tracked != null) {
                                tracked.lastOrigServiceState = ServiceState(orig)
                            } else if (isInitialReplay && uid >= 10000) {
                                getOrCreateReplayState(binder).sanitizedOrigSs = ServiceState(orig)
                            }

                            if (cellModeForUid(uid) != CellMode.SPOOF) return

                            val (hasFine, hasCoarse) = isRecordLocationPermitted(regAndRec.first, regAndRec.second)
                            val pair = ShmReader.readCellsWithHeader() ?: return
                            val serving = VirtualCellBuilder.buildServingCellIdentity(pair.first, pair.second)
                            val rewritten = VirtualCellBuilder.rewriteServiceState(orig, pair.first, serving)
                            val sanitized = sanitizeServiceStateForReceiver(rewritten, hasFine, hasCoarse) ?: return
                            param.args[0] = sanitized
                            val key = plmnStateKey(pair.first, pair.second)
                            if (tracked != null) {
                                tracked.lastPushedPlmnKey = key
                            } else if (isInitialReplay && uid >= 10000) {
                                getOrCreateReplayState(binder).pushedPlmnKey = key
                            }
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

    private fun resolveLiveRegistryAndRecord(binder: IBinder, tracked: TrackedTelCallback?): Pair<Any, Any>? {
        val reg = tracked?.registryRef?.get()
        if (reg != null) {
            val rec = findLiveRecord(reg, binder)
            if (rec != null) return Pair(reg, rec)
        }
        return findLiveRegistryAndRecord(binder)
    }

    private fun getOrCreateReplayState(binder: IBinder): InitialReplayState {
        return synchronized(initialReplayCache) {
            initialReplayCache[binder] ?: InitialReplayState().also { initialReplayCache[binder] = it }
        }
    }

    private fun lookupCallbackUid(proxyObj: Any?): Int {
        val binder = (proxyObj as? IInterface)?.asBinder() ?: return -1
        return callbackBinderToUid[binder] ?: -1
    }

    private fun plmnStateKey(header: ShmReader.RfHeader, cells: List<ShmReader.CellEntry>): String {
        val srv = cells.firstOrNull { it.registered } ?: cells.firstOrNull()
        val srvCi = srv?.ci ?: 0L
        val srvRat = srv?.rat ?: 0
        val spoofSim = if ((header.rfFlags and ShmReader.KUROKO_RF_FLAG_SPOOF_SIM_COUNTRY) != 0) 1 else 0
        return "${header.carrierMcc}-${header.carrierMnc}-${header.mncDigits}-${header.countryIso}-${header.carrierAlpha}-$srvRat-$srvCi-$spoofSim"
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
            if (trackedCallbacks.isEmpty()) emptyList() else ArrayList(trackedCallbacks.values)
        }
        val subSnapshotList: List<TrackedSubCallback> = synchronized(trackedSubCallbacks) {
            if (trackedSubCallbacks.isEmpty()) emptyList() else ArrayList(trackedSubCallbacks.values)
        }
        if (snapshotList.isEmpty() && subSnapshotList.isEmpty()) return

        val pair = ShmReader.readCellsWithHeader()
        val plmnKey = if (pair != null) plmnStateKey(pair.first, pair.second) else null

        var cachedCells: List<Any>? = null
        var cachedServingId: CellIdentity? = null
        var cachedGsmBundle: Bundle? = null
        val deadBinders = ArrayList<IBinder>()

        for (cb in snapshotList) {
            if (!cb.binder.isBinderAlive) {
                deadBinders.add(cb.binder)
                continue
            }
            val registry = cb.registryRef.get()
            val liveRecord = if (registry != null) findLiveRecord(registry, cb.binder) else null
            if (registry == null || liveRecord == null) {
                deadBinders.add(cb.binder)
                continue
            }
            cb.recordRef = WeakReference(liveRecord)
            cb.subId = extractRecordSubId(liveRecord)
            cb.phoneId = extractRecordPhoneId(registry, liveRecord)

            readLiveEventSetFlags(registry, liveRecord)?.let { (wantsSs, wantsCellInfo, wantsCellLoc) ->
                cb.wantsServiceState = wantsSs
                cb.wantsCellInfo = wantsCellInfo
                cb.wantsCellLocation = wantsCellLoc
            }
            if (!cb.wantsServiceState && !cb.wantsCellInfo && !cb.wantsCellLocation) {
                deadBinders.add(cb.binder)
                continue
            }

            val mode = cellModeForUid(cb.uid)
            if (mode != CellMode.SPOOF || pair == null) {
                // Simulation stopped or policy switched off: restore real ServiceState strictly for
                // THIS Record's own phoneId slot and sanitized for its live permissions (or fully
                // redacted under HIDE_CELL so real location never leaks)!
                if (cb.lastPushedPlmnKey != null && cb.wantsServiceState) {
                    val (restoreFine, restoreCoarse) = if (mode == CellMode.HIDE) {
                        Pair(false, false)
                    } else {
                        isRecordLocationPermitted(registry, liveRecord)
                    }
                    val slotReal = readSlotRealServiceState(registry, liveRecord)
                    val restoredSs = if (slotReal != null) {
                        sanitizeServiceStateForReceiver(slotReal, restoreFine, restoreCoarse)
                    } else {
                        cb.lastOrigServiceState?.let {
                            sanitizeServiceStateForReceiver(it, restoreFine, restoreCoarse)
                        }
                    }
                    if (restoredSs != null) {
                        val ok = invokeProxyMethod(cb.proxy, "onServiceStateChanged", restoredSs)
                        if (!ok) deadBinders.add(cb.binder)
                    }
                }
                cb.lastPushedPlmnKey = null
                cb.lastPushedCellEpoch = -1
                continue
            }

            val header = pair.first
            val cells = pair.second
            val plmnChanged = plmnKey != null && plmnKey != cb.lastPushedPlmnKey
            val needsCellInfoPush = cb.wantsCellInfo && cells.isNotEmpty() &&
                (plmnChanged || header.rfEpoch != cb.lastPushedCellEpoch)
            val needsSsPush = plmnChanged && cb.wantsServiceState
            val needsCellLocPush = plmnChanged && cb.wantsCellLocation && cells.isNotEmpty()
            if (!needsSsPush && !needsCellLocPush && !needsCellInfoPush) {
                if (plmnChanged) {
                    cb.lastPushedPlmnKey = plmnKey
                }
                continue
            }

            val (hasFine, hasCoarse) = isRecordLocationPermitted(registry, liveRecord)
            var pendingServiceState = false

            if (needsSsPush) {
                val baseSs = readSlotRealServiceState(registry, liveRecord) ?: cb.lastOrigServiceState
                if (baseSs != null) {
                    if (cachedServingId == null && cells.isNotEmpty()) {
                        cachedServingId = VirtualCellBuilder.buildServingCellIdentity(header, cells)
                    }
                    val rewritten = VirtualCellBuilder.rewriteServiceState(
                        baseSs,
                        header,
                        cachedServingId
                    )
                    val sanitized = sanitizeServiceStateForReceiver(rewritten, hasFine, hasCoarse)
                    if (sanitized != null) {
                        if (!invokeProxyMethod(cb.proxy, "onServiceStateChanged", sanitized)) {
                            deadBinders.add(cb.binder)
                            continue
                        }
                    }
                } else {
                    pendingServiceState = true
                }
            }

            if (needsCellLocPush && hasFine) {
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

            if (needsCellInfoPush && hasFine) {
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
            for (b in deadBinders) {
                evictCallbackBinder(b)
            }
        }

        // Notify accepted IOnSubscriptionsChangedListener callbacks when SIM spoof state or region changes
        if (subSnapshotList.isNotEmpty()) {
            val deadSubBinders = ArrayList<IBinder>()
            for (scb in subSnapshotList) {
                if (!scb.binder.isBinderAlive) {
                    deadSubBinders.add(scb.binder)
                    continue
                }
                val registry = scb.registryRef.get()
                val liveRecord = if (registry != null) findLiveRecord(registry, scb.binder) else null
                if (registry == null || liveRecord == null) {
                    deadSubBinders.add(scb.binder)
                    continue
                }
                scb.recordRef = WeakReference(liveRecord)

                val mode = cellModeForUid(scb.uid)
                val hdr = pair?.first
                val simKey = if (mode == CellMode.SPOOF &&
                    hdr != null &&
                    (hdr.rfFlags and ShmReader.KUROKO_RF_FLAG_SPOOF_SIM_COUNTRY) != 0
                ) {
                    "${hdr.carrierMcc}-${hdr.carrierMnc}-${hdr.mncDigits}-${hdr.countryIso}-${hdr.carrierAlpha}"
                } else {
                    "REAL"
                }
                val prev = scb.lastPushedSimKey
                if (prev == null) {
                    scb.lastPushedSimKey = simKey
                    if (simKey != "REAL" && !invokeZeroArgProxyMethod(scb.proxy, "onSubscriptionsChanged")) {
                        deadSubBinders.add(scb.binder)
                    }
                } else if (prev != simKey) {
                    scb.lastPushedSimKey = simKey
                    if (!invokeZeroArgProxyMethod(scb.proxy, "onSubscriptionsChanged")) {
                        deadSubBinders.add(scb.binder)
                    }
                }
            }
            if (deadSubBinders.isNotEmpty()) {
                synchronized(trackedSubCallbacks) {
                    for (b in deadSubBinders) {
                        trackedSubCallbacks.remove(b)
                    }
                }
            }
        }
    }

    private fun invokeZeroArgProxyMethod(proxy: Any, methodName: String): Boolean {
        val m = findMethodByName(proxy.javaClass, methodName, 0) ?: return true
        inOurTelPump.set(true)
        return try {
            m.invoke(proxy)
            true
        } catch (t: Throwable) {
            val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
            if (cause is RemoteException) {
                false
            } else {
                logOnce("tel_pump_inv0_$methodName", "Telephony pump $methodName error: $cause")
                true
            }
        } finally {
            inOurTelPump.set(false)
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
                        if (inPermProbe.get() == true) return
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

    private fun logOnce(key: String, msg: String) {
        if (loggedOnceKeys.add(key)) {
            XposedBridge.log("$TAG: $msg")
        }
    }
}
