package com.kuroko.teleport.xposed

import android.net.wifi.WifiInfo
import android.os.Binder
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * System-server Wi-Fi & Connectivity hooks for Android 12–17 APEX services:
 *  - Dynamically captures APEX ClassLoaders for service-wifi.jar and service-connectivity.jar
 *  - Channel A: com.android.server.wifi.WifiServiceImpl
 *      * getScanResults(String, String):
 *          - KUROKO_POLICY_SPOOF_WIFI (overrides HIDE_WIFI) -> APEX ParceledListSlice of virtual
 *            ScanResult entries (including API 33+ WifiSsid and 802.11d Country IE id=7)
 *          - KUROKO_POLICY_HIDE_WIFI -> empty APEX ParceledListSlice
 *      * getCountryCode(...):
 *          - KUROKO_POLICY_SPOOF_WIFI -> virtual rfHeader.wifiCountryIso (e.g. "US"/"JP"/"CN"),
 *            strictly per target UID without altering real radio regulatory domain
 *      * getConnectionInfo(String, String):
 *          - KUROKO_POLICY_SPOOF_WIFI + KUROKO_RF_FLAG_WIFI_SPOOF_CONN + connected virtual AP ->
 *            spoofed WifiInfo matching virtual AP (otherwise redacted)
 *          - KUROKO_POLICY_HIDE_WIFI -> redacted WifiInfo
 *  - Channel B: android.net.connectivity.com.android.server.ConnectivityService
 *      * createWithSensitiveInfoSanitizedIfNecessaryWhenParceled(...) -> forces includeLocationInfo = false
 *        for target UIDs with KUROKO_POLICY_HIDE_WIFI or KUROKO_POLICY_SPOOF_WIFI!
 */
object WifiConnectivityHook {
    private const val TAG = "Kuroko-WifiConn"

    private const val CLASS_WIFI_SERVICE_IMPL = "com.android.server.wifi.WifiServiceImpl"
    private const val CLASS_WIFI_PARCELED_LIST_SLICE =
        "com.android.wifi.x.com.android.modules.utils.ParceledListSlice"
    private const val CLASS_CONNECTIVITY_SERVICE_APEX =
        "android.net.connectivity.com.android.server.ConnectivityService"
    private const val CLASS_CONNECTIVITY_SERVICE_LEGACY =
        "com.android.server.ConnectivityService"

    private enum class WifiMode { PASS, HIDE, SPOOF }

    private val wifiHooked = AtomicBoolean(false)
    private val connectivityHooked = AtomicBoolean(false)
    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var emptySliceMethod: Method? = null

    @Volatile
    private var sliceListCtor: Constructor<*>? = null

    private fun wifiModeForUid(uid: Int): WifiMode {
        val policy = ShmReader.getTargetPolicy(uid)
        return when {
            (policy and ShmReader.KUROKO_POLICY_SPOOF_WIFI) != 0 -> WifiMode.SPOOF
            (policy and ShmReader.KUROKO_POLICY_HIDE_WIFI) != 0 -> WifiMode.HIDE
            else -> WifiMode.PASS
        }
    }

    fun initSystemServer(cl: ClassLoader) {
        // 1. Direct attempt in case APEX classes are already visible
        tryHookWifi(cl)
        tryHookConnectivity(cl)

        if (wifiHooked.get() && connectivityHooked.get()) {
            return
        }

        // 2. Capture APEX ClassLoaders via SystemServerClassLoaderFactory.getOrCreateClassLoader
        try {
            val factoryClass = XposedHelpers.findClassIfExists(
                "com.android.server.SystemServerClassLoaderFactory",
                cl
            )
            if (factoryClass != null) {
                XposedBridge.hookAllMethods(
                    factoryClass,
                    "getOrCreateClassLoader",
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (wifiHooked.get() && connectivityHooked.get()) return
                            try {
                                val path = param.args.firstOrNull() as? String ?: ""
                                val apexCl = param.result as? ClassLoader ?: return
                                onCandidateApexClassLoader(path, apexCl)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            logOnce("hook_cl_factory", "SystemServerClassLoaderFactory hook warning: $t")
        }

        // 3. Capture APEX ClassLoaders via SystemServiceManager.startService(Class)
        try {
            val ssmClass = XposedHelpers.findClassIfExists(
                "com.android.server.SystemServiceManager",
                cl
            )
            if (ssmClass != null) {
                XposedBridge.hookAllMethods(ssmClass, "startService", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (wifiHooked.get() && connectivityHooked.get()) return
                        try {
                            val arg = param.args.firstOrNull()
                            if (arg is Class<*>) {
                                val name = arg.name
                                val svcCl = arg.classLoader ?: return
                                if (!wifiHooked.get() && name.contains("Wifi", ignoreCase = true)) {
                                    tryHookWifi(svcCl)
                                }
                                if (!connectivityHooked.get() && name.contains("Connectivity", ignoreCase = true)) {
                                    tryHookConnectivity(svcCl)
                                }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            logOnce("hook_ssm", "SystemServiceManager hook warning: $t")
        }

        // 4. Fallback: catch PathClassLoader creation for APEX jars
        try {
            val pathClClass = XposedHelpers.findClassIfExists("dalvik.system.PathClassLoader", cl)
            if (pathClClass != null) {
                XposedBridge.hookAllConstructors(pathClClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (wifiHooked.get() && connectivityHooked.get()) return
                        try {
                            val dexPath = param.args.firstOrNull() as? String ?: return
                            val createdCl = param.thisObject as? ClassLoader ?: return
                            onCandidateApexClassLoader(dexPath, createdCl)
                        } catch (_: Throwable) {
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            logOnce("hook_pathcl", "PathClassLoader ctor hook warning: $t")
        }
    }

    private fun onCandidateApexClassLoader(path: String, apexCl: ClassLoader) {
        if (!wifiHooked.get() && (path.contains("service-wifi") || path.contains("com.android.wifi"))) {
            tryHookWifi(apexCl)
        }
        if (!connectivityHooked.get() &&
            (path.contains("service-connectivity") || path.contains("com.android.tethering"))
        ) {
            tryHookConnectivity(apexCl)
        }
    }

    private fun tryHookWifi(wifiCl: ClassLoader) {
        if (wifiHooked.get()) return
        val wifiClass = XposedHelpers.findClassIfExists(CLASS_WIFI_SERVICE_IMPL, wifiCl) ?: return
        if (!wifiHooked.compareAndSet(false, true)) return

        resolveParceledListSliceFactory(wifiCl)

        // Hook WifiServiceImpl.getScanResults(String, String) (+ any variant overloads)
        try {
            XposedBridge.hookAllMethods(wifiClass, "getScanResults", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.throwable != null) return
                        val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                        val mode = wifiModeForUid(uid)
                        if (mode == WifiMode.PASS) return

                        val retType = (param.method as? Method)?.returnType
                        val items: List<Any> = if (mode == WifiMode.SPOOF) {
                            val pair = ShmReader.readWifiApsWithHeader()
                            if (pair != null && pair.second.isNotEmpty()) {
                                VirtualWifiBuilder.buildScanResults(pair.first, pair.second)
                            } else {
                                Collections.emptyList()
                            }
                        } else {
                            Collections.emptyList()
                        }

                        if (retType != null && List::class.java.isAssignableFrom(retType)) {
                            param.result = items
                            return
                        }
                        val slice = createParceledListSlice(retType, wifiCl, items)
                        if (slice != null) {
                            param.result = slice
                        }
                    } catch (t: Throwable) {
                        logOnce("wifi_scan_err", "getScanResults hook error: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            logOnce("hook_wifi_scan", "Failed hooking WifiServiceImpl.getScanResults: $t")
        }

        // Hook WifiServiceImpl.getCountryCode(...) strictly per target UID (SPOOF_WIFI)
        try {
            val countryHooks = XposedBridge.hookAllMethods(wifiClass, "getCountryCode", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.throwable != null) return
                        val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                        if (wifiModeForUid(uid) != WifiMode.SPOOF) return
                        val header = ShmReader.readRfHeader() ?: return
                        if (header.wifiCountryIso.length == 2) {
                            param.result = header.wifiCountryIso
                        }
                    } catch (t: Throwable) {
                        logOnce("wifi_cc_err", "getCountryCode hook error: $t")
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked WifiServiceImpl.getCountryCode (${countryHooks.size})")
        } catch (t: Throwable) {
            logOnce("hook_wifi_cc", "Failed hooking WifiServiceImpl.getCountryCode: $t")
        }

        // Hook WifiServiceImpl.getConnectionInfo(String, String) (+ any variant overloads)
        try {
            XposedBridge.hookAllMethods(wifiClass, "getConnectionInfo", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    param.setObjectExtra("kuroko_uid", Binder.getCallingUid())
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        if (param.throwable != null) return
                        val uid = (param.getObjectExtra("kuroko_uid") as? Int) ?: Binder.getCallingUid()
                        val mode = wifiModeForUid(uid)
                        if (mode == WifiMode.PASS) return

                        val orig = param.result as? WifiInfo ?: return
                        if (mode == WifiMode.SPOOF) {
                            val pair = ShmReader.readWifiApsWithHeader()
                            val connAp = if (pair != null &&
                                (pair.first.rfFlags and ShmReader.KUROKO_RF_FLAG_WIFI_SPOOF_CONN) != 0
                            ) {
                                pair.second.firstOrNull { it.isConnectedAp }
                            } else {
                                null
                            }
                            param.result = if (connAp != null) {
                                VirtualWifiBuilder.buildSpoofedWifiInfo(orig, connAp)
                            } else {
                                VirtualWifiBuilder.redactWifiInfo(orig)
                            }
                        } else {
                            param.result = VirtualWifiBuilder.redactWifiInfo(orig)
                        }
                    } catch (t: Throwable) {
                        logOnce("wifi_conn_err", "getConnectionInfo hook error: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            logOnce("hook_wifi_conn", "Failed hooking WifiServiceImpl.getConnectionInfo: $t")
        }

        XposedBridge.log("$TAG: Hooked $CLASS_WIFI_SERVICE_IMPL (APEX ClassLoader captured)")
    }

    private fun tryHookConnectivity(connCl: ClassLoader) {
        if (connectivityHooked.get()) return
        val connClass = XposedHelpers.findClassIfExists(CLASS_CONNECTIVITY_SERVICE_APEX, connCl)
            ?: XposedHelpers.findClassIfExists(CLASS_CONNECTIVITY_SERVICE_LEGACY, connCl)
            ?: return
        if (!connectivityHooked.compareAndSet(false, true)) return

        try {
            val hooks = XposedBridge.hookAllMethods(
                connClass,
                "createWithSensitiveInfoSanitizedIfNecessaryWhenParceled",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val method = param.method as? Method ?: return
                            val paramTypes = method.parameterTypes
                            val args = param.args ?: return

                            // (NetworkCapabilities, boolean includeLocationInfo, int pid, int uid, String pkg, String attr)
                            var boolIdx = -1
                            var intCount = 0
                            var uidIdx = -1
                            for (i in paramTypes.indices) {
                                val pt = paramTypes[i]
                                if (pt == Boolean::class.javaPrimitiveType && boolIdx < 0) {
                                    boolIdx = i
                                } else if (pt == Int::class.javaPrimitiveType) {
                                    intCount++
                                    if (intCount == 2) {
                                        uidIdx = i
                                    } else if (uidIdx < 0) {
                                        uidIdx = i
                                    }
                                }
                            }
                            if (boolIdx < 0 || uidIdx < 0 || boolIdx >= args.size || uidIdx >= args.size) {
                                return
                            }
                            val includeLoc = args[boolIdx] as? Boolean ?: return
                            if (!includeLoc) return

                            val uid = args[uidIdx] as? Int ?: return
                            val policy = ShmReader.getTargetPolicy(uid)
                            if ((policy and (ShmReader.KUROKO_POLICY_HIDE_WIFI or ShmReader.KUROKO_POLICY_SPOOF_WIFI)) != 0) {
                                args[boolIdx] = false
                            }
                        } catch (t: Throwable) {
                            logOnce("conn_sanitize_err", "ConnectivityService sanitize hook error: $t")
                        }
                    }
                }
            )
            XposedBridge.log(
                "$TAG: Hooked ${connClass.name}.createWithSensitiveInfoSanitizedIfNecessaryWhenParceled " +
                    "(overloads=${hooks.size})"
            )
        } catch (t: Throwable) {
            logOnce("hook_conn_sanitize", "Failed hooking ConnectivityService: $t")
        }
    }

    private fun resolveParceledListSliceFactory(wifiCl: ClassLoader) {
        try {
            val sliceClass = XposedHelpers.findClassIfExists(CLASS_WIFI_PARCELED_LIST_SLICE, wifiCl)
                ?: return
            cacheSliceFactory(sliceClass)
        } catch (_: Throwable) {
        }
    }

    private fun cacheSliceFactory(sliceClass: Class<*>) {
        if (emptySliceMethod == null) {
            try {
                emptySliceMethod = sliceClass.getMethod("emptyList").apply { isAccessible = true }
            } catch (_: Throwable) {
            }
        }
        if (sliceListCtor == null) {
            try {
                sliceListCtor = sliceClass.getConstructor(List::class.java).apply { isAccessible = true }
            } catch (_: Throwable) {
            }
        }
    }

    private fun createParceledListSlice(
        retType: Class<*>?,
        wifiCl: ClassLoader,
        items: List<Any>
    ): Any? {
        val targetClass = when {
            retType != null && retType.simpleName.contains("ParceledListSlice") -> retType
            else -> XposedHelpers.findClassIfExists(CLASS_WIFI_PARCELED_LIST_SLICE, wifiCl)
        } ?: return null

        try {
            if (emptySliceMethod?.declaringClass != targetClass &&
                sliceListCtor?.declaringClass != targetClass
            ) {
                emptySliceMethod = null
                sliceListCtor = null
                cacheSliceFactory(targetClass)
            }
            if (items.isEmpty()) {
                emptySliceMethod?.let { m ->
                    val res = m.invoke(null)
                    if (res != null && targetClass.isInstance(res)) return res
                }
            }
            sliceListCtor?.let { ctor ->
                val res = ctor.newInstance(items)
                if (res != null && targetClass.isInstance(res)) return res
            }
        } catch (t: Throwable) {
            logOnce("slice_create_err", "Failed creating ${targetClass.name} (size=${items.size}): $t")
        }
        return null
    }

    private fun logOnce(key: String, msg: String) {
        if (loggedOnceKeys.add(key)) {
            XposedBridge.log("$TAG: $msg")
        }
    }
}
