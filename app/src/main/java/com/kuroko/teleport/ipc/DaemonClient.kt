package com.kuroko.teleport.ipc

import android.content.Context
import androidx.annotation.StringRes
import com.kuroko.teleport.BuildConfig
import com.kuroko.teleport.R
import com.kuroko.teleport.diag.AppLogBuffer
import com.kuroko.teleport.rf.PinnedBleBeacon
import com.kuroko.teleport.rf.PinnedCell
import com.kuroko.teleport.rf.PinnedWifiAp
import com.kuroko.teleport.rf.RfConfig
import com.kuroko.teleport.rf.RfFormatting
import com.kuroko.teleport.ui.AppLocaleManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Protocol constants mirrored 1:1 from common/include/kuroko_shm.h & native/daemon/main.cpp
 */
object KurokoProtocol {
    const val APP_VERSION_NAME = BuildConfig.VERSION_NAME
    const val APP_VERSION_CODE = BuildConfig.VERSION_CODE
    const val IPC_PROTOCOL_VERSION = BuildConfig.IPC_PROTOCOL_VERSION
    const val SHM_SCHEMA_VERSION = BuildConfig.SHM_SCHEMA_VERSION
    const val MIN_COMPAT_MODULE_CODE = BuildConfig.MIN_COMPAT_MODULE_CODE

    const val CTRL_HOST = "127.0.0.1"
    const val CTRL_PORT = 28765
    const val TOKEN_FILE_PATH = "/data/adb/kuroko/token"
    const val MAX_TARGETS = 32

    // KurokoMotionMode
    const val MOTION_STILL = 0
    const val MOTION_WALK = 1
    const val MOTION_JOG = 2
    const val MOTION_RUN = 3
    const val MOTION_SPRINT = 4
    const val MOTION_AUTO = 5

    // KurokoCarryMode
    const val CARRY_HAND_SWING = 0
    const val CARRY_POCKET = 1

    // Per-target UID policy bitmask (mirrored 1:1 from common/include/kuroko_shm.h)
    const val POLICY_LOCATION = 1 shl 0
    const val POLICY_STEPS = 1 shl 1
    const val POLICY_IMU = 1 shl 2
    const val POLICY_HIDE_WIFI = 1 shl 3
    const val POLICY_HIDE_CELL = 1 shl 4
    const val POLICY_SUPPRESS_MEAS = 1 shl 5
    const val POLICY_SPOOF_WIFI = 1 shl 6   // Overrides HIDE_WIFI
    const val POLICY_SPOOF_CELL = 1 shl 7   // Overrides HIDE_CELL
    const val POLICY_HIDE_BLE = 1 shl 8
    const val POLICY_SPOOF_BLE = 1 shl 9    // Overrides HIDE_BLE

    /** Full spoof preset: location + steps + IMU + spoof Wi-Fi + spoof cell + spoof BLE + suppress raw GNSS measurements */
    const val POLICY_ALL_SPOOF =
        POLICY_LOCATION or POLICY_STEPS or POLICY_IMU or
            POLICY_SPOOF_WIFI or POLICY_SPOOF_CELL or POLICY_SPOOF_BLE or
            POLICY_SUPPRESS_MEAS

    /** Cross-city strict policy: location + steps + IMU + hide Wi-Fi + hide cell + hide BLE + suppress raw GNSS measurements */
    const val POLICY_ALL_STRICT =
        POLICY_LOCATION or POLICY_STEPS or POLICY_IMU or
            POLICY_HIDE_WIFI or POLICY_HIDE_CELL or POLICY_HIDE_BLE or
            POLICY_SUPPRESS_MEAS

    /** Local outdoor policy: location + steps + IMU + hide Wi-Fi + suppress raw GNSS measurements (keeps cell towers) */
    const val POLICY_LOCAL_OUTDOOR =
        POLICY_LOCATION or POLICY_STEPS or POLICY_IMU or
            POLICY_HIDE_WIFI or POLICY_SUPPRESS_MEAS

    // Global RF synthesis flags (KUROKO_RF_FLAG_*)
    const val RF_FLAG_CELL_AUTO = 1 shl 0
    const val RF_FLAG_WIFI_AUTO = 1 shl 1
    const val RF_FLAG_WIFI_SPOOF_CONN = 1 shl 2
    const val RF_FLAG_BLE_AMBIENT = 1 shl 3
    const val RF_FLAG_SPOOF_SIM_COUNTRY = 1 shl 4
    const val RF_FLAG_CELL_PREFER_5G = 1 shl 5

    const val MAX_PINNED_CELLS = 4
    const val MAX_PINNED_WIFIS = 8
    const val MAX_PINNED_BEACONS = 8
    const val PIN_FOLLOW_SENTINEL = 999.0
}

data class DaemonTargetSpec(
    val uid: Int,
    val policyFlags: Int = KurokoProtocol.POLICY_ALL_STRICT
)

data class DaemonWaypoint(
    val latWgs84: Double,
    val lonWgs84: Double,
    val altM: Double = 45.0
)

data class SimStatus(
    val active: Boolean = false,
    val moving: Boolean = false,
    val loop: Boolean = true,
    val latWgs84: Double = 39.9042,
    val lonWgs84: Double = 116.4074,
    val altM: Double = 45.0,
    val speedMps: Float = 0f,
    val targetSpeedMps: Float = 2.8f,
    val bearingDeg: Float = 0f,
    val hAccuracyM: Float = 4.5f,
    val totalDistanceM: Float = 0f,
    val routeLengthM: Float = 0f,
    val maxDistanceM: Float = 0f,
    val laps: Int = 0,
    val maxLaps: Int = 0,
    val virtualSteps: Long = 0L,
    val cadenceSpm: Float = 0f,
    val strideM: Float = 0f,
    val motionMode: Int = KurokoProtocol.MOTION_AUTO,
    val carryMode: Int = KurokoProtocol.CARRY_HAND_SWING,
    val jitterSigmaM: Float = 2.0f,
    val paceVarRatio: Float = 0.08f,
    val targetCount: Int = 0,
    val locShm: Boolean = false,
    val phoneShm: Boolean = false,
    val sensorShm: Boolean = false,
    val btShm: Boolean = false,
    val sensorDiag: String = "",
    val modVer: String = "",
    val modCode: Int = 0,
    val protoVer: Int = 0,
    val shmVer: Int = 0,
    val minAppCode: Int = 0,
    val xposedCode: Int = 0,
    val zygiskCode: Int = 0,
    val rfCountry: String = "",
    val rfMcc: Int = 0,
    val rfMnc: Int = 0,
    val rfCells: Int = 0,
    val rfWifis: Int = 0,
    val rfBles: Int = 0,
    val rfServing: String = ""
)

typealias DaemonStatus = SimStatus

enum class VersionCompatState {
    OFFLINE,
    MATCHED,
    REBOOT_REQUIRED,
    MISMATCH_LOCKED
}

data class VersionCompatInfo(
    val state: VersionCompatState = VersionCompatState.OFFLINE,
    val canStartSimulation: Boolean = false,
    val daemonOk: Boolean = false,
    val protocolOk: Boolean = false,
    val xposedOk: Boolean = true,
    val zygiskOk: Boolean = true,
    val headline: String = "Daemon Offline",
    val detail: String = "Ensure the KernelSU2 module is enabled and rebooted",
    val actionHint: String = "Check module status and tap Reconnect"
)

fun evaluateVersionCompat(
    connected: Boolean,
    st: SimStatus,
    context: Context? = null
): VersionCompatInfo {
    val ctx = context?.let { AppLocaleManager.localizedContext(it) }
        ?: KurokoRuntime.localizedContextOrNull()

    fun str(@StringRes id: Int, fallback: String, vararg args: Any): String {
        return ctx?.let {
            runCatching {
                if (args.isEmpty()) it.getString(id) else it.getString(id, *args)
            }.getOrNull()
        } ?: fallback
    }

    if (!connected) {
        return VersionCompatInfo(
            state = VersionCompatState.OFFLINE,
            canStartSimulation = false,
            daemonOk = false,
            protocolOk = false,
            xposedOk = false,
            zygiskOk = false,
            headline = str(R.string.compat_offline_headline, "Daemon Offline"),
            detail = str(
                R.string.compat_offline_detail,
                "Cannot verify 4-tier versions; ensure kuroko_daemon is running"
            ),
            actionHint = str(
                R.string.compat_offline_hint,
                "Check the Kuroko Teleport module in KernelSU Manager, then reboot or reconnect"
            )
        )
    }

    val appVer = KurokoProtocol.APP_VERSION_NAME
    val appCode = KurokoProtocol.APP_VERSION_CODE
    val appProto = KurokoProtocol.IPC_PROTOCOL_VERSION
    val appShm = KurokoProtocol.SHM_SCHEMA_VERSION

    val daemonVerOk = st.modCode == appCode &&
        st.modVer == appVer &&
        (st.minAppCode <= 0 || appCode >= st.minAppCode) &&
        st.modCode >= KurokoProtocol.MIN_COMPAT_MODULE_CODE
    val protoOk = st.protoVer == appProto && st.shmVer == appShm
    val xposedOk = !st.locShm || st.xposedCode == appCode
    val zygiskOk = !st.sensorShm || st.zygiskCode == appCode

    if (daemonVerOk && protoOk && xposedOk && zygiskOk) {
        return VersionCompatInfo(
            state = VersionCompatState.MATCHED,
            canStartSimulation = true,
            daemonOk = true,
            protocolOk = true,
            xposedOk = true,
            zygiskOk = true,
            headline = str(
                R.string.compat_matched_headline,
                "All 4 tiers aligned (v$appVer #$appCode)",
                appVer,
                appCode
            ),
            detail = str(
                R.string.compat_matched_detail,
                "App / LSPosed Hook / Native Daemon / Zygisk Sensor · P$appProto matched",
                appProto
            ),
            actionHint = ""
        )
    }

    val legacyLabel = str(R.string.common_legacy, "Legacy")
    if (daemonVerOk && protoOk && (!xposedOk || !zygiskOk)) {
        val staleParts = buildList {
            if (!xposedOk) {
                val xpLabel = if (st.xposedCode > 0) "#${st.xposedCode}" else legacyLabel
                add("LSPosed Hook($xpLabel)")
            }
            if (!zygiskOk) {
                val zyLabel = if (st.zygiskCode > 0) "#${st.zygiskCode}" else legacyLabel
                add("Zygisk Sensor($zyLabel)")
            }
        }
        val sep = str(R.string.common_list_sep, ", ")
        val staleJoined = staleParts.joinToString(sep)
        return VersionCompatInfo(
            state = VersionCompatState.REBOOT_REQUIRED,
            canStartSimulation = false,
            daemonOk = true,
            protocolOk = true,
            xposedOk = xposedOk,
            zygiskOk = zygiskOk,
            headline = str(R.string.compat_reboot_headline, "Reboot Required"),
            detail = str(
                R.string.compat_reboot_detail,
                "App and Daemon are at v$appVer (#$appCode), but $staleJoined in system memory is still outdated.",
                appVer,
                appCode,
                staleJoined
            ),
            actionHint = str(
                R.string.compat_reboot_hint,
                "Reboot your device so the new system hooks take effect before starting."
            )
        )
    }

    val (headline, detail, hint) = when {
        st.modCode == 0 -> Triple(
            str(R.string.compat_legacy_mod_headline, "Legacy Module · Locked"),
            str(
                R.string.compat_legacy_mod_detail,
                "Daemon reported no version (legacy module); incompatible with App v$appVer (#$appCode).",
                appVer,
                appCode
            ),
            str(
                R.string.compat_flash_module_hint,
                "Flash the Kuroko Teleport v$appVer All-in-One ZIP in KernelSU Manager and reboot.",
                appVer
            )
        )
        st.modCode < appCode -> Triple(
            str(R.string.compat_mod_older_headline, "Module Outdated · Locked"),
            str(
                R.string.compat_mod_older_detail,
                "KSU module v${st.modVer} (#${st.modCode}) / P${st.protoVer} is older than App v$appVer (#$appCode) / P$appProto.",
                st.modVer,
                st.modCode,
                st.protoVer,
                appVer,
                appCode,
                appProto
            ),
            str(
                R.string.compat_flash_module_hint,
                "Flash the Kuroko Teleport v$appVer All-in-One ZIP in KernelSU Manager and reboot.",
                appVer
            )
        )
        st.modCode > appCode -> Triple(
            str(R.string.compat_app_older_headline, "App Outdated · Locked"),
            str(
                R.string.compat_app_older_detail,
                "App v$appVer (#$appCode) is older than KSU module v${st.modVer} (#${st.modCode}).",
                appVer,
                appCode,
                st.modVer,
                st.modCode
            ),
            str(
                R.string.compat_app_older_hint,
                "Update to Kuroko Teleport App v${st.modVer} (or re-flash the v${st.modVer} All-in-One ZIP).",
                st.modVer
            )
        )
        else -> Triple(
            str(R.string.compat_proto_mismatch_headline, "Version Mismatch · Locked"),
            str(
                R.string.compat_proto_mismatch_detail,
                "Module v${st.modVer} (#${st.modCode}) P${st.protoVer}/SHM${st.shmVer} mismatches App v$appVer (#$appCode) P$appProto/SHM$appShm.",
                st.modVer,
                st.modCode,
                st.protoVer,
                st.shmVer,
                appVer,
                appCode,
                appProto,
                appShm
            ),
            str(
                R.string.compat_proto_mismatch_hint,
                "Flash the full Kuroko Teleport v$appVer All-in-One ZIP and reboot so all 4 tiers match.",
                appVer
            )
        )
    }

    return VersionCompatInfo(
        state = VersionCompatState.MISMATCH_LOCKED,
        canStartSimulation = false,
        daemonOk = daemonVerOk,
        protocolOk = protoOk,
        xposedOk = xposedOk,
        zygiskOk = zygiskOk,
        headline = headline,
        detail = detail,
        actionHint = hint
    )
}

/**
 * Authenticated TCP loopback client for kuroko_daemon (127.0.0.1:28765).
 * Reads the per-boot auth token once via `su -c cat /data/adb/kuroko/token` and maintains
 * a persistent authenticated connection with automatic reconnection.
 */
class DaemonClient {
    private val mutex = Mutex()

    @Volatile
    var contextProvider: (() -> Context?)? = null

    @Volatile
    private var cachedToken: String? = null

    private var socket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    @Volatile
    private var lastErrSignature: String? = null

    @Volatile
    private var lastErrLogMs: Long = 0L

    private fun logFailureThrottled(signature: String, detail: String, error: Boolean = true) {
        val now = System.currentTimeMillis()
        if (lastErrSignature == signature && now - lastErrLogMs < 30_000L) return
        lastErrSignature = signature
        lastErrLogMs = now
        if (error) {
            AppLogBuffer.e("DaemonClient", detail)
        } else {
            AppLogBuffer.w("DaemonClient", detail)
        }
    }

    private fun clearFailureThrottle() {
        lastErrSignature = null
        lastErrLogMs = 0L
    }

    private fun errMsg(@StringRes resId: Int, fallback: String, vararg args: Any): String {
        val ctx = contextProvider?.invoke()
        return ctx?.let {
            runCatching {
                if (args.isEmpty()) it.getString(resId) else it.getString(resId, *args)
            }.getOrNull()
        } ?: fallback
    }

    suspend fun ping(): Result<Boolean> = sendCommand("PING").map { it.startsWith("OK PONG") }

    suspend fun getStatus(): Result<SimStatus> = sendCommand("STATUS").mapCatching { raw ->
        val j = JSONObject(raw)
        SimStatus(
            active = j.optInt("active", 0) != 0,
            moving = j.optInt("moving", 0) != 0,
            loop = j.optInt("loop", 1) != 0,
            latWgs84 = j.optDouble("lat", 39.9042),
            lonWgs84 = j.optDouble("lon", 116.4074),
            altM = j.optDouble("alt", 45.0),
            speedMps = j.optDouble("speed", 0.0).toFloat(),
            targetSpeedMps = j.optDouble("target_speed", 2.8).toFloat(),
            bearingDeg = j.optDouble("bearing", 0.0).toFloat(),
            hAccuracyM = j.optDouble("acc", 4.5).toFloat(),
            totalDistanceM = j.optDouble("dist", 0.0).toFloat(),
            routeLengthM = j.optDouble("route_len", 0.0).toFloat(),
            maxDistanceM = j.optDouble("max_dist", 0.0).toFloat(),
            laps = j.optInt("laps", 0),
            maxLaps = j.optInt("max_laps", 0),
            virtualSteps = j.optLong("steps", 0L),
            cadenceSpm = j.optDouble("cadence", 0.0).toFloat(),
            strideM = j.optDouble("stride", 0.0).toFloat(),
            motionMode = j.optInt("mode", KurokoProtocol.MOTION_AUTO),
            carryMode = j.optInt("carry", KurokoProtocol.CARRY_HAND_SWING),
            jitterSigmaM = j.optDouble("jitter", 2.0).toFloat(),
            paceVarRatio = j.optDouble("pace_var", 0.08).toFloat(),
            targetCount = j.optInt("targets", 0),
            locShm = j.optInt("loc_shm", 0) != 0,
            phoneShm = j.optInt("phone_shm", 0) != 0,
            sensorShm = j.optInt("sensor_shm", 0) != 0,
            btShm = j.optInt("bt_shm", 0) != 0,
            sensorDiag = j.optString("sensor_diag", ""),
            modVer = j.optString("mod_ver", ""),
            modCode = j.optInt("mod_code", 0),
            protoVer = j.optInt("proto_ver", 0),
            shmVer = j.optInt("shm_ver", 0),
            minAppCode = j.optInt("min_app_code", 0),
            xposedCode = j.optInt("xposed_code", 0),
            zygiskCode = j.optInt("zygisk_code", 0),
            rfCountry = j.optString("rf_country", ""),
            rfMcc = j.optInt("rf_mcc", 0),
            rfMnc = j.optInt("rf_mnc", 0),
            rfCells = j.optInt("rf_cells", 0),
            rfWifis = j.optInt("rf_wifis", 0),
            rfBles = j.optInt("rf_bles", 0),
            rfServing = j.optString("rf_serving", "")
        )
    }

    suspend fun start(): Result<String> = sendCommand("START")

    suspend fun hold(): Result<String> = sendCommand("HOLD")

    suspend fun stop(): Result<String> = sendCommand("STOP")

    suspend fun resetSteps(): Result<String> = sendCommand("RESET_STEPS")

    suspend fun setPoint(latWgs84: Double, lonWgs84: Double, altM: Double = 45.0): Result<String> {
        val cmd = String.format(Locale.US, "SET_POINT %.7f %.7f %.2f", latWgs84, lonWgs84, altM)
        return sendCommand(cmd)
    }

    /**
     * Sends v0.2.0 4-arg SET_ROUTE: `SET_ROUTE <loop:0|1> <max_dist_m> <max_laps:0=inf,N>=1> <pts>`
     */
    suspend fun setRoute(
        points: List<DaemonWaypoint>,
        loop: Boolean,
        maxDistanceM: Float = 0f,
        maxLaps: Int = 0
    ): Result<String> {
        if (points.isEmpty()) {
            return Result.failure(
                IllegalArgumentException(errMsg(R.string.daemon_err_empty_route, "Route cannot be empty"))
            )
        }
        val loopFlag = if (loop) 1 else 0
        val clampedLaps = maxLaps.coerceAtLeast(0)
        val serialized = points.joinToString(";") { pt ->
            String.format(Locale.US, "%.7f,%.7f,%.2f", pt.latWgs84, pt.lonWgs84, pt.altM)
        }
        val cmd = String.format(
            Locale.US,
            "SET_ROUTE %d %.2f %d %s",
            loopFlag,
            maxDistanceM.coerceAtLeast(0f),
            clampedLaps,
            serialized
        )
        return sendCommand(cmd)
    }

    suspend fun setSpeed(speedMps: Float): Result<String> {
        val cmd = String.format(Locale.US, "SET_SPEED %.3f", speedMps.coerceIn(0f, 30f))
        return sendCommand(cmd)
    }

    suspend fun setMode(motionMode: Int, carryMode: Int): Result<String> {
        return sendCommand("SET_MODE $motionMode $carryMode")
    }

    suspend fun setJitter(sigmaM: Float): Result<String> {
        val cmd = String.format(Locale.US, "SET_JITTER %.2f", sigmaM.coerceIn(0f, 20f))
        return sendCommand(cmd)
    }

    suspend fun setPaceVar(ratio: Float): Result<String> {
        val cmd = String.format(Locale.US, "SET_PACE_VAR %.3f", ratio.coerceIn(0f, 0.25f))
        return sendCommand(cmd)
    }

    suspend fun setTargets(targets: List<DaemonTargetSpec>): Result<String> {
        val trimmed = targets.filter { it.uid > 0 }.take(KurokoProtocol.MAX_TARGETS)
        val spec = if (trimmed.isEmpty()) {
            "NONE"
        } else {
            trimmed.joinToString(",") { "${it.uid}:${it.policyFlags}" }
        }
        return sendCommand("SET_TARGETS $spec")
    }

    suspend fun setLang(lang: String): Result<String> {
        val trimmed = lang.trim().replace('_', '-')
        val safeBcp47 = Regex("^[a-zA-Z]{2,3}(-[a-zA-Z0-9]{2,8}){0,2}$")
        val valid = trimmed.equals("auto", ignoreCase = true) ||
            (trimmed.length <= 16 && safeBcp47.matches(trimmed))
        if (!valid) {
            return Result.failure(IllegalArgumentException("Unsupported lang tag: $lang"))
        }
        val normalized = if (trimmed.equals("auto", ignoreCase = true)) "auto" else trimmed
        return sendCommand("SET_LANG $normalized")
    }

    suspend fun setRfConfig(config: RfConfig): Result<String> {
        val flags = config.toRfFlags()
        val mcc = if (config.carrierAuto) 0 else config.carrierMcc.coerceIn(0, 999)
        val mnc = if (config.carrierAuto) 0 else config.carrierMnc.coerceIn(0, 999)
        val mncDigits = if (config.carrierMncDigits == 3) 3 else 2
        val iso = if (config.carrierAuto) "AUTO" else RfFormatting.normalizeCountryIsoOrAuto(config.carrierCountryIso)
        val alphaB64 = if (config.carrierAuto) "AUTO" else RfFormatting.alphaToBase64OrAuto(config.carrierAlpha)
        val cmd = String.format(
            Locale.US,
            "SET_RF_CONFIG 0x%x %d %d %d %s %s",
            flags,
            mcc,
            mnc,
            mncDigits,
            iso,
            alphaB64
        )
        return sendCommand(cmd)
    }

    suspend fun setRfCells(cells: List<PinnedCell>): Result<String> {
        val trimmed = cells.take(KurokoProtocol.MAX_PINNED_CELLS)
        if (trimmed.isEmpty()) {
            return sendCommand("SET_RF_CELLS NONE")
        }
        val spec = trimmed.joinToString(";") { c ->
            val lat = if (c.followVirtual) KurokoProtocol.PIN_FOLLOW_SENTINEL else c.pinLat
            val lon = if (c.followVirtual) KurokoProtocol.PIN_FOLLOW_SENTINEL else c.pinLon
            String.format(
                Locale.US,
                "%d:%d:%d:%d:%d:%d:%d:%d:%d:%d:%.6f:%.6f",
                if (c.rat == 2) 2 else 1,
                c.mcc.coerceIn(1, 999),
                c.mnc.coerceIn(0, 999),
                if (c.mncDigits == 3) 3 else 2,
                c.tac.coerceIn(0L, 16_777_215L),
                c.ci.coerceAtLeast(0L),
                c.pci.coerceIn(0, 1007),
                c.arfcn.coerceAtLeast(0),
                c.band.coerceIn(0, 512),
                c.rsrpDbm.coerceIn(-140, -44),
                lat,
                lon
            )
        }
        return sendCommand("SET_RF_CELLS $spec")
    }

    suspend fun setRfWifis(wifis: List<PinnedWifiAp>): Result<String> {
        val trimmed = wifis.take(KurokoProtocol.MAX_PINNED_WIFIS)
        if (trimmed.isEmpty()) {
            return sendCommand("SET_RF_WIFIS NONE")
        }
        val spec = trimmed.joinToString(";") { w ->
            val bssidHex12 = RfFormatting.macToHex12(w.bssid).lowercase(Locale.US)
            val flags = if (w.isConnectedAp) 1 else 0
            val lat = if (w.followVirtual) KurokoProtocol.PIN_FOLLOW_SENTINEL else w.pinLat
            val lon = if (w.followVirtual) KurokoProtocol.PIN_FOLLOW_SENTINEL else w.pinLon
            val ssidHex = RfFormatting.utf8ToHex(w.ssid.ifBlank { "WiFi-AP" }, 32)
            String.format(
                Locale.US,
                "%s:%d:%d:%d:%.6f:%.6f:%s",
                bssidHex12,
                w.frequencyMhz.coerceIn(2412, 7125),
                w.rssiDbm.coerceIn(-100, -25),
                flags,
                lat,
                lon,
                ssidHex
            )
        }
        return sendCommand("SET_RF_WIFIS $spec")
    }

    suspend fun setRfBeacons(beacons: List<PinnedBleBeacon>): Result<String> {
        val trimmed = beacons.take(KurokoProtocol.MAX_PINNED_BEACONS)
        if (trimmed.isEmpty()) {
            return sendCommand("SET_RF_BEACONS NONE")
        }
        val spec = trimmed.joinToString(";") { b ->
            val macHex12 = RfFormatting.macToHex12(b.mac).lowercase(Locale.US)
            val devType = if (b.deviceType == 1) 1 else 0
            val uuidHex32 = RfFormatting.normalizeUuidHex32(b.uuidHex32).lowercase(Locale.US)
            val lat = if (b.followVirtual) KurokoProtocol.PIN_FOLLOW_SENTINEL else b.pinLat
            val lon = if (b.followVirtual) KurokoProtocol.PIN_FOLLOW_SENTINEL else b.pinLon
            val nameHex = RfFormatting.utf8ToHex(b.name, 23)
            String.format(
                Locale.US,
                "%s:%d:%s:%d:%d:%d:%.6f:%.6f:%s",
                macHex12,
                devType,
                uuidHex32,
                b.major.coerceIn(0, 65535),
                b.minor.coerceIn(0, 65535),
                b.txPowerDbm.coerceIn(-100, 10),
                lat,
                lon,
                nameHex
            )
        }
        return sendCommand("SET_RF_BEACONS $spec")
    }

    /**
     * Force-clears cached token and TCP socket so next command re-reads token via root.
     */
    suspend fun resetConnection() = withContext(Dispatchers.IO) {
        mutex.withLock {
            AppLogBuffer.i("DaemonClient", "Manual reconnect requested; clearing socket and token cache")
            cachedToken = null
            clearFailureThrottle()
            closeSocketLocked()
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            closeSocketLocked()
        }
    }

    private suspend fun sendCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            var lastError: Throwable? = null
            for (attempt in 0..1) {
                try {
                    ensureConnectedLocked(forceRefreshToken = attempt > 0)
                    val w = writer ?: throw IOException("Socket writer unavailable")
                    val r = reader ?: throw IOException("Socket reader unavailable")
                    w.write(command)
                    w.write("\n")
                    w.flush()
                    val response = r.readLine()
                        ?: throw IOException(errMsg(R.string.daemon_err_disconnected, "Daemon disconnected"))
                    if (response.startsWith("ERR UNAUTHORIZED")) {
                        AppLogBuffer.w("DaemonClient", "Session unauthorized; refreshing token and reconnecting")
                        cachedToken = null
                        closeSocketLocked()
                        lastError = IOException(
                            errMsg(R.string.daemon_err_token_expired, "Auth token expired, please retry")
                        )
                        continue
                    }
                    if (response.startsWith("ERR ")) {
                        val verb = command.substringBefore(' ')
                        AppLogBuffer.w("DaemonClient", "Command $verb rejected: $response")
                        return@withLock Result.failure(IllegalStateException(response))
                    }
                    clearFailureThrottle()
                    return@withLock Result.success(response)
                } catch (e: Exception) {
                    lastError = e
                    closeSocketLocked()
                    if (attempt == 0 && e.message?.contains("UNAUTHORIZED", ignoreCase = true) == true) {
                        cachedToken = null
                    }
                }
            }
            val verb = command.substringBefore(' ')
            val errSummary = lastError?.let { "${it.javaClass.simpleName}: ${it.message ?: "error"}" } ?: "comm failed"
            logFailureThrottled("$verb:$errSummary", "IPC $verb failed: $errSummary")
            Result.failure(
                lastError ?: IOException(errMsg(R.string.daemon_err_comm_failed, "Daemon communication failed"))
            )
        }
    }

    private fun ensureConnectedLocked(forceRefreshToken: Boolean) {
        val cur = socket
        if (cur != null && cur.isConnected && !cur.isClosed && reader != null && writer != null) {
            return
        }
        closeSocketLocked()

        val token = if (forceRefreshToken || cachedToken.isNullOrEmpty()) {
            fetchTokenViaRoot().also { cachedToken = it }
        } else {
            cachedToken!!
        }

        val sock = Socket()
        try {
            sock.connect(
                InetSocketAddress(KurokoProtocol.CTRL_HOST, KurokoProtocol.CTRL_PORT),
                2000
            )
            sock.soTimeout = 2500
            sock.tcpNoDelay = true

            val br = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            val bw = BufferedWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8))

            bw.write("AUTH $token\n")
            bw.flush()
            val authResp = br.readLine()
                ?: throw IOException(errMsg(R.string.daemon_err_auth_no_resp, "No auth response from daemon"))
            if (!authResp.startsWith("OK AUTH")) {
                cachedToken = null
                AppLogBuffer.e("DaemonClient", "Daemon auth rejected: $authResp")
                throw IOException(
                    errMsg(R.string.daemon_err_auth_failed, "Daemon auth failed: $authResp", authResp)
                )
            }

            socket = sock
            reader = br
            writer = bw
            clearFailureThrottle()
            AppLogBuffer.i("DaemonClient", "Authenticated TCP loopback session on 127.0.0.1:${KurokoProtocol.CTRL_PORT}")
        } catch (e: Exception) {
            runCatching { sock.close() }
            throw e
        }
    }

    private fun fetchTokenViaRoot(): String {
        val process = try {
            ProcessBuilder("su", "-c", "cat ${KurokoProtocol.TOKEN_FILE_PATH}")
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            val detail = e.message ?: "error"
            logFailureThrottled("su_spawn:$detail", "su spawn failed while fetching daemon token: $detail")
            throw IOException(
                errMsg(R.string.daemon_err_su_failed, "Unable to run su for daemon token: $detail", detail),
                e
            )
        }

        val finished = process.waitFor(4, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            logFailureThrottled("su_timeout", "Root token read timed out after 4s")
            throw IOException(errMsg(R.string.daemon_err_root_timeout, "Root token read timed out"))
        }
        val output = process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }.trim()
        val exitCode = process.exitValue()
        if (exitCode != 0 || output.isEmpty()) {
            logFailureThrottled(
                "token_exit_$exitCode",
                "Failed to read daemon token file (exitCode=$exitCode)"
            )
            throw IOException(
                errMsg(
                    R.string.daemon_err_token_read,
                    "Failed to read ${KurokoProtocol.TOKEN_FILE_PATH} (code=$exitCode): $output",
                    KurokoProtocol.TOKEN_FILE_PATH,
                    exitCode,
                    output
                )
            )
        }
        val tokenLine = output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.length >= 16 && it.all { ch -> ch.isLetterOrDigit() } }
            ?: output.lineSequence().last().trim()
        if (tokenLine.isEmpty()) {
            logFailureThrottled("token_empty", "Daemon token file is empty")
            throw IOException(
                errMsg(
                    R.string.daemon_err_token_empty,
                    "Daemon token is empty; verify kuroko_daemon is running"
                )
            )
        }
        return tokenLine
    }

    private fun closeSocketLocked() {
        runCatching { reader?.close() }
        runCatching { writer?.close() }
        runCatching { socket?.close() }
        reader = null
        writer = null
        socket = null
    }
}
