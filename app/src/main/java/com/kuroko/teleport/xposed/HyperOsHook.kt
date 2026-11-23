package com.kuroko.teleport.xposed

import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.ConcurrentHashMap

/**
 * HyperOS 4 (miui-services.jar) compatibility hooks in system_server:
 *  - com.miui.server.greeze.power.GpsProxyManager:
 *      isGpsProxied(int) / checkIfIgnoreGps(int) -> false for target UIDs so HyperOS
 *      background freezer / GPS proxy does not suppress target running apps while stationary.
 *  - com.android.server.location.MiuiBlurLocationManagerImpl:
 *      isBlurLocationMode(int, String) -> false for target UIDs so Xiaomi fuzzy-location
 *      rewriting does not corrupt simulated coordinates.
 *
 * Gracefully no-ops on non-HyperOS builds if classes are absent.
 */
object HyperOsHook {
    private const val TAG = "Kuroko-HyperOS"

    private const val CLASS_GPS_PROXY_MANAGER = "com.miui.server.greeze.power.GpsProxyManager"
    private const val CLASS_MIUI_BLUR_LOCATION =
        "com.android.server.location.MiuiBlurLocationManagerImpl"

    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()

    fun initSystemServer(cl: ClassLoader) {
        hookGpsProxyManager(cl)
        hookMiuiBlurLocation(cl)
    }

    private fun hookGpsProxyManager(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClassIfExists(CLASS_GPS_PROXY_MANAGER, cl)
            if (cls == null) {
                XposedBridge.log("$TAG: $CLASS_GPS_PROXY_MANAGER not present (skipping)")
                return
            }

            val bypassHook = object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val uid = extractUidArgOrCaller(param.args)
                        if (ShmReader.hasPolicy(uid, ShmReader.KUROKO_POLICY_LOCATION)) {
                            param.result = false
                        }
                    } catch (t: Throwable) {
                        logOnce("gps_proxy_err", "GpsProxyManager hook error: $t")
                    }
                }
            }

            val h1 = XposedBridge.hookAllMethods(cls, "isGpsProxied", bypassHook)
            val h2 = XposedBridge.hookAllMethods(cls, "checkIfIgnoreGps", bypassHook)
            XposedBridge.log(
                "$TAG: Hooked GpsProxyManager (isGpsProxied=${h1.size}, checkIfIgnoreGps=${h2.size})"
            )
        } catch (t: Throwable) {
            logOnce("hook_gps_proxy", "Failed hooking GpsProxyManager: $t")
        }
    }

    private fun hookMiuiBlurLocation(cl: ClassLoader) {
        try {
            val cls = XposedHelpers.findClassIfExists(CLASS_MIUI_BLUR_LOCATION, cl)
            if (cls == null) {
                XposedBridge.log("$TAG: $CLASS_MIUI_BLUR_LOCATION not present (skipping)")
                return
            }

            val hooks = XposedBridge.hookAllMethods(
                cls,
                "isBlurLocationMode",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val uid = extractUidArgOrCaller(param.args)
                            if (ShmReader.hasPolicy(uid, ShmReader.KUROKO_POLICY_LOCATION)) {
                                param.result = false
                            }
                        } catch (t: Throwable) {
                            logOnce("miui_blur_err", "MiuiBlurLocationManagerImpl hook error: $t")
                        }
                    }
                }
            )
            XposedBridge.log("$TAG: Hooked MiuiBlurLocationManagerImpl.isBlurLocationMode (${hooks.size})")
        } catch (t: Throwable) {
            logOnce("hook_miui_blur", "Failed hooking MiuiBlurLocationManagerImpl: $t")
        }
    }

    private fun extractUidArgOrCaller(args: Array<Any?>?): Int {
        if (args != null) {
            for (arg in args) {
                if (arg is Int && arg > 0) {
                    return arg
                }
            }
        }
        return Binder.getCallingUid()
    }

    private fun logOnce(key: String, msg: String) {
        if (loggedOnceKeys.add(key)) {
            XposedBridge.log("$TAG: $msg")
        }
    }
}
