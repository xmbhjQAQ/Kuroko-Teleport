package com.kuroko.teleport.xposed

import android.annotation.SuppressLint
import android.os.Binder
import android.os.Build
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lock-free Seqlock reader for the 16KB KurokoShm memfd pushed by root kuroko_daemon
 * via Binder onTransact to system_server ("location") and com.android.phone ("phone").
 *
 * Exact byte offsets mirror common/include/kuroko_shm.h (little-endian, packed).
 */
object ShmReader {
    private const val TAG = "Kuroko-Shm"

    // Protocol & layout constants (common/include/kuroko_shm.h)
    const val KUROKO_SHM_MAGIC = 0x4F52554B
    const val KUROKO_SHM_VERSION = 1
    const val KUROKO_SHM_SIZE = 16384
    const val KUROKO_MAX_TARGETS = 32
    const val KUROKO_MAX_SVS = 32
    const val KUROKO_NMEA_BUF_SIZE = 2040

    // Binder transaction codes (within 0x00000001..0x00ffffff for libbinder_ndk)
    const val KUROKO_BINDER_TX_SET_SHM = 0x004B5243
    const val KUROKO_BINDER_TX_PING = 0x004B5250
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
                        if (code != KUROKO_BINDER_TX_SET_SHM && code != KUROKO_BINDER_TX_PING) {
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
     * Handles root-only Kuroko Teleport Binder transactions (SET_SHM and PING).
     * Supports standard libbinder_ndk interface-token-prefixed parcels via enforceInterface()
     * as well as raw parcels without interface token as fallback.
     */
    fun handleBinderTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        expectedInterface: String
    ): Boolean {
        if (code != KUROKO_BINDER_TX_SET_SHM && code != KUROKO_BINDER_TX_PING) {
            return false
        }
        // Strictly root-only (UID 0 = kuroko_daemon)
        if (Binder.getCallingUid() != 0) {
            return false
        }

        val startPos = data.dataPosition()
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
}
