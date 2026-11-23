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
 * System-server Wi-Fi & Connectivity anti-leak hooks for Android 17 / HyperOS 4 APEX services:
 *  - Dynamically captures APEX ClassLoaders for service-wifi.jar and service-connectivity.jar
 *  - Channel A: com.android.server.wifi.WifiServiceImpl
 *      * getScanResults(String, String) -> empty com.android.wifi.x.com.android.modules.utils.ParceledListSlice
 *      * getConnectionInfo(String, String) -> redacted WifiInfo (strips SSID/BSSID/MAC, preserves link state)
 *  - Channel B: android.net.connectivity.com.android.server.ConnectivityService
 *      * createWithSensitiveInfoSanitizedIfNecessaryWhenParceled(...) -> forces includeLocationInfo = false
 *        for target UIDs with KUROKO_POLICY_HIDE_WIFI, covering both synchronous queries and NetworkCallback!
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

    private val wifiHooked = AtomicBoolean(false)
    private val connectivityHooked = AtomicBoolean(false)
    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var emptySliceMethod: Method? = null

    @Volatile
    private var sliceListCtor: Constructor<*>? = null

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

        // 3. Capture APEX ClassLoaders via SystemServiceManager.startService(Class) / startServiceFromJar
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

        // 4. Fallback: catch PathClassLoader / BaseDexClassLoader creation for APEX jars
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
                        if (!ShmReader.hasPolicy(uid, ShmReader.KUROKO_POLICY_HIDE_WIFI)) return

                        val retType = (param.method as? Method)?.returnType
                        if (retType != null && List::class.java.isAssignableFrom(retType)) {
                            param.result = Collections.emptyList<Any>()
                            return
                        }
                        val emptySlice = createEmptyParceledListSlice(retType, wifiCl)
                        if (emptySlice != null) {
                            param.result = emptySlice
                        }
                    } catch (t: Throwable) {
                        logOnce("wifi_scan_err", "getScanResults hook error: $t")
                    }
                }
            })
        } catch (t: Throwable) {
            logOnce("hook_wifi_scan", "Failed hooking WifiServiceImpl.getScanResults: $t")
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
                        if (!ShmReader.hasPolicy(uid, ShmReader.KUROKO_POLICY_HIDE_WIFI)) return

                        val orig = param.result as? WifiInfo ?: return
                        param.result = redactWifiInfo(orig)
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

                            // Verified Android 17 signature:
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
                                    // 1st int is pid, 2nd int is uid
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
                            if (!includeLoc) return // Already sanitized

                            val uid = args[uidIdx] as? Int ?: return
                            if (ShmReader.hasPolicy(uid, ShmReader.KUROKO_POLICY_HIDE_WIFI)) {
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

    private fun createEmptyParceledListSlice(retType: Class<*>?, wifiCl: ClassLoader): Any? {
        // Ensure factory matches the exact runtime return class (jarjar-relocated ParceledListSlice)
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
            emptySliceMethod?.let { m ->
                val res = m.invoke(null)
                if (res != null && targetClass.isInstance(res)) return res
            }
            sliceListCtor?.let { ctor ->
                val res = ctor.newInstance(Collections.emptyList<Any>())
                if (res != null && targetClass.isInstance(res)) return res
            }
        } catch (t: Throwable) {
            logOnce("slice_create_err", "Failed creating empty ${targetClass.name}: $t")
        }
        return null
    }

    private fun redactWifiInfo(orig: WifiInfo): WifiInfo {
        try {
            val redactions = orig.applicableRedactions
            if (redactions != 0L) {
                return orig.makeCopy(redactions)
            }
            return orig.makeCopy(-1L)
        } catch (_: Throwable) {
        }
        return try {
            val ctor = WifiInfo::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            ctor.newInstance()
        } catch (_: Throwable) {
            orig
        }
    }

    private fun logOnce(key: String, msg: String) {
        if (loggedOnceKeys.add(key)) {
            XposedBridge.log("$TAG: $msg")
        }
    }
}
