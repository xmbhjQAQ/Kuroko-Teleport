package com.kuroko.teleport.xposed

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import com.kuroko.teleport.BuildConfig
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.io.FileInputStream
import java.lang.invoke.VarHandle
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free Seqlock reader for the 16KB KurokoShm v2 memfd pushed by root kuroko_daemon
 * via Binder onTransact to system_server ("location"), com.android.phone ("phone"),
 * and com.android.bluetooth (AdapterBinder).
 *
 * Exact byte offsets mirror common/include/kuroko_shm.h (little-endian, packed).
 */
object ShmReader {
    private const val TAG = "Kuroko-Shm"

    // Protocol & layout constants (common/include/kuroko_shm.h)
    const val KUROKO_SHM_MAGIC = 0x4F52554B
    const val KUROKO_SHM_VERSION = 2
    const val KUROKO_SHM_SIZE = 16384
    const val KUROKO_MAX_TARGETS = 32
    const val KUROKO_MAX_SVS = 32
    const val KUROKO_NMEA_BUF_SIZE = 2040
    const val KUROKO_MAX_CELLS = 8
    const val KUROKO_MAX_WIFI_APS = 16
    const val KUROKO_MAX_BLE_DEVS = 16

    // Binder transaction codes (within 0x00000001..0x00ffffff for libbinder_ndk)
    const val KUROKO_BINDER_TX_SET_SHM = 0x004B5243
    const val KUROKO_BINDER_TX_PING = 0x004B5250
    const val KUROKO_BINDER_TX_GET_BT_BINDER = 0x004B5242
    const val KUROKO_ACK_OK = 0x4F4B

    // Global flags
    const val KUROKO_FLAG_GNSS_SYNTH = 1 shl 0
    const val KUROKO_FLAG_SUPPRESS_RAW_MEAS = 1 shl 1
    const val KUROKO_FLAG_STEP_SPOOF = 1 shl 2
    const val KUROKO_FLAG_IMU_SPOOF = 1 shl 3

    // Per-target UID policy flags
    const val KUROKO_POLICY_LOCATION = 1 shl 0
    const val KUROKO_POLICY_STEPS = 1 shl 1
    const val KUROKO_POLICY_IMU = 1 shl 2
    const val KUROKO_POLICY_HIDE_WIFI = 1 shl 3
    const val KUROKO_POLICY_HIDE_CELL = 1 shl 4
    const val KUROKO_POLICY_SUPPRESS_MEAS = 1 shl 5
    const val KUROKO_POLICY_SPOOF_WIFI = 1 shl 6
    const val KUROKO_POLICY_SPOOF_CELL = 1 shl 7
    const val KUROKO_POLICY_HIDE_BLE = 1 shl 8
    const val KUROKO_POLICY_SPOOF_BLE = 1 shl 9

    // Global RF simulation flags (KurokoRfHeader.rf_flags)
    const val KUROKO_RF_FLAG_CELL_AUTO = 1 shl 0
    const val KUROKO_RF_FLAG_WIFI_AUTO = 1 shl 1
    const val KUROKO_RF_FLAG_WIFI_SPOOF_CONN = 1 shl 2
    const val KUROKO_RF_FLAG_BLE_AMBIENT = 1 shl 3
    const val KUROKO_RF_FLAG_SPOOF_SIM_COUNTRY = 1 shl 4
    const val KUROKO_RF_FLAG_CELL_PREFER_5G = 1 shl 5

    // Cell RAT types & flags
    const val KUROKO_CELL_RAT_LTE = 1
    const val KUROKO_CELL_RAT_NR = 2
    const val KUROKO_CELL_FLAG_USER_PINNED = 1 shl 0

    // Wi-Fi AP flags
    const val KUROKO_WIFI_FLAG_CONNECTED_AP = 1 shl 0
    const val KUROKO_WIFI_FLAG_IS_5GHZ = 1 shl 1
    const val KUROKO_WIFI_FLAG_USER_PINNED = 1 shl 2

    // BLE device types & flags
    const val KUROKO_BLE_TYPE_GENERIC = 0
    const val KUROKO_BLE_TYPE_IBEACON = 1
    const val KUROKO_BLE_FLAG_CONNECTABLE = 1 shl 0
    const val KUROKO_BLE_FLAG_USER_PINNED = 1 shl 1

    // Synthetic GNSS SV flags
    const val KUROKO_SV_HAS_EPHEMERIS = 1 shl 0
    const val KUROKO_SV_HAS_ALMANAC = 1 shl 1
    const val KUROKO_SV_USED_IN_FIX = 1 shl 2
    const val KUROKO_SV_HAS_CARRIER_FREQ = 1 shl 3
    const val KUROKO_SV_HAS_BASEBAND_CN0 = 1 shl 4

    // Heartbeat freshness window (5 seconds in nanoseconds)
    private const val HEARTBEAT_TIMEOUT_NS = 5_000_000_000L
    private const val MAX_SEQLOCK_TRIES = 8

    // Header offsets (0x000..0x03F)
    private const val OFF_MAGIC = 0x000
    private const val OFF_VERSION = 0x004
    private const val OFF_SEQ = 0x008
    private const val OFF_ACTIVE = 0x00C
    private const val OFF_TARGET_COUNT = 0x010
    private const val OFF_GLOBAL_FLAGS = 0x01C
    private const val OFF_DAEMON_HEARTBEAT_NS = 0x020
    private const val OFF_SNAPSHOT_BOOT_NS = 0x028
    private const val OFF_SNAPSHOT_UTC_MS = 0x030
    private const val OFF_ROUTE_EPOCH = 0x038

    // Location snapshot offsets (0x040..0x0BF)
    private const val OFF_LAT_WGS84 = 0x040
    private const val OFF_LON_WGS84 = 0x048
    private const val OFF_ALT_WGS84 = 0x050
    private const val OFF_MSL_ALT = 0x058
    private const val OFF_SPEED_MPS = 0x060
    private const val OFF_BEARING_DEG = 0x064
    private const val OFF_H_ACC_M = 0x068
    private const val OFF_V_ACC_M = 0x06C
    private const val OFF_SPEED_ACC_MPS = 0x070
    private const val OFF_BEARING_ACC_DEG = 0x074
    private const val OFF_SV_COUNT = 0x084

    // Target UID table (0x140..0x33F): 32 * 16 bytes
    private const val OFF_TARGETS = 0x140
    private const val TARGET_STRIDE = 16
    private const val TARGET_OFF_UID = 0x00
    private const val TARGET_OFF_POLICY = 0x04

    // Synthetic GNSS SVs (0x340..0x93F): 32 * 48 bytes
    private const val OFF_SVS = 0x340
    private const val SV_STRIDE = 48
    private const val SV_OFF_CONSTELLATION = 0x00
    private const val SV_OFF_SVID = 0x04
    private const val SV_OFF_CN0 = 0x08
    private const val SV_OFF_ELEVATION = 0x0C
    private const val SV_OFF_AZIMUTH = 0x10
    private const val SV_OFF_CARRIER_FREQ = 0x14
    private const val SV_OFF_BASEBAND_CN0 = 0x18
    private const val SV_OFF_FLAGS = 0x1C

    // Synthetic NMEA epoch (0x940..0x113F)
    private const val OFF_NMEA_SEQ = 0x940
    private const val OFF_NMEA_LEN = 0x944
    private const val OFF_NMEA_TEXT = 0x948

    // RF Header (0x1140..0x117F, 64B)
    private const val OFF_RF_HEADER = 0x1140
    private const val RF_OFF_EPOCH = 0x00
    private const val RF_OFF_CELL_COUNT = 0x04
    private const val RF_OFF_WIFI_COUNT = 0x08
    private const val RF_OFF_BLE_COUNT = 0x0C
    private const val RF_OFF_FLAGS = 0x10
    private const val RF_OFF_CARRIER_MCC = 0x14
    private const val RF_OFF_CARRIER_MNC = 0x16
    private const val RF_OFF_MNC_DIGITS = 0x18
    private const val RF_OFF_COUNTRY_ISO = 0x19
    private const val RF_OFF_WIFI_COUNTRY_ISO = 0x1D
    private const val RF_OFF_CARRIER_ALPHA = 0x21

    // Virtual Cells (0x1180..0x137F, 8 * 64B = 512B)
    private const val OFF_RF_CELLS = 0x1180
    private const val CELL_STRIDE = 64
    private const val CELL_OFF_RAT = 0x00
    private const val CELL_OFF_REGISTERED = 0x01
    private const val CELL_OFF_MNC_DIGITS = 0x02
    private const val CELL_OFF_FLAGS = 0x03
    private const val CELL_OFF_MCC = 0x04
    private const val CELL_OFF_MNC = 0x06
    private const val CELL_OFF_PCI = 0x08
    private const val CELL_OFF_BAND = 0x0A
    private const val CELL_OFF_TAC = 0x0C
    private const val CELL_OFF_ARFCN = 0x10
    private const val CELL_OFF_BW_KHZ = 0x14
    private const val CELL_OFF_CI = 0x18
    private const val CELL_OFF_RSRP = 0x20
    private const val CELL_OFF_RSRQ = 0x24
    private const val CELL_OFF_SINR = 0x28
    private const val CELL_OFF_TA = 0x2C
    private const val CELL_OFF_TS_NS = 0x30

    // Virtual Wi-Fi APs (0x1380..0x1B7F, 16 * 128B = 2048B)
    private const val OFF_RF_WIFIS = 0x1380
    private const val WIFI_STRIDE = 128
    private const val WIFI_OFF_BSSID = 0x00
    private const val WIFI_OFF_FLAGS = 0x06
    private const val WIFI_OFF_SSID_LEN = 0x07
    private const val WIFI_OFF_SSID = 0x08
    private const val WIFI_OFF_CAPABILITIES = 0x28
    private const val WIFI_OFF_FREQ_MHZ = 0x58
    private const val WIFI_OFF_RSSI_DBM = 0x5C
    private const val WIFI_OFF_CH_WIDTH = 0x60
    private const val WIFI_OFF_CENTER_FREQ0 = 0x64
    private const val WIFI_OFF_TS_US = 0x68
    private const val WIFI_OFF_COUNTRY_ISO = 0x70

    // Virtual BLE Devices (0x1B80..0x237F, 16 * 128B = 2048B)
    private const val OFF_RF_BLES = 0x1B80
    private const val BLE_STRIDE = 128
    private const val BLE_OFF_MAC = 0x00
    private const val BLE_OFF_ADDR_TYPE = 0x06
    private const val BLE_OFF_DEV_TYPE = 0x07
    private const val BLE_OFF_FLAGS = 0x08
    private const val BLE_OFF_NAME_LEN = 0x09
    private const val BLE_OFF_ADV_LEN = 0x0A
    private const val BLE_OFF_TX_POWER = 0x0B
    private const val BLE_OFF_RSSI = 0x0C
    private const val BLE_OFF_MAJOR = 0x10
    private const val BLE_OFF_MINOR = 0x12
    private const val BLE_OFF_UUID = 0x14
    private const val BLE_OFF_NAME = 0x24
    private const val BLE_OFF_ADV_BYTES = 0x3C

    data class RfHeader(
        val rfEpoch: Int,
        val cellCount: Int,
        val wifiCount: Int,
        val bleCount: Int,
        val rfFlags: Int,
        val carrierMcc: Int,
        val carrierMnc: Int,
        val mncDigits: Int,
        val countryIso: String,
        val wifiCountryIso: String,
        val carrierAlpha: String
    ) {
        fun mccString(): String = String.format(Locale.US, "%03d", carrierMcc.coerceIn(0, 999))
        fun mncString(): String = if (mncDigits >= 3) {
            String.format(Locale.US, "%03d", carrierMnc.coerceIn(0, 999))
        } else {
            String.format(Locale.US, "%02d", carrierMnc.coerceIn(0, 99))
        }
        fun operatorNumeric(): String = mccString() + mncString()
    }

    data class CellEntry(
        val rat: Int,
        val registered: Boolean,
        val mncDigits: Int,
        val flags: Int,
        val mcc: Int,
        val mnc: Int,
        val pci: Int,
        val band: Int,
        val tac: Int,
        val arfcn: Int,
        val bandwidthKhz: Int,
        val ci: Long,
        val rsrpDbm: Int,
        val rsrqDb: Int,
        val sinrDb: Int,
        val ta: Int,
        val timestampNs: Long
    ) {
        fun mccString(): String = String.format(Locale.US, "%03d", mcc.coerceIn(0, 999))
        fun mncString(): String = if (mncDigits >= 3) {
            String.format(Locale.US, "%03d", mnc.coerceIn(0, 999))
        } else {
            String.format(Locale.US, "%02d", mnc.coerceIn(0, 99))
        }
        fun operatorNumeric(): String = mccString() + mncString()
    }

    data class WifiApEntry(
        val bssid: ByteArray,
        val flags: Int,
        val ssid: String,
        val ssidBytes: ByteArray,
        val capabilities: String,
        val frequencyMhz: Int,
        val rssiDbm: Int,
        val channelWidth: Int,
        val centerFreq0Mhz: Int,
        val timestampUs: Long,
        val countryIso: String
    ) {
        val isConnectedAp: Boolean get() = (flags and KUROKO_WIFI_FLAG_CONNECTED_AP) != 0
        val is5Ghz: Boolean get() = (flags and KUROKO_WIFI_FLAG_IS_5GHZ) != 0 || frequencyMhz >= 5000

        fun bssidString(): String {
            if (bssid.size < 6) return "02:00:00:00:00:00"
            return String.format(
                Locale.US,
                "%02x:%02x:%02x:%02x:%02x:%02x",
                bssid[0].toInt() and 0xFF,
                bssid[1].toInt() and 0xFF,
                bssid[2].toInt() and 0xFF,
                bssid[3].toInt() and 0xFF,
                bssid[4].toInt() and 0xFF,
                bssid[5].toInt() and 0xFF
            )
        }
    }

    data class BleDeviceEntry(
        val mac: ByteArray,
        val addrType: Int,
        val deviceType: Int,
        val flags: Int,
        val txPowerDbm: Byte,
        val rssiDbm: Int,
        val major: Int,
        val minor: Int,
        val uuid: ByteArray,
        val name: String,
        val advBytes: ByteArray
    ) {
        val isConnectable: Boolean get() = (flags and KUROKO_BLE_FLAG_CONNECTABLE) != 0
        val isIBeacon: Boolean get() = deviceType == KUROKO_BLE_TYPE_IBEACON

        fun macString(): String {
            if (mac.size < 6) return "02:00:00:00:00:00"
            return String.format(
                Locale.US,
                "%02X:%02X:%02X:%02X:%02X:%02X",
                mac[0].toInt() and 0xFF,
                mac[1].toInt() and 0xFF,
                mac[2].toInt() and 0xFF,
                mac[3].toInt() and 0xFF,
                mac[4].toInt() and 0xFF,
                mac[5].toInt() and 0xFF
            )
        }
    }

    data class TargetEntry(
        val uid: Int,
        val policyFlags: Int
    )

    data class SvEntry(
        val constellation: Int,
        val svid: Int,
        val cn0DbHz: Float,
        val elevationDeg: Float,
        val azimuthDeg: Float,
        val carrierFreqHz: Float,
        val basebandCn0DbHz: Float,
        val flags: Int
    )

    data class LocationSnapshot(
        val active: Boolean,
        val globalFlags: Int,
        val daemonHeartbeatNs: Long,
        val snapshotBootNs: Long,
        val snapshotUtcMs: Long,
        val routeEpoch: Long,
        val latWgs84: Double,
        val lonWgs84: Double,
        val altWgs84: Double,
        val mslAlt: Double,
        val speedMps: Float,
        val bearingDeg: Float,
        val hAccuracyM: Float,
        val vAccuracyM: Float,
        val speedAccuracyMps: Float,
        val bearingAccuracyDeg: Float,
        val svCount: Int,
        val targets: List<TargetEntry>
    ) {
        fun policyForUid(uid: Int): Int {
            if (!active || uid <= 0) return 0
            for (i in targets.indices) {
                val t = targets[i]
                if (t.uid == uid) return t.policyFlags
            }
            return 0
        }

        fun hasAnyTargetWithPolicy(flag: Int): Boolean {
            if (!active) return false
            for (i in targets.indices) {
                if ((targets[i].policyFlags and flag) != 0) return true
            }
            return false
        }
    }

    data class FullSnapshot(
        val location: LocationSnapshot,
        val svs: List<SvEntry>,
        val nmeaSeq: Int,
        val nmeaText: String
    )

    @Volatile
    private var mappedBuffer: MappedByteBuffer? = null

    @Volatile
    var cachedBluetoothBinder: IBinder? = null

    @Volatile
    private var lastLoggedSensorHookProp: String = UNLOGGED_SENTINEL

    private val mapLock = Any()
    private val fallbackFence = AtomicInteger(0)

    private const val PROP_SENSOR_HOOK = "kuroko.sensor.hook"
    private const val PROP_SENSOR_DIAG = "kuroko.sensor.diag"
    private const val UNLOGGED_SENTINEL = "\u0000__unlogged__"

    @SuppressLint("NewApi")
    private fun acquireFence() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VarHandle.acquireFence()
        } else {
            fallbackFence.compareAndSet(0, 0)
        }
    }

    /**
     * Installs a safe onTransact hook on a Binder service class (walking up to its Stub if
     * the concrete subclass does not override onTransact) so root daemon can push memfd & ping.
     */
    fun hookServiceOnTransact(
        serviceClassName: String,
        cl: ClassLoader,
        expectedInterface: String
    ): Boolean {
        return try {
            val svcClass = XposedHelpers.findClassIfExists(serviceClassName, cl)
            if (svcClass == null) {
                XposedBridge.log("$TAG: Class not found for onTransact hook: $serviceClassName")
                return false
            }
            var targetMethod: java.lang.reflect.Method? = null
            var cur: Class<*>? = svcClass
            while (cur != null && cur != Any::class.java) {
                try {
                    targetMethod = cur.getDeclaredMethod(
                        "onTransact",
                        Int::class.java,
                        Parcel::class.java,
                        Parcel::class.java,
                        Int::class.java
                    )
                    break
                } catch (_: NoSuchMethodException) {
                    cur = cur.superclass
                }
            }
            if (targetMethod == null) {
                XposedBridge.log("$TAG: No onTransact method found in hierarchy of $serviceClassName")
                return false
            }
            XposedBridge.hookMethod(targetMethod, object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val code = param.args[0] as Int
                        if (code != KUROKO_BINDER_TX_SET_SHM &&
                            code != KUROKO_BINDER_TX_PING &&
                            code != KUROKO_BINDER_TX_GET_BT_BINDER
                        ) {
                            return
                        }
                        if (!svcClass.isInstance(param.thisObject)) {
                            return
                        }
                        val data = param.args[1] as? Parcel ?: return
                        val reply = param.args[2] as? Parcel
                        if (handleBinderTransact(code, data, reply, expectedInterface)) {
                            param.result = true
                        }
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: onTransact hook error ($serviceClassName): $t")
                    }
                }
            })
            XposedBridge.log("$TAG: Hooked ${targetMethod.declaringClass.name}.onTransact for $serviceClassName")
            true
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed to hook onTransact for $serviceClassName: $t")
            false
        }
    }

    /**
     * Handles root-only Kuroko Teleport Binder transactions (SET_SHM, PING, GET_BT_BINDER).
     * Supports standard libbinder_ndk interface-token-prefixed parcels via enforceInterface()
     * as well as raw parcels without interface token as fallback.
     */
    fun handleBinderTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        expectedInterface: String
    ): Boolean {
        if (code != KUROKO_BINDER_TX_SET_SHM &&
            code != KUROKO_BINDER_TX_PING &&
            code != KUROKO_BINDER_TX_GET_BT_BINDER
        ) {
            return false
        }
        // Strictly root-only (UID 0 = kuroko_daemon)
        if (Binder.getCallingUid() != 0) {
            return false
        }

        val startPos = data.dataPosition()
        if (code == KUROKO_BINDER_TX_GET_BT_BINDER) {
            if (expectedInterface != "android.location.ILocationManager") {
                return false
            }
            if (!verifyGetBtBinderParcel(data, startPos, expectedInterface)) {
                data.setDataPosition(startPos)
                return false
            }
            val bt = cachedBluetoothBinder?.takeIf {
                try {
                    it.isBinderAlive
                } catch (_: Throwable) {
                    false
                }
            }
            if (bt == null && cachedBluetoothBinder != null) {
                cachedBluetoothBinder = null
            }
            reply?.writeNoException()
            reply?.writeStrongBinder(bt)
            return true
        }

        val magic = readMagicWithInterfaceFallback(data, startPos, expectedInterface)
        if (magic != KUROKO_SHM_MAGIC) {
            data.setDataPosition(startPos)
            return false
        }

        if (code == KUROKO_BINDER_TX_PING) {
            val sensorProp = try {
                System.getProperty(PROP_SENSOR_HOOK)
            } catch (_: Throwable) {
                null
            }
            val sensorOk = if (sensorProp?.startsWith("ok") == true) 1 else 0
            val normalizedProp = sensorProp ?: "<unset>"
            if (expectedInterface == "android.location.ILocationManager" &&
                normalizedProp != lastLoggedSensorHookProp
            ) {
                lastLoggedSensorHookProp = normalizedProp
                XposedBridge.log("$TAG: $PROP_SENSOR_HOOK=$normalizedProp (sensorOk=$sensorOk)")
            }
            reply?.writeNoException()
            reply?.writeInt(if (isMappedAndFresh()) 1 else 0)
            reply?.writeInt(sensorOk)
            if (expectedInterface == "android.location.ILocationManager") {
                try {
                    val rawDiag = try {
                        System.getProperty(PROP_SENSOR_DIAG)
                    } catch (_: Throwable) {
                        null
                    }
                    val baseDiag = rawDiag?.takeIf { it.isNotEmpty() } ?: sensorProp ?: ""
                    val withXp = if (baseDiag.isNotEmpty()) {
                        "xp=${BuildConfig.VERSION_CODE},$baseDiag"
                    } else {
                        "xp=${BuildConfig.VERSION_CODE}"
                    }
                    var diagBytes = withXp.take(256).toByteArray(StandardCharsets.UTF_8)
                    if (diagBytes.size > 256) {
                        var end = 256
                        while (end > 0 && (diagBytes[end].toInt() and 0xC0) == 0x80) {
                            end--
                        }
                        diagBytes = diagBytes.copyOf(end)
                    }
                    reply?.writeByteArray(diagBytes)
                } catch (_: Throwable) {
                }
            }
            return true
        }

        // KUROKO_BINDER_TX_SET_SHM
        val size = try {
            data.readInt()
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: Failed reading SHM size: $t")
            reply?.writeNoException()
            reply?.writeInt(0)
            return true
        }

        if (size != KUROKO_SHM_SIZE) {
            XposedBridge.log("$TAG: Rejecting unexpected SHM size=$size (expected $KUROKO_SHM_SIZE)")
            reply?.writeNoException()
            reply?.writeInt(0)
            return true
        }

        val pfd = readPfdFlexible(data)
        var mappedOk = false
        if (pfd != null) {
            try {
                mappedOk = mapFromPfd(pfd, size)
            } finally {
                try {
                    pfd.close()
                } catch (_: Throwable) {
                }
            }
        } else {
            XposedBridge.log("$TAG: Failed to extract ParcelFileDescriptor from SET_SHM parcel")
        }

        reply?.writeNoException()
        reply?.writeInt(if (mappedOk) KUROKO_ACK_OK else 0)
        return true
    }

    private fun readMagicWithInterfaceFallback(
        data: Parcel,
        startPos: Int,
        expectedInterface: String
    ): Int {
        // Primary path: standard libbinder_ndk AIBinder_prepareTransaction writes interface token
        try {
            data.enforceInterface(expectedInterface)
            return data.readInt()
        } catch (_: Throwable) {
            // Fallback: raw parcel without interface token header
        }
        return try {
            data.setDataPosition(startPos)
            data.readInt()
        } catch (_: Throwable) {
            0
        }
    }

    /**
     * Reads ParcelFileDescriptor written by AParcel_writeParcelFileDescriptor.
     *
     * Primary format (AOSP libbinder_ndk): Parcel::writeParcelable(ParcelFileDescriptor) writes
     * int32(1) [non-null] + int32(0) [hasCommChannel] + BINDER_TYPE_FD, matching
     * data.readTypedObject(ParcelFileDescriptor.CREATOR).
     * Fallbacks support 1-int framing (readInt() != 0 -> readFileDescriptor()) and raw FD.
     */
    private fun readPfdFlexible(data: Parcel): ParcelFileDescriptor? {
        val fdPos = data.dataPosition()

        // 1. Primary: exact AOSP AParcel_writeParcelFileDescriptor wire format
        try {
            val typed = data.readTypedObject(ParcelFileDescriptor.CREATOR)
            if (typed != null && typed.fileDescriptor != null && typed.fileDescriptor.valid()) {
                return typed
            }
            try {
                typed?.close()
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
            // Try fallback framing
        }

        // 2. Fallback: single non-null int32 prefix + raw BINDER_TYPE_FD
        try {
            data.setDataPosition(fdPos)
            if (data.readInt() != 0) {
                val framed = data.readFileDescriptor()
                if (framed != null && framed.fileDescriptor != null && framed.fileDescriptor.valid()) {
                    return framed
                }
                try {
                    framed?.close()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            // Try direct FD
        }

        // 3. Fallback: direct raw BINDER_TYPE_FD without prefix ints
        try {
            data.setDataPosition(fdPos)
            val direct = data.readFileDescriptor()
            if (direct != null && direct.fileDescriptor != null && direct.fileDescriptor.valid()) {
                return direct
            }
            try {
                direct?.close()
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
            // Give up
        }

        return null
    }

    private fun mapFromPfd(pfd: ParcelFileDescriptor, size: Int): Boolean {
        synchronized(mapLock) {
            var fis: FileInputStream? = null
            var channel: FileChannel? = null
            return try {
                fis = FileInputStream(pfd.fileDescriptor)
                channel = fis.channel
                val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0L, size.toLong())
                buf.order(ByteOrder.LITTLE_ENDIAN)
                acquireFence()
                val hdrMagic = buf.getInt(OFF_MAGIC)
                val hdrVer = buf.getInt(OFF_VERSION)
                if (hdrMagic != KUROKO_SHM_MAGIC || hdrVer != KUROKO_SHM_VERSION) {
                    XposedBridge.log(
                        "$TAG: SHM header mismatch magic=0x${hdrMagic.toUInt().toString(16)} ver=$hdrVer"
                    )
                    false
                } else {
                    mappedBuffer = buf
                    XposedBridge.log("$TAG: Mapped ${size}B SHM memfd successfully (pid=${android.os.Process.myPid()})")
                    true
                }
            } catch (t: Throwable) {
                XposedBridge.log("$TAG: mmap failed: $t")
                false
            } finally {
                try {
                    channel?.close()
                } catch (_: Throwable) {
                }
                try {
                    fis?.close()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun isHeartbeatFresh(heartbeatNs: Long): Boolean {
        if (heartbeatNs <= 0L) return false
        val ageNs = SystemClock.elapsedRealtimeNanos() - heartbeatNs
        return ageNs <= HEARTBEAT_TIMEOUT_NS
    }

    /**
     * Returns true if SHM is mapped, valid, and daemon heartbeat is within 5s.
     * Returning false on stale heartbeat triggers kuroko_daemon to re-push its new memfd
     * automatically after a daemon restart.
     */
    fun isMappedAndFresh(): Boolean {
        val buf = mappedBuffer ?: return false
        for (attempt in 0 until MAX_SEQLOCK_TRIES) {
            val s1 = buf.getInt(OFF_SEQ)
            if ((s1 and 1) != 0) continue
            acquireFence()

            val magic = buf.getInt(OFF_MAGIC)
            val version = buf.getInt(OFF_VERSION)
            val heartbeatNs = buf.getLong(OFF_DAEMON_HEARTBEAT_NS)

            acquireFence()
            val s2 = buf.getInt(OFF_SEQ)
            if (s1 == s2) {
                return magic == KUROKO_SHM_MAGIC &&
                    version == KUROKO_SHM_VERSION &&
                    isHeartbeatFresh(heartbeatNs)
            }
        }
        return false
    }

    /**
     * Fast zero-allocation Seqlock lookup of the per-UID policy flags.
     * Returns 0 if SHM is unmapped, inactive, heartbeat stale (> 5s), or UID is not targeted.
     */
    fun getTargetPolicy(uid: Int): Int {
        if (uid <= 0) return 0
        val buf = mappedBuffer ?: return 0

        for (attempt in 0 until MAX_SEQLOCK_TRIES) {
            val s1 = buf.getInt(OFF_SEQ)
            if ((s1 and 1) != 0) continue
            acquireFence()

            val magic = buf.getInt(OFF_MAGIC)
            val version = buf.getInt(OFF_VERSION)
            val active = buf.getInt(OFF_ACTIVE)
            val heartbeatNs = buf.getLong(OFF_DAEMON_HEARTBEAT_NS)
            val targetCount = buf.getInt(OFF_TARGET_COUNT).coerceIn(0, KUROKO_MAX_TARGETS)

            var foundPolicy = 0
            if (active != 0 && targetCount > 0) {
                for (i in 0 until targetCount) {
                    val base = OFF_TARGETS + i * TARGET_STRIDE
                    if (buf.getInt(base + TARGET_OFF_UID) == uid) {
                        foundPolicy = buf.getInt(base + TARGET_OFF_POLICY)
                        break
                    }
                }
            }

            acquireFence()
            val s2 = buf.getInt(OFF_SEQ)
            if (s1 == s2) {
                if (magic != KUROKO_SHM_MAGIC || version != KUROKO_SHM_VERSION) return 0
                if (active == 0 || !isHeartbeatFresh(heartbeatNs)) return 0
                return foundPolicy
            }
        }
        return 0
    }

    fun hasPolicy(uid: Int, policyFlag: Int): Boolean {
        return (getTargetPolicy(uid) and policyFlag) != 0
    }

    fun hasAnyTargetWithPolicy(policyFlag: Int): Boolean {
        val buf = mappedBuffer ?: return false

        for (attempt in 0 until MAX_SEQLOCK_TRIES) {
            val s1 = buf.getInt(OFF_SEQ)
            if ((s1 and 1) != 0) continue
            acquireFence()

            val magic = buf.getInt(OFF_MAGIC)
            val version = buf.getInt(OFF_VERSION)
            val active = buf.getInt(OFF_ACTIVE)
            val heartbeatNs = buf.getLong(OFF_DAEMON_HEARTBEAT_NS)
            val targetCount = buf.getInt(OFF_TARGET_COUNT).coerceIn(0, KUROKO_MAX_TARGETS)

            var matched = false
            if (active != 0 && targetCount > 0) {
                for (i in 0 until targetCount) {
                    val base = OFF_TARGETS + i * TARGET_STRIDE
                    val tUid = buf.getInt(base + TARGET_OFF_UID)
                    val tPolicy = buf.getInt(base + TARGET_OFF_POLICY)
                    if (tUid > 0 && (tPolicy and policyFlag) != 0) {
                        matched = true
                        break
                    }
                }
            }

            acquireFence()
            val s2 = buf.getInt(OFF_SEQ)
            if (s1 == s2) {
                if (magic != KUROKO_SHM_MAGIC || version != KUROKO_SHM_VERSION) return false
                if (active == 0 || !isHeartbeatFresh(heartbeatNs)) return false
                return matched
            }
        }
        return false
    }

    /**
     * Atomically reads header, location snapshot, and target UID table via Seqlock.
     * Returns null if unmapped, inactive, corrupt, or daemon heartbeat > 5s stale.
     */
    fun readLocationSnapshot(): LocationSnapshot? {
        val buf = mappedBuffer ?: return null

        for (attempt in 0 until MAX_SEQLOCK_TRIES) {
            val s1 = buf.getInt(OFF_SEQ)
            if ((s1 and 1) != 0) continue
            acquireFence()

            val magic = buf.getInt(OFF_MAGIC)
            val version = buf.getInt(OFF_VERSION)
            val active = buf.getInt(OFF_ACTIVE)
            val targetCount = buf.getInt(OFF_TARGET_COUNT).coerceIn(0, KUROKO_MAX_TARGETS)
            val globalFlags = buf.getInt(OFF_GLOBAL_FLAGS)
            val heartbeatNs = buf.getLong(OFF_DAEMON_HEARTBEAT_NS)
            val snapshotBootNs = buf.getLong(OFF_SNAPSHOT_BOOT_NS)
            val snapshotUtcMs = buf.getLong(OFF_SNAPSHOT_UTC_MS)
            val routeEpoch = buf.getLong(OFF_ROUTE_EPOCH)

            val lat = buf.getDouble(OFF_LAT_WGS84)
            val lon = buf.getDouble(OFF_LON_WGS84)
            val alt = buf.getDouble(OFF_ALT_WGS84)
            val mslAlt = buf.getDouble(OFF_MSL_ALT)
            val speed = buf.getFloat(OFF_SPEED_MPS)
            val bearing = buf.getFloat(OFF_BEARING_DEG)
            val hAcc = buf.getFloat(OFF_H_ACC_M)
            val vAcc = buf.getFloat(OFF_V_ACC_M)
            val speedAcc = buf.getFloat(OFF_SPEED_ACC_MPS)
            val bearingAcc = buf.getFloat(OFF_BEARING_ACC_DEG)
            val svCount = buf.getInt(OFF_SV_COUNT).coerceIn(0, KUROKO_MAX_SVS)

            val targets = ArrayList<TargetEntry>(targetCount)
            for (i in 0 until targetCount) {
                val base = OFF_TARGETS + i * TARGET_STRIDE
                val uid = buf.getInt(base + TARGET_OFF_UID)
                val policy = buf.getInt(base + TARGET_OFF_POLICY)
                if (uid > 0) {
                    targets.add(TargetEntry(uid, policy))
                }
            }

            acquireFence()
            val s2 = buf.getInt(OFF_SEQ)
            if (s1 == s2) {
                if (magic != KUROKO_SHM_MAGIC || version != KUROKO_SHM_VERSION) return null
                if (active == 0 || !isHeartbeatFresh(heartbeatNs)) return null
                if (!lat.isFinite() || !lon.isFinite()) return null
                return LocationSnapshot(
                    active = true,
                    globalFlags = globalFlags,
                    daemonHeartbeatNs = heartbeatNs,
                    snapshotBootNs = snapshotBootNs,
                    snapshotUtcMs = snapshotUtcMs,
                    routeEpoch = routeEpoch,
                    latWgs84 = lat,
                    lonWgs84 = lon,
                    altWgs84 = if (alt.isFinite()) alt else 0.0,
                    mslAlt = if (mslAlt.isFinite()) mslAlt else 0.0,
                    speedMps = if (speed.isFinite()) speed else 0f,
                    bearingDeg = if (bearing.isFinite()) bearing else 0f,
                    hAccuracyM = if (hAcc.isFinite()) hAcc else 5f,
                    vAccuracyM = if (vAcc.isFinite()) vAcc else 6f,
                    speedAccuracyMps = if (speedAcc.isFinite()) speedAcc else 0.3f,
                    bearingAccuracyDeg = if (bearingAcc.isFinite()) bearingAcc else 10f,
                    svCount = svCount,
                    targets = targets
                )
            }
        }
        return null
    }

    /**
     * Atomically reads header, location, targets, synthetic GNSS SVs, and synthetic NMEA text
     * via Seqlock for the 1Hz Active Pump.
     */
    fun readFullSnapshot(): FullSnapshot? {
        val buf = mappedBuffer ?: return null

        for (attempt in 0 until MAX_SEQLOCK_TRIES) {
            val s1 = buf.getInt(OFF_SEQ)
            if ((s1 and 1) != 0) continue
            acquireFence()

            val magic = buf.getInt(OFF_MAGIC)
            val version = buf.getInt(OFF_VERSION)
            val active = buf.getInt(OFF_ACTIVE)
            val targetCount = buf.getInt(OFF_TARGET_COUNT).coerceIn(0, KUROKO_MAX_TARGETS)
            val globalFlags = buf.getInt(OFF_GLOBAL_FLAGS)
            val heartbeatNs = buf.getLong(OFF_DAEMON_HEARTBEAT_NS)
            val snapshotBootNs = buf.getLong(OFF_SNAPSHOT_BOOT_NS)
            val snapshotUtcMs = buf.getLong(OFF_SNAPSHOT_UTC_MS)
            val routeEpoch = buf.getLong(OFF_ROUTE_EPOCH)

            val lat = buf.getDouble(OFF_LAT_WGS84)
            val lon = buf.getDouble(OFF_LON_WGS84)
            val alt = buf.getDouble(OFF_ALT_WGS84)
            val mslAlt = buf.getDouble(OFF_MSL_ALT)
            val speed = buf.getFloat(OFF_SPEED_MPS)
            val bearing = buf.getFloat(OFF_BEARING_DEG)
            val hAcc = buf.getFloat(OFF_H_ACC_M)
            val vAcc = buf.getFloat(OFF_V_ACC_M)
            val speedAcc = buf.getFloat(OFF_SPEED_ACC_MPS)
            val bearingAcc = buf.getFloat(OFF_BEARING_ACC_DEG)
            val svCount = buf.getInt(OFF_SV_COUNT).coerceIn(0, KUROKO_MAX_SVS)

            val targets = ArrayList<TargetEntry>(targetCount)
            for (i in 0 until targetCount) {
                val base = OFF_TARGETS + i * TARGET_STRIDE
                val uid = buf.getInt(base + TARGET_OFF_UID)
                val policy = buf.getInt(base + TARGET_OFF_POLICY)
                if (uid > 0) {
                    targets.add(TargetEntry(uid, policy))
                }
            }

            val svs = ArrayList<SvEntry>(svCount)
            for (i in 0 until svCount) {
                val base = OFF_SVS + i * SV_STRIDE
                svs.add(
                    SvEntry(
                        constellation = buf.getInt(base + SV_OFF_CONSTELLATION),
                        svid = buf.getInt(base + SV_OFF_SVID),
                        cn0DbHz = buf.getFloat(base + SV_OFF_CN0),
                        elevationDeg = buf.getFloat(base + SV_OFF_ELEVATION),
                        azimuthDeg = buf.getFloat(base + SV_OFF_AZIMUTH),
                        carrierFreqHz = buf.getFloat(base + SV_OFF_CARRIER_FREQ),
                        basebandCn0DbHz = buf.getFloat(base + SV_OFF_BASEBAND_CN0),
                        flags = buf.getInt(base + SV_OFF_FLAGS)
                    )
                )
            }

            val nmeaSeq = buf.getInt(OFF_NMEA_SEQ)
            val nmeaLen = buf.getInt(OFF_NMEA_LEN).coerceIn(0, KUROKO_NMEA_BUF_SIZE)
            val nmeaBytes = if (nmeaLen > 0) {
                val arr = ByteArray(nmeaLen)
                val dup = buf.duplicate()
                dup.position(OFF_NMEA_TEXT)
                dup.get(arr, 0, nmeaLen)
                arr
            } else {
                null
            }

            acquireFence()
            val s2 = buf.getInt(OFF_SEQ)
            if (s1 == s2) {
                if (magic != KUROKO_SHM_MAGIC || version != KUROKO_SHM_VERSION) return null
                if (active == 0 || !isHeartbeatFresh(heartbeatNs)) return null
                if (!lat.isFinite() || !lon.isFinite()) return null

                val locSnap = LocationSnapshot(
                    active = true,
                    globalFlags = globalFlags,
                    daemonHeartbeatNs = heartbeatNs,
                    snapshotBootNs = snapshotBootNs,
                    snapshotUtcMs = snapshotUtcMs,
                    routeEpoch = routeEpoch,
                    latWgs84 = lat,
                    lonWgs84 = lon,
                    altWgs84 = if (alt.isFinite()) alt else 0.0,
                    mslAlt = if (mslAlt.isFinite()) mslAlt else 0.0,
                    speedMps = if (speed.isFinite()) speed else 0f,
                    bearingDeg = if (bearing.isFinite()) bearing else 0f,
                    hAccuracyM = if (hAcc.isFinite()) hAcc else 5f,
                    vAccuracyM = if (vAcc.isFinite()) vAcc else 6f,
                    speedAccuracyMps = if (speedAcc.isFinite()) speedAcc else 0.3f,
                    bearingAccuracyDeg = if (bearingAcc.isFinite()) bearingAcc else 10f,
                    svCount = svCount,
                    targets = targets
                )
                val nmeaText = if (nmeaBytes != null) {
                    String(nmeaBytes, StandardCharsets.US_ASCII)
                } else {
                    ""
                }
                return FullSnapshot(
                    location = locSnap,
                    svs = svs,
                    nmeaSeq = nmeaSeq,
                    nmeaText = nmeaText
                )
            }
        }
        return null
    }

    private fun verifyGetBtBinderParcel(
        data: Parcel,
        startPos: Int,
        expectedInterface: String
    ): Boolean {
        try {
            data.enforceInterface(expectedInterface)
            if (data.dataAvail() >= 4) {
                return data.readInt() == KUROKO_SHM_MAGIC
            }
            return true
        } catch (_: Throwable) {
        }
        return try {
            data.setDataPosition(startPos)
            data.dataAvail() >= 4 && data.readInt() == KUROKO_SHM_MAGIC
        } catch (_: Throwable) {
            false
        }
    }

    // ==================== SHM v2 RF Snapshot Readers ====================

    private fun readRfHeaderLocked(buf: MappedByteBuffer): RfHeader {
        val base = OFF_RF_HEADER
        return RfHeader(
            rfEpoch = buf.getInt(base + RF_OFF_EPOCH),
            cellCount = buf.getInt(base + RF_OFF_CELL_COUNT).coerceIn(0, KUROKO_MAX_CELLS),
            wifiCount = buf.getInt(base + RF_OFF_WIFI_COUNT).coerceIn(0, KUROKO_MAX_WIFI_APS),
            bleCount = buf.getInt(base + RF_OFF_BLE_COUNT).coerceIn(0, KUROKO_MAX_BLE_DEVS),
            rfFlags = buf.getInt(base + RF_OFF_FLAGS),
            carrierMcc = buf.getShort(base + RF_OFF_CARRIER_MCC).toInt() and 0xFFFF,
            carrierMnc = buf.getShort(base + RF_OFF_CARRIER_MNC).toInt() and 0xFFFF,
            mncDigits = (buf.get(base + RF_OFF_MNC_DIGITS).toInt() and 0xFF).coerceIn(2, 3),
            countryIso = readCString(buf, base + RF_OFF_COUNTRY_ISO, 4).lowercase(Locale.US),
            wifiCountryIso = readCString(buf, base + RF_OFF_WIFI_COUNTRY_ISO, 4).uppercase(Locale.US),
            carrierAlpha = readCString(buf, base + RF_OFF_CARRIER_ALPHA, 16)
        )
    }

    fun readRfHeader(): RfHeader? {
        val buf = mappedBuffer ?: return null
        for (attempt in 0 until MAX_SEQLOCK_TRIES) {
            val s1 = buf.getInt(OFF_SEQ)
            if ((s1 and 1) != 0) continue
            acquireFence()

            val magic = buf.getInt(OFF_MAGIC)
            val version = buf.getInt(OFF_VERSION)
            val active = buf.getInt(OFF_ACTIVE)
            val heartbeatNs = buf.getLong(OFF_DAEMON_HEARTBEAT_NS)
            val rfHeader = if (active != 0) readRfHeaderLocked(buf) else null

            acquireFence()
            val s2 = buf.getInt(OFF_SEQ)
            if (s1 == s2) {
                if (magic != KUROKO_SHM_MAGIC || version != KUROKO_SHM_VERSION) return null
                if (active == 0 || !isHeartbeatFresh(heartbeatNs)) return null
                return rfHeader
            }
        }
        return null
    }

    fun readCells(): List<CellEntry> = readCellsWithHeader()?.second ?: emptyList()

    fun readCellsWithHeader(): Pair<RfHeader, List<CellEntry>>? {
        val buf = mappedBuffer ?: return null
        for (attempt in 0 until MAX_SEQLOCK_TRIES) {
            val s1 = buf.getInt(OFF_SEQ)
            if ((s1 and 1) != 0) continue
            acquireFence()

            val magic = buf.getInt(OFF_MAGIC)
            val version = buf.getInt(OFF_VERSION)
            val active = buf.getInt(OFF_ACTIVE)
            val heartbeatNs = buf.getLong(OFF_DAEMON_HEARTBEAT_NS)

            var header: RfHeader? = null
            var cells: ArrayList<CellEntry>? = null
            if (active != 0) {
                val h = readRfHeaderLocked(buf)
                header = h
                val list = ArrayList<CellEntry>(h.cellCount)
                for (i in 0 until h.cellCount) {
                    val base = OFF_RF_CELLS + i * CELL_STRIDE
                    list.add(
                        CellEntry(
                            rat = buf.get(base + CELL_OFF_RAT).toInt() and 0xFF,
                            registered = (buf.get(base + CELL_OFF_REGISTERED).toInt() and 0xFF) != 0,
                            mncDigits = (buf.get(base + CELL_OFF_MNC_DIGITS).toInt() and 0xFF).coerceIn(2, 3),
                            flags = buf.get(base + CELL_OFF_FLAGS).toInt() and 0xFF,
                            mcc = buf.getShort(base + CELL_OFF_MCC).toInt() and 0xFFFF,
                            mnc = buf.getShort(base + CELL_OFF_MNC).toInt() and 0xFFFF,
                            pci = buf.getShort(base + CELL_OFF_PCI).toInt() and 0xFFFF,
                            band = buf.getShort(base + CELL_OFF_BAND).toInt() and 0xFFFF,
                            tac = buf.getInt(base + CELL_OFF_TAC),
                            arfcn = buf.getInt(base + CELL_OFF_ARFCN),
                            bandwidthKhz = buf.getInt(base + CELL_OFF_BW_KHZ),
                            ci = buf.getLong(base + CELL_OFF_CI),
                            rsrpDbm = buf.getInt(base + CELL_OFF_RSRP),
                            rsrqDb = buf.getInt(base + CELL_OFF_RSRQ),
                            sinrDb = buf.getInt(base + CELL_OFF_SINR),
                            ta = buf.getInt(base + CELL_OFF_TA),
                            timestampNs = buf.getLong(base + CELL_OFF_TS_NS)
                        )
                    )
                }
                cells = list
            }

            acquireFence()
            val s2 = buf.getInt(OFF_SEQ)
            if (s1 == s2) {
                if (magic != KUROKO_SHM_MAGIC || version != KUROKO_SHM_VERSION) return null
                if (active == 0 || !isHeartbeatFresh(heartbeatNs)) return null
                if (header != null && cells != null) return Pair(header, cells)
                return null
            }
        }
        return null
    }

    fun readWifiAps(): List<WifiApEntry> = readWifiApsWithHeader()?.second ?: emptyList()

    fun readWifiApsWithHeader(): Pair<RfHeader, List<WifiApEntry>>? {
        val buf = mappedBuffer ?: return null
        for (attempt in 0 until MAX_SEQLOCK_TRIES) {
            val s1 = buf.getInt(OFF_SEQ)
            if ((s1 and 1) != 0) continue
            acquireFence()

            val magic = buf.getInt(OFF_MAGIC)
            val version = buf.getInt(OFF_VERSION)
            val active = buf.getInt(OFF_ACTIVE)
            val heartbeatNs = buf.getLong(OFF_DAEMON_HEARTBEAT_NS)

            var header: RfHeader? = null
            var aps: ArrayList<WifiApEntry>? = null
            if (active != 0) {
                val h = readRfHeaderLocked(buf)
                header = h
                val list = ArrayList<WifiApEntry>(h.wifiCount)
                for (i in 0 until h.wifiCount) {
                    val base = OFF_RF_WIFIS + i * WIFI_STRIDE
                    val bssid = readBytes(buf, base + WIFI_OFF_BSSID, 6)
                    val flags = buf.get(base + WIFI_OFF_FLAGS).toInt() and 0xFF
                    val ssidLen = (buf.get(base + WIFI_OFF_SSID_LEN).toInt() and 0xFF).coerceIn(0, 32)
                    val ssidBytes = readBytes(buf, base + WIFI_OFF_SSID, ssidLen)
                    val ssid = String(ssidBytes, StandardCharsets.UTF_8)
                    val capabilities = readCString(buf, base + WIFI_OFF_CAPABILITIES, 48)
                    val freq = buf.getInt(base + WIFI_OFF_FREQ_MHZ)
                    val rssi = buf.getInt(base + WIFI_OFF_RSSI_DBM)
                    val chWidth = buf.getInt(base + WIFI_OFF_CH_WIDTH)
                    val centerFreq0 = buf.getInt(base + WIFI_OFF_CENTER_FREQ0)
                    val tsUs = buf.getLong(base + WIFI_OFF_TS_US)
                    val countryIso = readCString(buf, base + WIFI_OFF_COUNTRY_ISO, 4).uppercase(Locale.US)
                    list.add(
                        WifiApEntry(
                            bssid = bssid,
                            flags = flags,
                            ssid = ssid,
                            ssidBytes = ssidBytes,
                            capabilities = capabilities,
                            frequencyMhz = freq,
                            rssiDbm = rssi,
                            channelWidth = chWidth,
                            centerFreq0Mhz = centerFreq0,
                            timestampUs = tsUs,
                            countryIso = countryIso
                        )
                    )
                }
                aps = list
            }

            acquireFence()
            val s2 = buf.getInt(OFF_SEQ)
            if (s1 == s2) {
                if (magic != KUROKO_SHM_MAGIC || version != KUROKO_SHM_VERSION) return null
                if (active == 0 || !isHeartbeatFresh(heartbeatNs)) return null
                if (header != null && aps != null) return Pair(header, aps)
                return null
            }
        }
        return null
    }

    fun readBleDevices(): List<BleDeviceEntry> = readBleDevicesWithHeader()?.second ?: emptyList()

    fun readBleDevicesWithHeader(): Pair<RfHeader, List<BleDeviceEntry>>? {
        val buf = mappedBuffer ?: return null
        for (attempt in 0 until MAX_SEQLOCK_TRIES) {
            val s1 = buf.getInt(OFF_SEQ)
            if ((s1 and 1) != 0) continue
            acquireFence()

            val magic = buf.getInt(OFF_MAGIC)
            val version = buf.getInt(OFF_VERSION)
            val active = buf.getInt(OFF_ACTIVE)
            val heartbeatNs = buf.getLong(OFF_DAEMON_HEARTBEAT_NS)

            var header: RfHeader? = null
            var devs: ArrayList<BleDeviceEntry>? = null
            if (active != 0) {
                val h = readRfHeaderLocked(buf)
                header = h
                val list = ArrayList<BleDeviceEntry>(h.bleCount)
                for (i in 0 until h.bleCount) {
                    val base = OFF_RF_BLES + i * BLE_STRIDE
                    val mac = readBytes(buf, base + BLE_OFF_MAC, 6)
                    val addrType = buf.get(base + BLE_OFF_ADDR_TYPE).toInt() and 0xFF
                    val devType = buf.get(base + BLE_OFF_DEV_TYPE).toInt() and 0xFF
                    val flags = buf.get(base + BLE_OFF_FLAGS).toInt() and 0xFF
                    val nameLen = (buf.get(base + BLE_OFF_NAME_LEN).toInt() and 0xFF).coerceIn(0, 24)
                    val advLen = (buf.get(base + BLE_OFF_ADV_LEN).toInt() and 0xFF).coerceIn(0, 62)
                    val txPower = buf.get(base + BLE_OFF_TX_POWER)
                    val rssi = buf.getInt(base + BLE_OFF_RSSI)
                    val major = buf.getShort(base + BLE_OFF_MAJOR).toInt() and 0xFFFF
                    val minor = buf.getShort(base + BLE_OFF_MINOR).toInt() and 0xFFFF
                    val uuid = readBytes(buf, base + BLE_OFF_UUID, 16)
                    val nameBytes = readBytes(buf, base + BLE_OFF_NAME, nameLen)
                    val name = String(nameBytes, StandardCharsets.UTF_8)
                    val advBytes = readBytes(buf, base + BLE_OFF_ADV_BYTES, advLen)
                    list.add(
                        BleDeviceEntry(
                            mac = mac,
                            addrType = addrType,
                            deviceType = devType,
                            flags = flags,
                            txPowerDbm = txPower,
                            rssiDbm = rssi,
                            major = major,
                            minor = minor,
                            uuid = uuid,
                            name = name,
                            advBytes = advBytes
                        )
                    )
                }
                devs = list
            }

            acquireFence()
            val s2 = buf.getInt(OFF_SEQ)
            if (s1 == s2) {
                if (magic != KUROKO_SHM_MAGIC || version != KUROKO_SHM_VERSION) return null
                if (active == 0 || !isHeartbeatFresh(heartbeatNs)) return null
                if (header != null && devs != null) return Pair(header, devs)
                return null
            }
        }
        return null
    }

    private fun readBytes(buf: MappedByteBuffer, offset: Int, len: Int): ByteArray {
        if (len <= 0) return ByteArray(0)
        val out = ByteArray(len)
        for (i in 0 until len) {
            out[i] = buf.get(offset + i)
        }
        return out
    }

    private fun readCString(buf: MappedByteBuffer, offset: Int, maxLen: Int): String {
        if (maxLen <= 0) return ""
        val tmp = ByteArray(maxLen)
        var n = 0
        while (n < maxLen) {
            val b = buf.get(offset + n)
            if (b.toInt() == 0) break
            tmp[n] = b
            n++
        }
        return if (n == 0) "" else String(tmp, 0, n, StandardCharsets.UTF_8)
    }
}
