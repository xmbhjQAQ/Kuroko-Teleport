package com.kuroko.teleport.xposed

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.AttributionSource
import android.content.ComponentName
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.IInterface
import android.os.RemoteException
import android.os.SystemClock
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
 * Dual-process Bluetooth / BLE anti-leak & virtual iBeacon/BLE injection hooks for Android 12–17:
 *  1. system_server (service-bluetooth.jar APEX):
 *     - Dynamically captures service-bluetooth.jar APEX ClassLoader
 *     - Hooks BluetoothManagerService$BluetoothServiceConnection, AdapterBinder, and
 *       BluetoothManagerService to cache com.android.bluetooth's IBluetooth IBinder into
 *       ShmReader.cachedBluetoothBinder (queried by root kuroko_daemon via GET_BT_BINDER)
 *  2. com.android.bluetooth / com.google.android.bluetooth:
 *     - Hooks AdapterBinder.onTransact (android.bluetooth.IBluetooth) to receive 16KB SHM v2 memfd
 *     - Hooks Android 16/17 com.android.bluetooth.le_scan.* AND Android 12–15 gatt.* plus
 *       IScannerCallback$Stub$Proxy to drop real BLE results strictly for targeted appUids
 *       (HIDE_BLE / SPOOF_BLE) with zero impact on non-target apps, bonded audio/wearables, or GATT
 *     - Runs a 1Hz virtual BLE/iBeacon emitter thread (Kuroko-1Hz-BLE) building authentic
 *       android.bluetooth.le.ScanResult objects, evaluating caller ScanFilters, and dispatching
 *       to active SPOOF_BLE scanners
 *     - Suppresses Classic Bluetooth discovery for targeted appUids while keeping Settings pairing intact
 */
object BluetoothHook {
    private const val TAG = "Kuroko-BT"
    private const val BT_INTERFACE = "android.bluetooth.IBluetooth"
    private const val BLE_EMIT_INTERVAL_MS = 1000L
    private const val REAL_DISCOVERY_GRACE_MS = 15_000L

    private enum class BleMode { PASS, HIDE, SPOOF }

    private data class TrackedScanner(
        val scannerId: Int,
        val appUid: Int,
        val callback: Any?,
        val filters: List<ScanFilter>,
        val isBatch: Boolean
    )

    private val btManagerHooked = AtomicBoolean(false)
    private val bleEmitterStarted = AtomicBoolean(false)
    private val loggedOnceKeys = ConcurrentHashMap.newKeySet<String>()
    private val fieldCache = ConcurrentHashMap<String, Field>()
    private val methodCache = ConcurrentHashMap<String, Method>()

    // Per-scanner tracking in com.android.bluetooth
    private val callbackBinderToUid = Collections.synchronizedMap(WeakHashMap<IBinder, Int>())
    private val scannerIdToCallback = ConcurrentHashMap<Int, Any>()
    private val scannerIdToUid = ConcurrentHashMap<Int, Int>()
    private val activeScanners = ConcurrentHashMap<Int, TrackedScanner>()
    private val inOurBleEmitter = ThreadLocal.withInitial { false }

    @Volatile
    private var lastNonTargetDiscoveryElapsedMs: Long = 0L

    @Volatile
    private var scanRecordParseMethod: Method? = null

    private fun bleModeForUid(uid: Int): BleMode {
        if (uid <= 0) return BleMode.PASS
        val policy = ShmReader.getTargetPolicy(uid)
        return when {
            (policy and ShmReader.KUROKO_POLICY_SPOOF_BLE) != 0 -> BleMode.SPOOF
            (policy and ShmReader.KUROKO_POLICY_HIDE_BLE) != 0 -> BleMode.HIDE
            else -> BleMode.PASS
        }
    }

    // ==================== Half 1: system_server Bridge ====================

    fun initSystemServer(cl: ClassLoader) {
        tryHookBluetoothManager(cl)
        if (btManagerHooked.get()) return

        // Strategy 1: SystemServerClassLoaderFactory.getOrCreateClassLoader
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
                            if (btManagerHooked.get()) return
                            try {
                                val path = param.args.firstOrNull() as? String ?: ""
                                val apexCl = param.result as? ClassLoader ?: return
                                if (isBluetoothApexPath(path)) {
                                    tryHookBluetoothManager(apexCl)
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )
            }
        } catch (t: Throwable) {
            logOnce("bt_cl_factory", "SystemServerClassLoaderFactory hook warning: $t")
        }

        // Strategy 2: SystemServiceManager.startService(Class)
        try {
            val ssmClass = XposedHelpers.findClassIfExists(
                "com.android.server.SystemServiceManager",
                cl
            )
            if (ssmClass != null) {
                XposedBridge.hookAllMethods(ssmClass, "startService", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (btManagerHooked.get()) return
                        try {
                            val arg = param.args.firstOrNull()
                            if (arg is Class<*> && arg.name.contains("Bluetooth", ignoreCase = true)) {
                                arg.classLoader?.let { tryHookBluetoothManager(it) }
                            }
                        } catch (_: Throwable) {
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            logOnce("bt_ssm", "SystemServiceManager hook warning: $t")
        }

        // Strategy 3: PathClassLoader ctor for service-bluetooth.jar
        try {
            val pathClClass = XposedHelpers.findClassIfExists("dalvik.system.PathClassLoader", cl)
            if (pathClClass != null) {
                XposedBridge.hookAllConstructors(pathClClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (btManagerHooked.get()) return
                        try {
                            val dexPath = param.args.firstOrNull() as? String ?: return
                            val createdCl = param.thisObject as? ClassLoader ?: return
                            if (isBluetoothApexPath(dexPath)) {
                                tryHookBluetoothManager(createdCl)
                            }
                        } catch (_: Throwable) {
                        }
                    }
                })
            }
        } catch (t: Throwable) {
            logOnce("bt_pathcl", "PathClassLoader ctor hook warning: $t")
        }
    }

    private fun isBluetoothApexPath(path: String): Boolean {
        return path.contains("service-bluetooth") || path.contains("com.android.bt")
    }

    private fun tryHookBluetoothManager(btCl: ClassLoader) {
        if (btManagerHooked.get()) return
        val bmsClass = XposedHelpers.findClassIfExists(
            "com.android.server.bluetooth.BluetoothManagerService",
            btCl
        ) ?: return
        if (!btManagerHooked.compareAndSet(false, true)) return

        var hookedCount = 0

        // 1. BluetoothManagerService$BluetoothServiceConnection.onServiceConnected / onServiceDisconnected
        val connClass = XposedHelpers.findClassIfExists(
            "com.android.server.bluetooth.BluetoothManagerService\$BluetoothServiceConnection",
            btCl
        )
        if (connClass != null) {
            try {
                XposedBridge.hookAllMethods(connClass, "onServiceConnected", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val cn = param.args.getOrNull(0) as? ComponentName
                            val binder = param.args.getOrNull(1) as? IBinder ?: return
                            val desc = try {
                                binder.interfaceDescriptor
                            } catch (_: Throwable) {
                                null
                            }
                            if (desc == BT_INTERFACE ||
                                (cn != null && cn.className.contains("Adapter", ignoreCase = true))
                            ) {
                                updateCachedBluetoothBinder(binder, "BluetoothServiceConnection")
                            }
                        } catch (_: Throwable) {
                        }
                    }
                })
                XposedBridge.hookAllMethods(connClass, "onServiceDisconnected", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val cn = param.args.getOrNull(0) as? ComponentName
                            if (cn == null || cn.className.contains("Adapter", ignoreCase = true)) {
                                ShmReader.cachedBluetoothBinder = null
                            }
                        } catch (_: Throwable) {
                        }
                    }
                })
                hookedCount++
            } catch (t: Throwable) {
                logOnce("hook_bt_svc_conn", "BluetoothServiceConnection hook warning: $t")
            }
        }

        // 2. Android 15–17 wrapper com.android.server.bluetooth.AdapterBinder
        val adapterBinderClass = XposedHelpers.findClassIfExists(
            "com.android.server.bluetooth.AdapterBinder",
            btCl
        )
        if (adapterBinderClass != null) {
            try {
                XposedBridge.hookAllConstructors(adapterBinderClass, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val found = extractBluetoothIBinderFromArgs(param.args)
                                ?: extractBluetoothIBinder(param.thisObject)
                            if (found != null) {
                                updateCachedBluetoothBinder(found, "AdapterBinder.ctor")
                            }
                        } catch (_: Throwable) {
                        }
                    }
                })
                hookedCount++
            } catch (t: Throwable) {
                logOnce("hook_bt_adapter_binder", "AdapterBinder ctor hook warning: $t")
            }
        }

        // 3. BluetoothManagerService & BluetoothManagerServiceApi adapter registration / lookup
        val bmsApiClass = XposedHelpers.findClassIfExists(
            "com.android.server.bluetooth.BluetoothManagerServiceApi",
            btCl
        )
        for (cls in listOfNotNull(bmsClass, bmsApiClass)) {
            for (methodName in listOf("registerAdapter", "getAdapter", "bindToAdapter", "handleEnable")) {
                try {
                    val hooks = XposedBridge.hookAllMethods(cls, methodName, object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            try {
                                val found = extractBluetoothIBinder(param.result)
                                    ?: extractBluetoothIBinderFromArgs(param.args)
                                    ?: extractBluetoothIBinder(param.thisObject)
                                if (found != null) {
                                    updateCachedBluetoothBinder(found, "${cls.simpleName}.$methodName")
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    })
                    if (hooks.isNotEmpty()) hookedCount++
                } catch (_: Throwable) {
                }
            }
        }

        XposedBridge.log("$TAG: Hooked service-bluetooth bridge in system_server (points=$hookedCount)")
    }

    private fun updateCachedBluetoothBinder(binder: IBinder, source: String) {
        val prev = ShmReader.cachedBluetoothBinder
        ShmReader.cachedBluetoothBinder = binder
        if (prev !== binder) {
            XposedBridge.log("$TAG: Cached IBluetooth IBinder via $source")
        }
    }

    private fun extractBluetoothIBinderFromArgs(args: Array<Any?>?): IBinder? {
        if (args == null) return null
        for (arg in args) {
            val b = extractBluetoothIBinder(arg)
            if (b != null) return b
        }
        return null
    }

    private fun extractBluetoothIBinder(obj: Any?): IBinder? {
        if (obj == null) return null
        if (obj is IBinder) {
            return if (isIBluetoothBinder(obj)) obj else null
        }
        if (obj is IInterface) {
            val b = obj.asBinder()
            return if (b != null && isIBluetoothBinder(b)) b else null
        }
        // Check common wrapper fields (depth 1..2, e.g. AdapterBinder.adapterBinder or BMS.mAdapter)
        for (fieldName in listOf("adapterBinder", "mAdapterBinder", "mAdapter", "mBinder", "mBluetooth", "mService")) {
            val v = getFieldValue(obj, fieldName) ?: continue
            if (v is IBinder && isIBluetoothBinder(v)) return v
            if (v is IInterface) {
                val b = v.asBinder()
                if (b != null && isIBluetoothBinder(b)) return b
            }
            if (v !is String && v !is Number && v !is Boolean) {
                for (inner in listOf("adapterBinder", "mAdapter", "mBinder")) {
                    val iv = getFieldValue(v, inner) ?: continue
                    if (iv is IBinder && isIBluetoothBinder(iv)) return iv
                    if (iv is IInterface) {
                        val b = iv.asBinder()
                        if (b != null && isIBluetoothBinder(b)) return b
                    }
                }
            }
        }
        return null
    }

    private fun isIBluetoothBinder(binder: IBinder): Boolean {
        return try {
            binder.isBinderAlive && binder.interfaceDescriptor == BT_INTERFACE
        } catch (_: Throwable) {
            false
        }
    }

    // ==================== Half 2: com.android.bluetooth Process ====================

    fun initBluetoothProcess(cl: ClassLoader) {
        // 1. Hook IBluetooth onTransact to receive SHM v2 memfd & PING from root kuroko_daemon
        var txHooked = false
        for (binderClassName in listOf(
            "com.android.bluetooth.btservice.AdapterBinder",
            "com.android.bluetooth.btservice.AdapterServiceBinder",
            "com.android.bluetooth.btservice.AdapterService\$AdapterServiceBinder"
        )) {
            if (ShmReader.hookServiceOnTransact(binderClassName, cl, BT_INTERFACE)) {
                txHooked = true
                break
            }
        }
        if (!txHooked) {
            XposedBridge.log("$TAG: Warning — no IBluetooth AdapterBinder class found for onTransact")
        }

        // 2. Resolve ScanRecord.parseFromBytes reflection helper
        resolveScanRecordParser()

        // 3. Hook BLE scan controllers (Android 16/17 le_scan + Android 12–15 gatt)
        hookBleScanPipeline(cl)

        // 4. Hook IScannerCallback$Stub$Proxy for per-UID suppression of real BLE results
        hookScannerCallbackProxy(cl)

        // 5. Hook Classic Bluetooth discovery (AdapterService / AdapterBinder / RemoteDevices)
        hookClassicDiscovery(cl)

        // 6. Start 1Hz virtual BLE / iBeacon emitter daemon thread
        startBleEmitterThread()
    }

    private fun resolveScanRecordParser() {
        try {
            val m = ScanRecord::class.java.getDeclaredMethod("parseFromBytes", ByteArray::class.java)
            m.isAccessible = true
            scanRecordParseMethod = m
        } catch (t: Throwable) {
            logOnce("resolve_scan_record", "ScanRecord.parseFromBytes lookup failed: $t")
        }
    }

    private fun hookBleScanPipeline(cl: ClassLoader) {
        val controllerCandidates = listOf(
            // Android 16/17 refactored le_scan stack
            "com.android.bluetooth.le_scan.ScanController",
            "com.android.bluetooth.le_scan.ScanBinder",
            // Android 12–15 GATT scan stack
            "com.android.bluetooth.gatt.TransitionalScanHelper",
            "com.android.bluetooth.gatt.GattService",
            "com.android.bluetooth.gatt.GattService\$GattServiceBinder"
        )

        var hookedControllers = 0
        for (clsName in controllerCandidates) {
            val cls = XposedHelpers.findClassIfExists(clsName, cl) ?: continue
            hookScanControllerClass(cls)
            hookedControllers++
        }

        // Clean up activeScanners automatically when ScannerMap / ContextMap drops a dead client
        for (mapClsName in listOf(
            "com.android.bluetooth.le_scan.ScannerMap",
            "com.android.bluetooth.gatt.ContextMap",
            "com.android.bluetooth.gatt.GattService\$ScannerMap"
        )) {
            val mapCls = XposedHelpers.findClassIfExists(mapClsName, cl) ?: continue
            try {
                XposedBridge.hookAllMethods(mapCls, "remove", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val id = param.args.firstOrNull() as? Int ?: return
                        activeScanners.remove(id)
                        scannerIdToCallback.remove(id)
                        scannerIdToUid.remove(id)
                    }
                })
            } catch (_: Throwable) {
            }
        }

        XposedBridge.log("$TAG: Hooked BLE scan pipeline (controllerClasses=$hookedControllers)")
    }

    private fun hookScanControllerClass(cls: Class<*>) {
        // registerScanner: map callback IBinder -> appUid
        try {
            XposedBridge.hookAllMethods(cls, "registerScanner", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val uid = extractUidFromBtArgs(param.args)
                        if (uid <= 0) return
                        val cb = extractScannerCallbackFromArgs(param.args) ?: return
                        val binder = (cb as? IInterface)?.asBinder() ?: return
                        callbackBinderToUid[binder] = uid
                    } catch (_: Throwable) {
                    }
                }
            })
        } catch (_: Throwable) {
        }

        // startScan: register active scanner for virtual BLE injection & suppress PendingIntent real scans
        try {
            XposedBridge.hookAllMethods(cls, "startScan", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val args = param.args ?: return

                        // Check if this is a PendingIntent scan overload
                        val hasPendingIntent = args.any { it is PendingIntent }
                        if (hasPendingIntent) {
                            val uid = extractUidFromBtArgs(args)
                            if (bleModeForUid(uid) != BleMode.PASS) {
                                param.result = null
                            }
                            return
                        }

                        val scannerId = args.firstOrNull { it is Int } as? Int ?: return
                        val settings = args.firstOrNull { it is ScanSettings } as? ScanSettings
                        val filters = extractScanFilters(args)
                        val resolved = resolveScannerFromMap(param.thisObject, scannerId)
                        val appUid = resolved?.first
                            ?: scannerIdToUid[scannerId]
                            ?: extractUidFromBtArgs(args)
                        val callback = resolved?.second ?: scannerIdToCallback[scannerId]

                        if (appUid > 0) {
                            scannerIdToUid[scannerId] = appUid
                        }
                        if (callback != null) {
                            scannerIdToCallback[scannerId] = callback
                            val binder = (callback as? IInterface)?.asBinder()
                            if (binder != null && appUid > 0) {
                                callbackBinderToUid[binder] = appUid
                            }
                        }

                        val isBatch = (settings?.reportDelayMillis ?: 0L) > 0L
                        if (appUid > 0) {
                            activeScanners[scannerId] = TrackedScanner(
                                scannerId = scannerId,
                                appUid = appUid,
                                callback = callback,
                                filters = filters,
                                isBatch = isBatch
                            )
                        }
                    } catch (t: Throwable) {
                        logOnce("start_scan_err", "startScan hook error (${cls.simpleName}): $t")
                    }
                }
            })
        } catch (_: Throwable) {
        }

        // stopScan: deactivate scanner
        try {
            XposedBridge.hookAllMethods(cls, "stopScan", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val scannerId = param.args?.firstOrNull { it is Int } as? Int ?: return
                    activeScanners.remove(scannerId)
                }
            })
        } catch (_: Throwable) {
        }

        // unregisterScanner: remove scanner & callback maps
        try {
            XposedBridge.hookAllMethods(cls, "unregisterScanner", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val scannerId = param.args?.firstOrNull { it is Int } as? Int ?: return
                    activeScanners.remove(scannerId)
                    scannerIdToCallback.remove(scannerId)
                    scannerIdToUid.remove(scannerId)
                }
            })
        } catch (_: Throwable) {
        }
    }

    private fun hookScannerCallbackProxy(cl: ClassLoader) {
        val proxyCls = XposedHelpers.findClassIfExists(
            "android.bluetooth.le.IScannerCallback\$Stub\$Proxy",
            cl
        )
        if (proxyCls == null) {
            XposedBridge.log("$TAG: IScannerCallback\$Stub\$Proxy not found")
            return
        }

        // Capture scannerId <-> callback <-> uid at registration ack
        try {
            XposedBridge.hookAllMethods(proxyCls, "onScannerRegistered", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val status = param.args.getOrNull(0) as? Int ?: return
                        val scannerId = param.args.getOrNull(1) as? Int ?: return
                        if (status != 0 || scannerId < 0) return
                        val cb = param.thisObject ?: return
                        scannerIdToCallback[scannerId] = cb
                        val binder = (cb as? IInterface)?.asBinder() ?: return
                        val uid = callbackBinderToUid[binder]
                        if (uid != null && uid > 0) {
                            scannerIdToUid[scannerId] = uid
                        }
                    } catch (_: Throwable) {
                    }
                }
            })
        } catch (_: Throwable) {
        }

        // Drop real BLE scan results strictly for targeted appUids (HIDE_BLE or SPOOF_BLE)
        val dropRealScanHook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                try {
                    if (inOurBleEmitter.get() == true) return
                    val binder = (param.thisObject as? IInterface)?.asBinder() ?: return
                    val uid = callbackBinderToUid[binder] ?: return
                    if (bleModeForUid(uid) != BleMode.PASS) {
                        param.result = null
                    }
                } catch (t: Throwable) {
                    logOnce("drop_ble_err", "IScannerCallback drop hook error: $t")
                }
            }
        }

        try {
            val h1 = XposedBridge.hookAllMethods(proxyCls, "onScanResult", dropRealScanHook)
            val h2 = XposedBridge.hookAllMethods(proxyCls, "onBatchScanResults", dropRealScanHook)
            val h3 = XposedBridge.hookAllMethods(proxyCls, "onFoundOrLost", dropRealScanHook)
            XposedBridge.log(
                "$TAG: Hooked IScannerCallback\$Stub\$Proxy (onScanResult=${h1.size}, " +
                    "onBatchScanResults=${h2.size}, onFoundOrLost=${h3.size})"
            )
        } catch (t: Throwable) {
            logOnce("hook_scanner_cb", "Failed hooking IScannerCallback\$Stub\$Proxy: $t")
        }
    }

    private fun hookClassicDiscovery(cl: ClassLoader) {
        val adapterClasses = listOf(
            "com.android.bluetooth.btservice.AdapterBinder",
            "com.android.bluetooth.btservice.AdapterService",
            "com.android.bluetooth.btservice.AdapterServiceBinder",
            "com.android.bluetooth.btservice.AdapterService\$AdapterServiceBinder"
        )
        var hookedStart = 0
        for (clsName in adapterClasses) {
            val cls = XposedHelpers.findClassIfExists(clsName, cl) ?: continue
            try {
                val hooks = XposedBridge.hookAllMethods(cls, "startDiscovery", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val uid = extractUidFromBtArgs(param.args)
                            if (bleModeForUid(uid) != BleMode.PASS) {
                                param.result = false
                            } else {
                                lastNonTargetDiscoveryElapsedMs = SystemClock.elapsedRealtime()
                            }
                        } catch (_: Throwable) {
                        }
                    }
                })
                hookedStart += hooks.size
            } catch (_: Throwable) {
            }
        }

        val remoteDevCls = XposedHelpers.findClassIfExists(
            "com.android.bluetooth.btservice.RemoteDevices",
            cl
        )
        var hookedRemote = 0
        if (remoteDevCls != null) {
            try {
                val hooks = XposedBridge.hookAllMethods(
                    remoteDevCls,
                    "deviceFoundCallback",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            try {
                                val sinceReal = SystemClock.elapsedRealtime() - lastNonTargetDiscoveryElapsedMs
                                if (sinceReal in 0L..REAL_DISCOVERY_GRACE_MS) {
                                    // Legitimate non-target discovery (e.g. Bluetooth Settings pairing)
                                    return
                                }
                                if (ShmReader.hasAnyTargetWithPolicy(
                                        ShmReader.KUROKO_POLICY_HIDE_BLE or ShmReader.KUROKO_POLICY_SPOOF_BLE
                                    )
                                ) {
                                    param.result = null
                                }
                            } catch (_: Throwable) {
                            }
                        }
                    }
                )
                hookedRemote = hooks.size
            } catch (_: Throwable) {
            }
        }

        XposedBridge.log(
            "$TAG: Hooked Classic BT discovery suppression (startDiscovery=$hookedStart, remoteDevices=$hookedRemote)"
        )
    }

    // ==================== 1Hz Virtual BLE / iBeacon Emitter ====================

    private fun startBleEmitterThread() {
        if (!bleEmitterStarted.compareAndSet(false, true)) return
        val thread = Thread({
            XposedBridge.log("$TAG: 1Hz virtual BLE emitter thread started")
            while (true) {
                try {
                    runBleEmitTick()
                } catch (t: Throwable) {
                    logOnce("ble_emit_err", "1Hz BLE emitter tick error: $t")
                }
                try {
                    Thread.sleep(BLE_EMIT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                }
            }
        }, "Kuroko-1Hz-BLE")
        thread.isDaemon = true
        thread.start()
    }

    private fun runBleEmitTick() {
        if (activeScanners.isEmpty()) return
        if (!ShmReader.hasAnyTargetWithPolicy(ShmReader.KUROKO_POLICY_SPOOF_BLE)) return

        val bleDevices = ShmReader.readBleDevices()
        if (bleDevices.isEmpty()) return

        val results = buildVirtualBleScanResults(bleDevices)
        if (results.isEmpty()) return

        val token = Binder.clearCallingIdentity()
        inOurBleEmitter.set(true)
        try {
            val iterator = activeScanners.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val scanner = entry.value
                if (bleModeForUid(scanner.appUid) != BleMode.SPOOF) continue

                val cb = scanner.callback ?: scannerIdToCallback[scanner.scannerId] ?: continue
                val binder = (cb as? IInterface)?.asBinder()
                if (binder != null && !binder.isBinderAlive) {
                    iterator.remove()
                    scannerIdToCallback.remove(scanner.scannerId)
                    scannerIdToUid.remove(scanner.scannerId)
                    continue
                }

                val matched = if (scanner.filters.isEmpty()) {
                    results
                } else {
                    results.filter { res ->
                        scanner.filters.any { f -> safeFilterMatches(f, res) }
                    }
                }
                if (matched.isEmpty()) continue

                try {
                    if (scanner.isBatch) {
                        val m = findMethodByName(cb.javaClass, "onBatchScanResults", 1)
                        m?.invoke(cb, matched)
                    } else {
                        val m = findMethodByName(cb.javaClass, "onScanResult", 1)
                        if (m != null) {
                            for (res in matched) {
                                m.invoke(cb, res)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t
                    if (cause is RemoteException) {
                        iterator.remove()
                        scannerIdToCallback.remove(scanner.scannerId)
                        scannerIdToUid.remove(scanner.scannerId)
                    } else {
                        logOnce("ble_dispatch_err", "BLE callback dispatch error: $cause")
                    }
                }
            }
        } finally {
            inOurBleEmitter.set(false)
            Binder.restoreCallingIdentity(token)
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun buildVirtualBleScanResults(devices: List<ShmReader.BleDeviceEntry>): List<ScanResult> {
        val adapter = try {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        } catch (_: Throwable) {
            null
        }
        val tsNanos = SystemClock.elapsedRealtimeNanos()
        val out = ArrayList<ScanResult>(devices.size)

        for (dev in devices) {
            try {
                val mac = dev.macString()
                val btDev = createRemoteBluetoothDevice(adapter, mac, dev.addrType) ?: continue
                val record = parseScanRecord(dev.advBytes)
                val eventType = if (dev.isConnectable) 0x13 else 0x10
                val rssi = dev.rssiDbm.coerceIn(-110, -25)
                val txPower = dev.txPowerDbm.toInt().coerceIn(-127, 126)

                val sr = try {
                    ScanResult(
                        btDev,
                        eventType,
                        BluetoothDevice.PHY_LE_1M,
                        ScanResult.PHY_UNUSED,
                        ScanResult.SID_NOT_PRESENT,
                        txPower,
                        rssi,
                        ScanResult.PERIODIC_INTERVAL_NOT_PRESENT,
                        record,
                        tsNanos
                    )
                } catch (_: Throwable) {
                    @Suppress("DEPRECATION")
                    ScanResult(btDev, record, rssi, tsNanos)
                }
                out.add(sr)
            } catch (t: Throwable) {
                logOnce("build_ble_sr", "Failed building virtual BLE ScanResult: $t")
            }
        }
        return out
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun createRemoteBluetoothDevice(
        adapter: BluetoothAdapter?,
        mac: String,
        addrType: Int
    ): BluetoothDevice? {
        if (adapter != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    val normalizedType = if (addrType == 1) 1 else 0
                    return adapter.getRemoteLeDevice(mac, normalizedType)
                } catch (_: Throwable) {
                }
            }
            try {
                return adapter.getRemoteDevice(mac)
            } catch (_: Throwable) {
            }
        }
        return try {
            val ctor = BluetoothDevice::class.java.getDeclaredConstructor(String::class.java)
            ctor.isAccessible = true
            ctor.newInstance(mac)
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseScanRecord(advBytes: ByteArray): ScanRecord? {
        if (advBytes.isEmpty()) return null
        val m = scanRecordParseMethod ?: return null
        return try {
            m.invoke(null, advBytes) as? ScanRecord
        } catch (_: Throwable) {
            null
        }
    }

    private fun safeFilterMatches(filter: ScanFilter, result: ScanResult): Boolean {
        return try {
            filter.matches(result)
        } catch (_: Throwable) {
            false
        }
    }

    // ==================== Reflection & Extraction Helpers ====================

    private fun extractUidFromBtArgs(args: Array<Any?>?): Int {
        if (args != null) {
            for (arg in args) {
                if (arg is AttributionSource) {
                    val uid = arg.uid
                    if (uid > 0) return uid
                }
            }
        }
        return Binder.getCallingUid()
    }

    private fun extractScannerCallbackFromArgs(args: Array<Any?>?): Any? {
        if (args == null) return null
        for (arg in args) {
            if (arg == null) continue
            if (arg is IInterface) {
                val clsName = arg.javaClass.name
                if (clsName.contains("IScannerCallback") ||
                    findMethodByName(arg.javaClass, "onScanResult", 1) != null
                ) {
                    return arg
                }
            }
        }
        return null
    }

    private fun extractScanFilters(args: Array<Any?>): List<ScanFilter> {
        for (arg in args) {
            if (arg is List<*>) {
                val out = ArrayList<ScanFilter>(arg.size)
                for (item in arg) {
                    if (item is ScanFilter) out.add(item)
                }
                return out
            }
        }
        return emptyList()
    }

    private fun resolveScannerFromMap(controller: Any?, scannerId: Int): Pair<Int, Any?>? {
        if (controller == null) return null
        try {
            val map = getFieldValue(controller, "mScannerMap") ?: return null
            val getById = findMethodByName(map.javaClass, "getById", 1) ?: return null
            val app = getById.invoke(map, scannerId) ?: return null
            val uid = (getFieldValue(app, "mAppUid") as? Int)
                ?: (getFieldValue(app, "appUid") as? Int)
                ?: (getFieldValue(app, "uid") as? Int)
                ?: -1
            val cb = getFieldValue(app, "callback")
                ?: getFieldValue(app, "mCallback")
            if (uid > 0 || cb != null) {
                return Pair(uid, cb)
            }
        } catch (_: Throwable) {
        }
        return null
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
