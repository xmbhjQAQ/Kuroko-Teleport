package com.kuroko.teleport.xposed

import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Dual-process Telephony / Cell-Tower isolation hooks for Android 17 / HyperOS 4:
 *  1. com.android.phone (TeleService.apk):
 *     - PhoneInterfaceManager.onTransact: receives root daemon SHM memfd & ping
 *     - getAllCellInfo, getCellLocation, getLastKnownCellIdentity, getNeighboringCellInfo,
 *       requestCellInfoUpdate* -> returns empty / null for target UIDs with KUROKO_POLICY_HIDE_CELL
 *  2. system_server (services.jar):
 *     - TelephonyRegistry: blocks real cell info / cell location broadcasts & cached replays
 *       specifically for target UIDs with KUROKO_POLICY_HIDE_CELL while keeping non-target
 *       apps and system components untouched
 */
object TelephonyHook {
    private const val TAG = "Kuroko-Tel"
    private const val TELEPHONY_INTERFACE = "com.android.internal.telephony.ITelephony"

    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()
    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val methodCache = ConcurrentHashMap<String, Method>()

    // Fallback context flag for TelephonyRegistry if checkFineLocationAccess/checkCoarseLocationAccess are absent
    private val inCellNotify = ThreadLocal.withInitial { false }

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

        // 2. getAllCellInfo -> empty list for target UIDs
        hookReturnEmptyList(pimClass, "getAllCellInfo")

        // 3. getNeighboringCellInfo (legacy, if present) -> empty list for target UIDs
        hookReturnEmptyList(pimClass, "getNeighboringCellInfo")

        // 4. getCellLocation / getLastKnownCellIdentity -> null for target UIDs
        hookReturnNull(pimClass, "getCellLocation")
        hookReturnNull(pimClass, "getLastKnownCellIdentity")

        // 5. requestCellInfoUpdate / requestCellInfoUpdateWithWorkSource -> invoke callback with empty list
        var hookedReqCount = 0
        try {
            for (m in pimClass.declaredMethods) {
                if (!m.name.startsWith("requestCellInfoUpdate")) continue
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val uid = Binder.getCallingUid()
                            if (!ShmReader.hasPolicy(uid, ShmReader.KUROKO_POLICY_HIDE_CELL)) return

                            // Deliver empty list to ICellInfoCallback if present so caller does not hang
                            val args = param.args
                            if (args != null) {
                                for (arg in args) {
                                    if (arg == null) continue
                                    val onCellInfo = findMethodByName(arg.javaClass, "onCellInfo", 1)
                                    if (onCellInfo != null) {
                                        try {
                                            onCellInfo.invoke(arg, Collections.emptyList<Any>())
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

    private fun hookReturnEmptyList(cls: Class<*>, methodName: String) {
        try {
            val hooks = XposedBridge.hookAllMethods(cls, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.throwable != null) return
                        val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                        if (ShmReader.hasPolicy(uid, ShmReader.KUROKO_POLICY_HIDE_CELL)) {
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

    private fun hookReturnNull(cls: Class<*>, methodName: String) {
        try {
            val hooks = XposedBridge.hookAllMethods(cls, methodName, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.throwable != null) return
                        val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                        if (ShmReader.hasPolicy(uid, ShmReader.KUROKO_POLICY_HIDE_CELL)) {
                            param.result = null
                        }
                    } catch (t: Throwable) {
                        logOnce("tel_null_$methodName", "$methodName hook error: $t")
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

        // Primary mechanism: per-Record location access gates inside TelephonyRegistry.
        // Returning false for Records whose callerUid has KUROKO_POLICY_HIDE_CELL causes
        // TelephonyRegistry itself to skip onCellInfoChanged / onCellLocationChanged AND
        // strip CellIdentity from ServiceState & initial cached replay — strictly per-UID!
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
                            if (uid > 0 && ShmReader.hasPolicy(uid, ShmReader.KUROKO_POLICY_HIDE_CELL)) {
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

        // Track notifyCellInfo* / notifyCellLocation* entry & attach fallback LocationAccessPolicy hook
        // in case a future OEM build bypasses checkFineLocationAccess / checkCoarseLocationAccess.
        val notifyMethods = listOf(
            "notifyCellInfoForSubscriber",
            "notifyCellInfo",
            "notifyCellLocationForSubscriber",
            "notifyCellLocation"
        )
        var notifyHooks = 0
        for (name in notifyMethods) {
            try {
                val hooks = XposedBridge.hookAllMethods(regClass, name, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (ShmReader.hasAnyTargetWithPolicy(ShmReader.KUROKO_POLICY_HIDE_CELL)) {
                            inCellNotify.set(true)
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

        if (locationGateHooks == 0) {
            hookLocationAccessPolicyFallback(cl)
        }

        XposedBridge.log(
            "$TAG: Hooked TelephonyRegistry (locationGates=$locationGateHooks, notifyHooks=$notifyHooks)"
        )
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
                        if (ShmReader.hasPolicy(uid, ShmReader.KUROKO_POLICY_HIDE_CELL)) {
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
