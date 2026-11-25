package com.kuroko.teleport.diag

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.kuroko.teleport.R
import com.kuroko.teleport.ipc.DaemonSnapshot
import com.kuroko.teleport.ipc.KurokoProtocol
import com.kuroko.teleport.ipc.MotionControlState
import com.kuroko.teleport.ipc.OverlayPrefsState
import com.kuroko.teleport.ipc.VersionCompatInfo
import com.kuroko.teleport.ipc.VersionCompatState
import com.kuroko.teleport.ui.AppLocaleManager
import com.kuroko.teleport.ui.SavedTargetApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class HealthStatus {
    PASS,
    WARN,
    FAIL
}

data class HealthCheckItem(
    val id: String,
    val title: String,
    val status: HealthStatus,
    val detail: String
)

data class DeviceMetadata(
    val brand: String,
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val abis: String,
    val display: String,
    val incremental: String,
    val kernelVersion: String
)

data class DiagnosticSnapshot(
    val collectedAtMs: Long,
    val collectedAtClock: String,
    val collectedAtIsoUtc: String,
    val rootAvailable: Boolean,
    val rootError: String,
    val deviceInfo: DeviceMetadata,
    val appVersionName: String,
    val appVersionCode: Int,
    val appProtoVer: Int,
    val appShmVer: Int,
    val appLangTag: String,
    val daemonSnap: DaemonSnapshot,
    val versionCompat: VersionCompatInfo,
    val motion: MotionControlState,
    val overlayPrefs: OverlayPrefsState,
    val savedTargets: List<SavedTargetApp>,
    val moduleProp: String,
    val moduleDisabled: Boolean,
    val moduleRemovePending: Boolean,
    val moduleUpdatePending: Boolean,
    val bootGuardCount: Int,
    val bootGuardRaw: String,
    val persistedLang: String,
    val apkInstalledProp: String,
    val daemonPids: String,
    val portListening: Boolean,
    val gmsDisabledFile: Boolean,
    val gmsStatusFile: String,
    val gmsProcesses: List<String>,
    val gmsHookPidAlive: Boolean = false,
    val lsposedSensorHook: String,
    val sensorServiceInstances: Int,
    val sensorServiceVmaCount: Int,
    val sensorServiceMaps: String,
    val daemonLog: String,
    val lsposedLog: String,
    val systemLogcat: String,
    val selinuxAvcCount: Int,
    val appEventsLog: String
)

/**
 * On-demand diagnostic snapshot collector, mandatory privacy redactor, auto health check engine,
 * and Markdown/ZIP exporter for Kuroko Teleport.
 *
 * Security & privacy invariants:
 * - Strictly read-only shell snapshot (never reads /data/adb/kuroko/token).
 * - Never exports API keys, saved routes, pins, coordinates, serials, IMEI, MAC, or unrelated app logs.
 * - Re-verifies strict whitelists in Kotlin for both logcat and LSPosed module logs.
 */
object DiagnosticCollector {
    private const val SHELL_TIMEOUT_SECONDS = 10L
    private const val MAX_STDOUT_BYTES = 1024 * 1024 // 1 MiB hard cap
    private const val CACHE_PRUNE_AGE_MS = 24L * 60L * 60L * 1000L

    private val AUTH_HEX_REGEX = Regex("""(?i)\bAUTH\s+[A-Za-z0-9._-]{4,}""")
    private val TOKEN_KV_REGEX = Regex("""(?i)\btoken\s*[=:]\s*[^\s&'",;}\]]+""")
    private val URL_KEY_REGEX = Regex("""(?i)([?&](?:tk|key|apikey|api_key)=)[^&\s'"]+""")
    private val SET_POINT_REGEX = Regex("""(?i)\bSET_POINT\b[^\r\n]*""")
    private val SET_ROUTE_REGEX = Regex("""(?i)\bSET_ROUTE\b[^\r\n]*""")
    private val SET_RF_PIN_REGEX = Regex("""(?i)\bSET_RF_(?:CELLS|WIFIS|BEACONS)\b[^\r\n]*""")
    private val NMEA_REGEX = Regex("""\x24G[PNLA][A-Z]{3},[^\r\n]*""")
    private val KEYED_COORD_REGEX = Regex(
        """(?i)\b(lat|lon|lng|latitude|longitude)\s*([=:])\s*-?\d{1,3}\.\d{3,}"""
    )
    private val BARE_COORD_PAIR_REGEX = Regex(
        """-?(?:[1-8]?\d\.\d{4,}|90\.0{4,})\s*[,/]\s*-?(?:1[0-7]\d\.\d{4,}|[1-9]?\d\.\d{4,}|180\.0{4,})"""
    )
    private val MAC_ADDR_REGEX = Regex("""\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")
    private val EMAIL_REGEX = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")

    private val AVC_DENIED_REGEX = Regex("""(?i)\bavc:\s*denied\b""")
    private val AVC_UI_SANDBOX_SRC_REGEX = Regex(
        """(?i)(?:\bscontext=u:r:untrusted_app[a-z0-9_]*:|\sRenderThread\s*:)"""
    )
    private val AVC_UI_GPU_NOISE_TARGET_REGEX = Regex(
        """(?i)(?:proc_perfmgr|hal_perf|vendor_hal_perf|kgsl|perfmgr|gpu)"""
    )
    private val AVC_ENGINE_SCONTEXT_REGEX = Regex(
        """(?i)\bscontext=u:r:(?:system_server|zygote|radio|bluetooth|su|magisk|init|kuroko)[a-z0-9_]*:"""
    )
    private val AVC_ENGINE_SUBJECT_REGEX = Regex(
        """(?i)(?:kuroko|memfd|tmpfs|adb_data_file|sensorservice|binder|location|unix_stream_socket)"""
    )
    private val AVC_GMS_SCONTEXT_REGEX = Regex(
        """(?i)\bscontext=u:r:gmscore_app[a-z0-9_]*:"""
    )
    private val AVC_GMS_SUBJECT_REGEX = Regex(
        """(?i)(?:kuroko|su_tmpfs|magisk_tmpfs|tmpfs|memfd|\btclass=fd\b|\bfd\b)"""
    )
    private val AVC_DIRECT_TARGET_REGEX = Regex(
        """(?i)(?:\btcontext=[^\s]*(?:kuroko|memfd|adb_data_file|sensorservice)|\bname="[^"]*(?:kuroko|memfd|adb_data_file|sensorservice)[^"]*")"""
    )
    private val FOREIGN_LSPOSED_HEADER_REGEX = Regex(
        """(?:\)\[|\[)([a-zA-Z0-9_]+(?:\.[a-zA-Z0-9_]+)+),"""
    )
    private val LSPOSED_SENSOR_HOOK_REGEX = Regex("""\bkuroko\.sensor\.hook=([^\s()]+)""")
    private val THREADTIME_LINE_REGEX = Regex(
        """^\s*\d{2}-\d{2}\s+\d{2}:\d{2}:\d{2}\.\d{3}\s+(\d+)\s+\d+\s+([VDIWEFA])\s+([^:]+?)\s*:"""
    )
    private val NOISY_UI_OEM_TAGS = setOf(
        "MIUIInput",
        "PerfBoostPolicy",
        "MiInputBoostRt",
        "SchedBoostThread",
        "PackageConfigPersister",
        "ViewRootImpl",
        "InputTransport",
        "BLASTBufferQueue",
        "BufferQueueProducer",
        "OpenGLRenderer",
        "Hwui",
        "SurfaceComposerClient",
        "InsetsController"
    )
    private val GENERIC_WARN_ERR_REGEX = Regex(
        """(\[(?:W|E|WARN|ERROR)\]|(?i)\b(?:WARN|WARNING|ERR|ERROR|FAIL|FAILED|EXCEPTION|DENIED|ABORT|TIMEOUT|MISMATCH|TRIPPED)\b)"""
    )
    private val DIAG_MP_REGEX = Regex("""\bmp=(\d+)""")
    private val DIAG_C_REGEX = Regex("""\bc=(\d+)""")
    private val DIAG_D_REGEX = Regex("""\bd=(\d+)""")
    private val DIAG_M_REGEX = Regex("""\bm=([a-zA-Z0-9_]+)""")
    private val HOOK_CORE_REGEX = Regex("""\bcore=(\d+)/(\d+)""")
    private val HOOK_MAPS_REGEX = Regex("""\bmaps=(\d+)""")

    private val ROOT_SNAPSHOT_SCRIPT = """
echo "===MOD_PROP==="
cat /data/adb/modules/kuroko_teleport/module.prop 2>/dev/null || true
echo "===MOD_FLAGS==="
[ -f /data/adb/modules/kuroko_teleport/disable ] && echo "disable=1" || echo "disable=0"
[ -f /data/adb/modules/kuroko_teleport/remove ] && echo "remove=1" || echo "remove=0"
[ -d /data/adb/modules_update/kuroko_teleport ] && echo "update_pending=1" || echo "update_pending=0"
echo "===BOOT_GUARD==="
cat /data/adb/kuroko/boot_guard 2>/dev/null || true
echo "===PERSIST_LANG==="
cat /data/adb/kuroko/lang 2>/dev/null || true
echo "===APK_PROP==="
cat /data/adb/kuroko/apk_installed.prop 2>/dev/null || true
echo "===DAEMON_PID==="
pidof kuroko_daemon 2>/dev/null || true
echo "===TCP_LISTEN==="
grep -i ":705D " /proc/net/tcp /proc/net/tcp6 2>/dev/null || true
echo "===GMS_STATUS==="
[ -f /data/adb/kuroko/gms_disabled ] && echo "gms_disabled=1" || echo "gms_disabled=0"
cat /data/adb/kuroko/gms_status 2>/dev/null || true
echo "===GMS_PROCS==="
GMS_APP_ID=""
if [ -r /data/system/packages.list ]; then
  GMS_APP_ID=$(awk '${'$'}1=="com.google.android.gms"{print ${'$'}2; exit}' /data/system/packages.list 2>/dev/null)
  if [ -z "${'$'}GMS_APP_ID" ]; then
    while read -r p_pkg p_uid _rest; do
      if [ "${'$'}p_pkg" = "com.google.android.gms" ]; then
        GMS_APP_ID="${'$'}p_uid"
        break
      fi
    done < /data/system/packages.list 2>/dev/null
  fi
fi
if [ -z "${'$'}GMS_APP_ID" ] && [ -d /data/user/0/com.google.android.gms ]; then
  GMS_APP_ID=$(stat -c %u /data/user/0/com.google.android.gms 2>/dev/null)
fi
if [ -z "${'$'}GMS_APP_ID" ] && [ -d /data/data/com.google.android.gms ]; then
  GMS_APP_ID=$(stat -c %u /data/data/com.google.android.gms 2>/dev/null)
fi
case "${'$'}GMS_APP_ID" in
  ''|*[!0-9]*) GMS_APP_ID="" ;;
  *) GMS_APP_ID=$((GMS_APP_ID % 100000)) ;;
esac
if [ -n "${'$'}GMS_APP_ID" ] && [ "${'$'}GMS_APP_ID" -ge 10000 ]; then
  GMS_PIDS=$(pidof com.google.android.gms com.google.android.gms.persistent com.google.android.gms.unstable com.google.android.gms.ui 2>/dev/null || true)
  for pid in ${'$'}GMS_PIDS; do
    case "${'$'}pid" in ''|*[!0-9]*) continue ;; esac
    [ -r "/proc/${'$'}pid/cmdline" ] || continue
    p_uid=$(stat -c %u "/proc/${'$'}pid" 2>/dev/null) || continue
    case "${'$'}p_uid" in ''|*[!0-9]*) continue ;; esac
    [ $((p_uid % 100000)) -eq "${'$'}GMS_APP_ID" ] || continue
    p_name=$(tr '\0' ' ' < "/proc/${'$'}pid/cmdline" 2>/dev/null | sed 's/ .*//')
    case "${'$'}p_name" in
      com.google.android.gms|com.google.android.gms.*)
        p_ctx=$(tr -d '\0\r\n' < "/proc/${'$'}pid/attr/current" 2>/dev/null)
        [ -n "${'$'}p_ctx" ] || p_ctx="u:r:unknown:s0"
        echo "${'$'}p_ctx ${'$'}p_name"
        ;;
    esac
  done | head -n 8 || true
fi
echo "===GMS_RESIDENCY==="
GMS_HOOK_ALIVE=0
if [ -r /data/adb/kuroko/gms_status ]; then
  GMS_ST_PID=""
  GMS_ST_VAL=""
  while IFS='=' read -r k v; do
    case "${'$'}k" in
      pid) GMS_ST_PID="${'$'}v" ;;
      status) GMS_ST_VAL="${'$'}v" ;;
    esac
  done < /data/adb/kuroko/gms_status 2>/dev/null
  GMS_ST_PID=$(printf '%s' "${'$'}GMS_ST_PID" | tr -d '\r\n ')
  GMS_ST_VAL=$(printf '%s' "${'$'}GMS_ST_VAL" | tr -d '\r\n')
  case "${'$'}GMS_ST_VAL" in
    ok:*)
      case "${'$'}GMS_ST_PID" in
        ''|*[!0-9]*) ;;
        *)
          if [ "${'$'}GMS_ST_PID" -gt 0 ] && [ -r "/proc/${'$'}GMS_ST_PID/cmdline" ]; then
            h_uid_ok=1
            if [ -n "${'$'}GMS_APP_ID" ] && [ "${'$'}GMS_APP_ID" -ge 10000 ]; then
              h_uid=$(stat -c %u "/proc/${'$'}GMS_ST_PID" 2>/dev/null)
              case "${'$'}h_uid" in
                ''|*[!0-9]*) h_uid_ok=0 ;;
                *) [ $((h_uid % 100000)) -eq "${'$'}GMS_APP_ID" ] || h_uid_ok=0 ;;
              esac
            fi
            if [ "${'$'}h_uid_ok" -eq 1 ]; then
              h_cmd=$(tr '\0' ' ' < "/proc/${'$'}GMS_ST_PID/cmdline" 2>/dev/null | sed 's/ .*//')
              case "${'$'}h_cmd" in
                com.google.android.gms|com.google.android.gms.persistent)
                  GMS_HOOK_ALIVE=1
                  ;;
              esac
            fi
          fi
          ;;
      esac
      ;;
  esac
fi
echo "hook_pid_alive=${'$'}GMS_HOOK_ALIVE"
echo "===SENSOR_MAPS==="
set -- $(pidof system_server 2>/dev/null)
SS_PID="${'$'}1"
if [ -n "${'$'}SS_PID" ] && [ -r "/proc/${'$'}SS_PID/maps" ]; then
  grep "libsensorservice.so" "/proc/${'$'}SS_PID/maps" 2>/dev/null | head -n 32 || true
fi
echo "===DAEMON_LOG==="
if [ -r /data/adb/kuroko/daemon.log ]; then
  tail -c 131072 /data/adb/kuroko/daemon.log 2>/dev/null || true
fi
echo "===LOGCAT==="
logcat -d -v threadtime -s KurokoDaemon:* KurokoZygisk:* KurokoSensorHook:* KurokoGmsHook:* 2>/dev/null | tail -n 300 || true
logcat -d -v threadtime -t 4000 2>/dev/null | grep -E "avc:[[:space:]]*denied|AndroidRuntime|FATAL EXCEPTION|com\.kuroko\.teleport" | tail -n 300 || true
echo "===LSPOSED_LOG==="
LSP_FILES=$(ls -1t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 2)
if [ -z "${'$'}LSP_FILES" ]; then
  LSP_FILES=$(ls -1t /data/adb/lspd/log/verbose_*.log 2>/dev/null | head -n 2)
fi
for f in ${'$'}LSP_FILES; do
  if [ -r "${'$'}f" ]; then
    tail -c 262144 "${'$'}f" 2>/dev/null | grep -E "\[com\.kuroko\.teleport,|Kuroko-" || true
  fi
done
echo "===END==="
"""

    suspend fun collect(
        context: Context,
        snap: DaemonSnapshot,
        compat: VersionCompatInfo,
        motion: MotionControlState,
        overlayPrefs: OverlayPrefsState,
        savedTargets: List<SavedTargetApp>,
        appLangTag: String,
        apiKeysToScrub: List<String>
    ): DiagnosticSnapshot = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val clockStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(nowMs))
        val isoUtcStr = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(nowMs))

        val cleanKeys = apiKeysToScrub.map { it.trim() }.filter { it.length >= 3 }.distinct()

        val device = DeviceMetadata(
            brand = Build.BRAND.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.joinToString(", ").orEmpty(),
            display = Build.DISPLAY.orEmpty(),
            incremental = Build.VERSION.INCREMENTAL.orEmpty(),
            kernelVersion = System.getProperty("os.version").orEmpty()
        )

        val rootResult = runRootSnapshotScript()
        val sections = if (rootResult.success) parseSections(rootResult.stdout) else emptyMap()

        val modFlagsRaw = sections["MOD_FLAGS"].orEmpty()
        val modDisabled = modFlagsRaw.lineSequence().any { it.trim() == "disable=1" }
        val modRemove = modFlagsRaw.lineSequence().any { it.trim() == "remove=1" }
        val modUpdate = modFlagsRaw.lineSequence().any { it.trim() == "update_pending=1" }

        val bootGuardRaw = sections["BOOT_GUARD"].orEmpty().trim()
        val bootGuardCount = bootGuardRaw.toIntOrNull() ?: 0

        val tcpListenRaw = sections["TCP_LISTEN"].orEmpty()
        val portListening = tcpListenRaw.lineSequence().any { it.contains(":705D", ignoreCase = true) }

        val gmsStatusRawLines = sections["GMS_STATUS"].orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val gmsDisabledFile = gmsStatusRawLines.any { it == "gms_disabled=1" }
        val gmsStatusFile = sanitizeGmsStatusFile(gmsStatusRawLines)
        val gmsProcesses = sanitizeGmsProcs(sections["GMS_PROCS"].orEmpty())
        val gmsHookPidAlive = sections["GMS_RESIDENCY"].orEmpty()
            .lineSequence()
            .any { it.trim() == "hook_pid_alive=1" }

        val sensorMapsSanitized = sanitizeSensorMaps(sections["SENSOR_MAPS"].orEmpty())
        val (sensorInstances, sensorVmaCount) = parseSensorMapStats(sensorMapsSanitized)

        val rawDaemonLog = sections["DAEMON_LOG"].orEmpty().trimEnd()
        val redactedDaemonLog = redactBase(rawDaemonLog, cleanKeys)

        val filteredLogcatLines = filterWhitelistedLogcat(sections["LOGCAT"].orEmpty())
        val avcCount = filteredLogcatLines.count { isEngineRelevantAvc(it) }
        val redactedLogcat = redactBase(filteredLogcatLines.joinToString("\n"), cleanKeys)

        val filteredLsposedLines = filterWhitelistedLsposed(sections["LSPOSED_LOG"].orEmpty())
        val redactedLsposed = redactBase(filteredLsposedLines.joinToString("\n"), cleanKeys)
        val lsposedSensorHook = extractLsposedSensorHook(redactedLsposed)

        val redactedAppEvents = redactBase(AppLogBuffer.dumpText(errorsOnly = false), cleanKeys)

        val redactedDaemonSnap = snap.copy(
            message = redactBase(snap.message, cleanKeys),
            status = snap.status.copy(
                sensorDiag = redactBase(snap.status.sensorDiag, cleanKeys),
                gmsDiag = redactBase(snap.status.gmsDiag, cleanKeys)
            )
        )

        DiagnosticSnapshot(
            collectedAtMs = nowMs,
            collectedAtClock = clockStr,
            collectedAtIsoUtc = isoUtcStr,
            rootAvailable = rootResult.success,
            rootError = redactBase(rootResult.error, cleanKeys),
            deviceInfo = device,
            appVersionName = KurokoProtocol.APP_VERSION_NAME,
            appVersionCode = KurokoProtocol.APP_VERSION_CODE,
            appProtoVer = KurokoProtocol.IPC_PROTOCOL_VERSION,
            appShmVer = KurokoProtocol.SHM_SCHEMA_VERSION,
            appLangTag = appLangTag,
            daemonSnap = redactedDaemonSnap,
            versionCompat = compat,
            motion = motion,
            overlayPrefs = overlayPrefs,
            savedTargets = savedTargets.toList(),
            moduleProp = redactBase(sections["MOD_PROP"].orEmpty().trim(), cleanKeys),
            moduleDisabled = modDisabled,
            moduleRemovePending = modRemove,
            moduleUpdatePending = modUpdate,
            bootGuardCount = bootGuardCount,
            bootGuardRaw = bootGuardRaw,
            persistedLang = redactBase(sections["PERSIST_LANG"].orEmpty().trim(), cleanKeys),
            apkInstalledProp = redactBase(sections["APK_PROP"].orEmpty().trim(), cleanKeys),
            daemonPids = sections["DAEMON_PID"].orEmpty().trim(),
            portListening = portListening,
            gmsDisabledFile = gmsDisabledFile,
            gmsStatusFile = redactBase(gmsStatusFile, cleanKeys),
            gmsProcesses = gmsProcesses,
            gmsHookPidAlive = gmsHookPidAlive,
            lsposedSensorHook = lsposedSensorHook,
            sensorServiceInstances = sensorInstances,
            sensorServiceVmaCount = sensorVmaCount,
            sensorServiceMaps = redactBase(sensorMapsSanitized, cleanKeys),
            daemonLog = redactedDaemonLog,
            lsposedLog = redactedLsposed,
            systemLogcat = redactedLogcat,
            selinuxAvcCount = avcCount,
            appEventsLog = redactedAppEvents
        )
    }

    fun evaluateHealth(snapshot: DiagnosticSnapshot, context: Context): List<HealthCheckItem> {
        val ctx = AppLocaleManager.localizedContext(context)
        val st = snapshot.daemonSnap.status
        val connected = snapshot.daemonSnap.connected
        val compat = snapshot.versionCompat

        // 1. Root & Daemon
        val daemonCheck = when {
            snapshot.bootGuardCount >= 2 -> HealthCheckItem(
                id = "daemon",
                title = ctx.getString(R.string.diag_check_daemon_title),
                status = HealthStatus.FAIL,
                detail = ctx.getString(R.string.diag_check_daemon_bootguard, snapshot.bootGuardCount)
            )
            snapshot.moduleDisabled -> HealthCheckItem(
                id = "daemon",
                title = ctx.getString(R.string.diag_check_daemon_title),
                status = HealthStatus.FAIL,
                detail = ctx.getString(R.string.diag_check_daemon_disabled)
            )
            !snapshot.rootAvailable && !connected -> HealthCheckItem(
                id = "daemon",
                title = ctx.getString(R.string.diag_check_daemon_title),
                status = HealthStatus.FAIL,
                detail = ctx.getString(R.string.diag_check_daemon_no_root)
            )
            connected -> HealthCheckItem(
                id = "daemon",
                title = ctx.getString(R.string.diag_check_daemon_title),
                status = HealthStatus.PASS,
                detail = ctx.getString(R.string.diag_check_daemon_ok)
            )
            else -> HealthCheckItem(
                id = "daemon",
                title = ctx.getString(R.string.diag_check_daemon_title),
                status = HealthStatus.FAIL,
                detail = ctx.getString(
                    R.string.diag_check_daemon_offline,
                    snapshot.daemonSnap.message.ifBlank { "offline" }
                )
            )
        }

        // 2. 4-Tier Version Lock
        val versionCheck = when {
            !connected -> HealthCheckItem(
                id = "version",
                title = ctx.getString(R.string.diag_check_version_title),
                status = HealthStatus.WARN,
                detail = ctx.getString(
                    R.string.diag_check_daemon_offline,
                    snapshot.daemonSnap.message.ifBlank { "offline" }
                )
            )
            compat.state == VersionCompatState.MATCHED -> HealthCheckItem(
                id = "version",
                title = ctx.getString(R.string.diag_check_version_title),
                status = HealthStatus.PASS,
                detail = ctx.getString(
                    R.string.diag_check_version_ok,
                    snapshot.appVersionName,
                    snapshot.appVersionCode,
                    st.protoVer,
                    st.shmVer
                )
            )
            compat.state == VersionCompatState.REBOOT_REQUIRED -> HealthCheckItem(
                id = "version",
                title = ctx.getString(R.string.diag_check_version_title),
                status = HealthStatus.WARN,
                detail = ctx.getString(R.string.diag_check_version_reboot)
            )
            else -> HealthCheckItem(
                id = "version",
                title = ctx.getString(R.string.diag_check_version_title),
                status = HealthStatus.FAIL,
                detail = ctx.getString(
                    R.string.diag_check_version_mismatch,
                    snapshot.appVersionCode,
                    st.xposedCode,
                    st.modCode,
                    st.zygiskCode
                )
            )
        }

        // 3. LSPosed Hooks (L / P)
        val xposedCheck = if (connected && st.locShm && st.phoneShm) {
            HealthCheckItem(
                id = "xposed",
                title = ctx.getString(R.string.diag_check_xposed_title),
                status = HealthStatus.PASS,
                detail = ctx.getString(R.string.diag_check_xposed_ok)
            )
        } else {
            val missingFlags = when {
                !connected -> "L? / P?"
                !st.locShm && !st.phoneShm -> "L× / P×"
                !st.locShm -> "L×"
                else -> "P×"
            }
            val severity = if (connected && !st.locShm) HealthStatus.FAIL else HealthStatus.WARN
            HealthCheckItem(
                id = "xposed",
                title = ctx.getString(R.string.diag_check_xposed_title),
                status = severity,
                detail = ctx.getString(R.string.diag_check_xposed_missing, missingFlags)
            )
        }

        // 4. Zygisk Sensor Hook (S)
        val sensorDiagRaw = st.sensorDiag
        val sensorHookRaw = snapshot.lsposedSensorHook
        val parsedMp = DIAG_MP_REGEX.find(sensorDiagRaw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: HOOK_MAPS_REGEX.find(sensorHookRaw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: snapshot.sensorServiceInstances.takeIf { it > 1 }
        val parsedCore = DIAG_C_REGEX.find(sensorDiagRaw)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: HOOK_CORE_REGEX.find(sensorHookRaw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val parsedDisc = DIAG_D_REGEX.find(sensorDiagRaw)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val parsedMode = DIAG_M_REGEX.find(sensorDiagRaw)?.groupValues?.getOrNull(1)
        val sensorHookErr = sensorHookRaw.startsWith("err:", ignoreCase = true)

        val sensorSummaryLabel = buildString {
            if (!parsedMode.isNullOrBlank()) append("m=$parsedMode")
            if (parsedMp != null) {
                if (isNotEmpty()) append(", ")
                append("mp=$parsedMp")
            }
            if (parsedCore != null) {
                if (isNotEmpty()) append(", ")
                append("c=$parsedCore/5")
            }
            if (parsedDisc != null) {
                if (isNotEmpty()) append(", ")
                append("d=$parsedDisc/3")
            }
            if (isEmpty()) {
                append(sensorDiagRaw.ifBlank { "S✓" }.take(40))
            }
        }

        val sensorCheck = when {
            parsedMp != null && parsedMp > 1 -> HealthCheckItem(
                id = "sensor",
                title = ctx.getString(R.string.diag_check_sensor_title),
                status = HealthStatus.WARN,
                detail = ctx.getString(R.string.diag_check_sensor_dual_map, parsedMp)
            )
            (parsedCore != null && parsedCore < 3 && (st.sensorShm || (!parsedMode.isNullOrBlank() && parsedMode != "wait"))) ||
                (sensorHookErr && !sensorHookRaw.contains("boot_guard", ignoreCase = true)) -> HealthCheckItem(
                id = "sensor",
                title = ctx.getString(R.string.diag_check_sensor_title),
                status = HealthStatus.FAIL,
                detail = ctx.getString(
                    R.string.diag_check_sensor_abi,
                    snapshot.deviceInfo.androidRelease.ifBlank { "?" },
                    snapshot.deviceInfo.sdkInt
                )
            )
            connected && st.sensorShm -> HealthCheckItem(
                id = "sensor",
                title = ctx.getString(R.string.diag_check_sensor_title),
                status = HealthStatus.PASS,
                detail = ctx.getString(R.string.diag_check_sensor_ok, sensorSummaryLabel)
            )
            !connected && sensorHookRaw.startsWith("ok:", ignoreCase = true) -> HealthCheckItem(
                id = "sensor",
                title = ctx.getString(R.string.diag_check_sensor_title),
                status = HealthStatus.PASS,
                detail = ctx.getString(R.string.diag_check_sensor_ok, sensorSummaryLabel)
            )
            else -> HealthCheckItem(
                id = "sensor",
                title = ctx.getString(R.string.diag_check_sensor_title),
                status = HealthStatus.WARN,
                detail = ctx.getString(R.string.diag_check_sensor_inactive)
            )
        }

        // 5. Optional LSPosed Bluetooth Hook (B)
        val anyBleTarget = snapshot.savedTargets.any { t ->
            (t.policyFlags and (KurokoProtocol.POLICY_HIDE_BLE or KurokoProtocol.POLICY_SPOOF_BLE)) != 0
        }
        val btCheck = when {
            connected && st.btShm -> HealthCheckItem(
                id = "bluetooth",
                title = ctx.getString(R.string.diag_check_bt_title),
                status = HealthStatus.PASS,
                detail = ctx.getString(R.string.diag_check_bt_ok)
            )
            !anyBleTarget -> HealthCheckItem(
                id = "bluetooth",
                title = ctx.getString(R.string.diag_check_bt_title),
                status = HealthStatus.PASS,
                detail = ctx.getString(R.string.diag_check_bt_unused)
            )
            else -> HealthCheckItem(
                id = "bluetooth",
                title = ctx.getString(R.string.diag_check_bt_title),
                status = HealthStatus.WARN,
                detail = ctx.getString(R.string.diag_check_bt_missing)
            )
        }

        // 6. Optional GMS Fused Location Hook (G)
        val gmsEnabledEffective = if (connected) st.gmsEnabled else !snapshot.gmsDisabledFile
        val gmsShmEffective = connected && st.gmsShm
        val gmsDiagEffective = st.gmsDiag.ifBlank { snapshot.gmsStatusFile }
        val gmsHostRunning = snapshot.gmsProcesses.any { isGmsFlpHostProcess(it) }
        val gmsPendingRestart = gmsDiagEffective.equals("disabled_pending_restart", ignoreCase = true) ||
            snapshot.gmsHookPidAlive
        val gmsCheck = when {
            !gmsEnabledEffective -> if (gmsPendingRestart) {
                HealthCheckItem(
                    id = "gms",
                    title = ctx.getString(R.string.diag_check_gms_title),
                    status = HealthStatus.WARN,
                    detail = ctx.getString(R.string.diag_check_gms_disabled_pending)
                )
            } else {
                HealthCheckItem(
                    id = "gms",
                    title = ctx.getString(R.string.diag_check_gms_title),
                    status = HealthStatus.PASS,
                    detail = ctx.getString(R.string.diag_check_gms_disabled)
                )
            }
            gmsShmEffective -> HealthCheckItem(
                id = "gms",
                title = ctx.getString(R.string.diag_check_gms_title),
                status = HealthStatus.PASS,
                detail = ctx.getString(R.string.diag_check_gms_ok, gmsDiagEffective.ifBlank { "G✓" })
            )
            gmsDiagEffective.startsWith("err:", ignoreCase = true) -> HealthCheckItem(
                id = "gms",
                title = ctx.getString(R.string.diag_check_gms_title),
                status = HealthStatus.WARN,
                detail = ctx.getString(R.string.diag_check_gms_warn, gmsDiagEffective)
            )
            gmsDiagEffective.equals("stale_daemon_restart", ignoreCase = true) -> HealthCheckItem(
                id = "gms",
                title = ctx.getString(R.string.diag_check_gms_title),
                status = HealthStatus.WARN,
                detail = ctx.getString(R.string.diag_check_gms_warn, "stale_daemon_restart")
            )
            !gmsHostRunning -> HealthCheckItem(
                id = "gms",
                title = ctx.getString(R.string.diag_check_gms_title),
                status = HealthStatus.PASS,
                detail = ctx.getString(R.string.diag_check_gms_not_installed)
            )
            else -> HealthCheckItem(
                id = "gms",
                title = ctx.getString(R.string.diag_check_gms_title),
                status = HealthStatus.WARN,
                detail = ctx.getString(R.string.diag_check_gms_warn, gmsDiagEffective.ifBlank { "not loaded" })
            )
        }

        // 7. SELinux Policy
        val selinuxCheck = if (snapshot.selinuxAvcCount == 0) {
            HealthCheckItem(
                id = "selinux",
                title = ctx.getString(R.string.diag_check_selinux_title),
                status = HealthStatus.PASS,
                detail = ctx.getString(R.string.diag_check_selinux_ok)
            )
        } else {
            HealthCheckItem(
                id = "selinux",
                title = ctx.getString(R.string.diag_check_selinux_title),
                status = HealthStatus.WARN,
                detail = ctx.getString(R.string.diag_check_selinux_avc, snapshot.selinuxAvcCount)
            )
        }

        return listOf(daemonCheck, versionCheck, xposedCheck, sensorCheck, btCheck, gmsCheck, selinuxCheck)
    }

    fun buildPackageAliasMap(savedTargets: List<SavedTargetApp>): Map<String, String> {
        val sortedPkgs = savedTargets
            .map { it.packageName.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
        val map = LinkedHashMap<String, String>(sortedPkgs.size)
        sortedPkgs.forEachIndexed { idx, pkg ->
            map[pkg] = "target_${idx + 1}"
        }
        return map
    }

    fun applyPackageAnonymization(
        text: String,
        savedTargets: List<SavedTargetApp>,
        anonymizePackages: Boolean
    ): String {
        if (!anonymizePackages || text.isEmpty() || savedTargets.isEmpty()) return text
        val aliasMap = buildPackageAliasMap(savedTargets)
        if (aliasMap.isEmpty()) return text
        var out = text
        // Replace longest package names first to prevent partial prefix collisions
        for ((pkg, alias) in aliasMap.entries.sortedByDescending { it.key.length }) {
            out = out.replace(pkg, alias)
        }
        return out
    }

    fun filterWarnErrorLines(rawText: String, isLogcat: Boolean = false): String {
        if (rawText.isBlank()) return ""
        return rawText.lineSequence()
            .filter { line ->
                if (isLogcat) {
                    if (AVC_DENIED_REGEX.containsMatchIn(line)) {
                        isEngineRelevantAvc(line)
                    } else {
                        val parsed = THREADTIME_LINE_REGEX.find(line)
                        if (parsed != null) {
                            val level = parsed.groupValues[2]
                            level == "W" || level == "E" || level == "F" || level == "A"
                        } else {
                            !line.startsWith("#") && GENERIC_WARN_ERR_REGEX.containsMatchIn(line)
                        }
                    }
                } else {
                    GENERIC_WARN_ERR_REGEX.containsMatchIn(line)
                }
            }
            .joinToString("\n")
    }

    fun buildClipboardSummary(
        snapshot: DiagnosticSnapshot,
        context: Context,
        anonymizePackages: Boolean
    ): String {
        val ctx = AppLocaleManager.localizedContext(context)
        val checks = evaluateHealth(snapshot, context)
        val st = snapshot.daemonSnap.status
        val dev = snapshot.deviceInfo
        val aliasMap = if (anonymizePackages) buildPackageAliasMap(snapshot.savedTargets) else emptyMap()
        val gmsEnabledEffective = if (snapshot.daemonSnap.connected) st.gmsEnabled else !snapshot.gmsDisabledFile
        val gmsPendingRestart = !gmsEnabledEffective &&
            (st.gmsDiag.equals("disabled_pending_restart", ignoreCase = true) || snapshot.gmsHookPidAlive)
        val gmsDiagEffective = when {
            gmsPendingRestart -> "disabled_pending_restart"
            !gmsEnabledEffective -> "disabled"
            else -> st.gmsDiag.ifBlank { snapshot.gmsStatusFile }.ifBlank { "none" }
        }
        val gmsStateLabel = when {
            gmsPendingRestart -> "OFF(pending)"
            !gmsEnabledEffective -> "OFF"
            snapshot.daemonSnap.connected && st.gmsShm -> "G✓"
            else -> "G-"
        }

        val sb = StringBuilder(2048)
        sb.appendLine("## Kuroko Teleport Diagnostics Summary")
        sb.appendLine("- **Timestamp (UTC)**: `${snapshot.collectedAtIsoUtc}`")
        sb.appendLine(
            "- **App**: `v${snapshot.appVersionName} (#${snapshot.appVersionCode})` · " +
                "`P${snapshot.appProtoVer}` · `SHM${snapshot.appShmVer}` · `lang=${snapshot.appLangTag}`"
        )
        sb.appendLine(
            "- **Device**: `${dev.manufacturer} ${dev.model}` (`${dev.brand}`) · " +
                "`Android ${dev.androidRelease} (API ${dev.sdkInt})` · ABIs: `${dev.abis}`"
        )
        sb.appendLine("- **Build / Kernel**: `${dev.display}` (`${dev.incremental}`) · `${dev.kernelVersion}`")
        sb.appendLine(
            "- **Root / Daemon**: root=`${snapshot.rootAvailable}` · connected=`${snapshot.daemonSnap.connected}` · " +
                "pid=`${snapshot.daemonPids.ifBlank { "none" }}` · port28765=`${snapshot.portListening}` · " +
                "boot_guard=`${snapshot.bootGuardCount}` · disabled=`${snapshot.moduleDisabled}`"
        )
        sb.appendLine(
            "- **4-Tier Lock**: state=`${snapshot.versionCompat.state}` · " +
                "App(`#${snapshot.appVersionCode}`) / Hook(`#${st.xposedCode}`) / " +
                "Daemon(`v${st.modVer.ifBlank { "?" }} #${st.modCode}`) / Sensor(`#${st.zygiskCode}`) · " +
                "SHM(`L=${if (st.locShm) "OK" else "NO"}, P=${if (st.phoneShm) "OK" else "NO"}, S=${if (st.sensorShm) "OK" else "NO"}, B=${if (st.btShm) "OK" else "OPT"}, G=$gmsStateLabel`)"
        )
        val diagEffective = st.sensorDiag.ifBlank { "none" }
        val hookSegment = if (snapshot.lsposedSensorHook.isNotBlank()) {
            " · hook=`${snapshot.lsposedSensorHook}`"
        } else {
            ""
        }
        sb.appendLine(
            "- **Sensor Hook**: diag=`$diagEffective`$hookSegment · " +
                "libsensorservice=`${snapshot.sensorServiceInstances} instance(s) (${snapshot.sensorServiceVmaCount} VMAs)`"
        )
        sb.appendLine(
            "- **RF Synthesis**: country=`${st.rfCountry.ifBlank { "none" }}` · " +
                "plmn=`${st.rfMcc}-${st.rfMnc}` · cells=`${st.rfCells}` · wifis=`${st.rfWifis}` · bles=`${st.rfBles}` · " +
                "serving=`${st.rfServing.ifBlank { "none" }}`"
        )
        sb.appendLine("- **${ctx.getString(R.string.diag_summary_gms, gmsStateLabel, gmsDiagEffective)}**")
        sb.appendLine(
            "- **SELinux / Targets**: avc_denials=`${snapshot.selinuxAvcCount}` · " +
                "configured_targets=`${snapshot.savedTargets.size}` (daemon_active=`${st.targetCount}`) · " +
                "hud_enabled=`${snapshot.overlayPrefs.enabled}`"
        )
        if (snapshot.savedTargets.isNotEmpty()) {
            val targetsLine = snapshot.savedTargets.take(12).joinToString(", ") { t ->
                val label = aliasMap[t.packageName] ?: t.packageName
                "$label(0x${t.policyFlags.toString(16)})"
            }
            sb.appendLine("- **Targets**: `$targetsLine`")
        }
        sb.appendLine()
        sb.appendLine("### Auto Health Check")
        for (item in checks) {
            val badge = when (item.status) {
                HealthStatus.PASS -> "PASS"
                HealthStatus.WARN -> "WARN"
                HealthStatus.FAIL -> "FAIL"
            }
            sb.appendLine("- [$badge] **${item.title}**: ${item.detail}")
        }

        val recentErrors = buildList {
            filterWarnErrorLines(snapshot.appEventsLog).lineSequence().filter { it.isNotBlank() }.toList()
                .takeLast(3).forEach { add("app: $it") }
            filterWarnErrorLines(snapshot.daemonLog).lineSequence().filter { it.isNotBlank() }.toList()
                .takeLast(3).forEach { add("daemon: $it") }
            filterWarnErrorLines(snapshot.systemLogcat, isLogcat = true).lineSequence().filter { it.isNotBlank() }.toList()
                .takeLast(3).forEach { add("sys: $it") }
        }.takeLast(6)

        if (recentErrors.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("### Recent Warnings / Errors")
            sb.appendLine("```text")
            for (line in recentErrors) {
                sb.appendLine(line.take(220))
            }
            sb.appendLine("```")
        }

        sb.appendLine()
        sb.append("_Redacted automatically: auth tokens, API keys, routes, coordinates, MACs, and unrelated app logs are excluded._")

        val raw = sb.toString().replace("\r\n", "\n")
        return applyPackageAnonymization(redactBase(raw, emptyList()), snapshot.savedTargets, anonymizePackages)
    }

    fun buildRuntimeStateJson(
        snapshot: DiagnosticSnapshot,
        context: Context,
        anonymizePackages: Boolean
    ): String {
        val checks = evaluateHealth(snapshot, context)
        val st = snapshot.daemonSnap.status
        val dev = snapshot.deviceInfo
        val aliasMap = if (anonymizePackages) buildPackageAliasMap(snapshot.savedTargets) else emptyMap()

        val root = JSONObject().apply {
            put("schema", "kuroko_diagnostics_v1")
            put("collectedAtUtc", snapshot.collectedAtIsoUtc)
            put("anonymizePackages", anonymizePackages)
            put("rootAvailable", snapshot.rootAvailable)
            if (snapshot.rootError.isNotBlank()) {
                put("rootError", snapshot.rootError)
            }
            put("app", JSONObject().apply {
                put("versionName", snapshot.appVersionName)
                put("versionCode", snapshot.appVersionCode)
                put("ipcProtocolVersion", snapshot.appProtoVer)
                put("shmSchemaVersion", snapshot.appShmVer)
                put("languageTag", snapshot.appLangTag)
            })
            put("device", JSONObject().apply {
                put("brand", dev.brand)
                put("manufacturer", dev.manufacturer)
                put("model", dev.model)
                put("androidRelease", dev.androidRelease)
                put("sdkInt", dev.sdkInt)
                put("abis", dev.abis)
                put("display", dev.display)
                put("incremental", dev.incremental)
                put("kernelVersion", dev.kernelVersion)
            })
            put("module", JSONObject().apply {
                put("disabled", snapshot.moduleDisabled)
                put("removePending", snapshot.moduleRemovePending)
                put("updatePending", snapshot.moduleUpdatePending)
                put("bootGuardCount", snapshot.bootGuardCount)
                put("persistedLang", snapshot.persistedLang)
                put("daemonPids", snapshot.daemonPids)
                put("port28765Listening", snapshot.portListening)
                put("moduleProp", snapshot.moduleProp)
                put("apkInstalledProp", snapshot.apkInstalledProp)
                put("gmsDisabledFile", snapshot.gmsDisabledFile)
                put("gmsStatusFile", snapshot.gmsStatusFile)
                put("gmsHookPidAlive", snapshot.gmsHookPidAlive)
            })
            put("daemon", JSONObject().apply {
                put("connected", snapshot.daemonSnap.connected)
                put("message", snapshot.daemonSnap.message)
                put("active", st.active)
                put("moving", st.moving)
                put("loop", st.loop)
                put("motionMode", st.motionMode)
                put("carryMode", st.carryMode)
                put("targetCount", st.targetCount)
                put("locShm", st.locShm)
                put("phoneShm", st.phoneShm)
                put("sensorShm", st.sensorShm)
                put("btShm", st.btShm)
                put("gmsShm", st.gmsShm)
                put("gmsEnabled", st.gmsEnabled)
                put("gmsDiag", st.gmsDiag)
                put("modVer", st.modVer)
                put("modCode", st.modCode)
                put("protoVer", st.protoVer)
                put("shmVer", st.shmVer)
                put("minAppCode", st.minAppCode)
                put("xposedCode", st.xposedCode)
                put("zygiskCode", st.zygiskCode)
                put("sensorDiag", st.sensorDiag)
                put("rfCountry", st.rfCountry)
                put("rfMcc", st.rfMcc)
                put("rfMnc", st.rfMnc)
                put("rfCells", st.rfCells)
                put("rfWifis", st.rfWifis)
                put("rfBles", st.rfBles)
                put("rfServing", st.rfServing)
                if (snapshot.lsposedSensorHook.isNotBlank()) {
                    put("lsposedSensorHook", snapshot.lsposedSensorHook)
                }
            })
            put("versionCompat", JSONObject().apply {
                put("state", snapshot.versionCompat.state.name)
                put("canStartSimulation", snapshot.versionCompat.canStartSimulation)
                put("daemonOk", snapshot.versionCompat.daemonOk)
                put("protocolOk", snapshot.versionCompat.protocolOk)
                put("xposedOk", snapshot.versionCompat.xposedOk)
                put("zygiskOk", snapshot.versionCompat.zygiskOk)
            })
            put("overlay", JSONObject().apply {
                put("enabled", snapshot.overlayPrefs.enabled)
                put("autoHideInApp", snapshot.overlayPrefs.autoHideInApp)
                put("secureFlag", snapshot.overlayPrefs.secureFlag)
            })
            put("selinuxAvcCount", snapshot.selinuxAvcCount)
            put("sensorServiceInstances", snapshot.sensorServiceInstances)
            put("sensorServiceVmaCount", snapshot.sensorServiceVmaCount)
            put("sensorServiceMaps", JSONArray().apply {
                snapshot.sensorServiceMaps.lineSequence().filter { it.isNotBlank() }.forEach { put(it) }
            })
            put("gmsProcesses", JSONArray().apply {
                snapshot.gmsProcesses.forEach { put(it) }
            })
            put("targets", JSONArray().apply {
                for (t in snapshot.savedTargets) {
                    put(JSONObject().apply {
                        put("package", aliasMap[t.packageName] ?: t.packageName)
                        put("policyFlags", t.policyFlags)
                    })
                }
            })
            put("healthChecks", JSONArray().apply {
                for (c in checks) {
                    put(JSONObject().apply {
                        put("id", c.id)
                        put("title", c.title)
                        put("status", c.status.name)
                        put("detail", c.detail)
                    })
                }
            })
        }
        val raw = root.toString(2).replace("\r\n", "\n") + "\n"
        return applyPackageAnonymization(redactBase(raw, emptyList()), snapshot.savedTargets, anonymizePackages)
    }

    fun buildDiagnosticZipBytes(
        snapshot: DiagnosticSnapshot,
        context: Context,
        anonymizePackages: Boolean
    ): ByteArray {
        val summaryMd = buildClipboardSummary(snapshot, context, anonymizePackages) + "\n"
        val runtimeJson = buildRuntimeStateJson(snapshot, context, anonymizePackages)
        val daemonText = prepareExportLog(snapshot.daemonLog, snapshot.savedTargets, anonymizePackages)
        val lsposedText = prepareExportLog(snapshot.lsposedLog, snapshot.savedTargets, anonymizePackages)
        val systemText = buildSystemLogBundle(snapshot, anonymizePackages)
        val appEventsText = prepareExportLog(snapshot.appEventsLog, snapshot.savedTargets, anonymizePackages)

        val baos = ByteArrayOutputStream(32 * 1024)
        ZipOutputStream(baos).use { zos ->
            writeZipTextEntry(zos, "summary.md", summaryMd)
            writeZipTextEntry(zos, "daemon.log", daemonText)
            writeZipTextEntry(zos, "lsposed_kuroko.log", lsposedText)
            writeZipTextEntry(zos, "system_logcat.log", systemText)
            writeZipTextEntry(zos, "app_events.log", appEventsText)
            writeZipTextEntry(zos, "runtime_state.json", runtimeJson)
        }
        return baos.toByteArray()
    }

    fun suggestedZipFileName(collectedAtMs: Long = System.currentTimeMillis()): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(collectedAtMs))
        return "kuroko-diag-$stamp.zip"
    }

    fun createShareableZipUri(context: Context, zipBytes: ByteArray, collectedAtMs: Long = System.currentTimeMillis()): Uri {
        val dir = File(context.cacheDir, "diagnostics")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val cutoff = System.currentTimeMillis() - CACHE_PRUNE_AGE_MS
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < cutoff) {
                runCatching { f.delete() }
            }
        }
        val outFile = File(dir, suggestedZipFileName(collectedAtMs))
        outFile.writeBytes(zipBytes)
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            outFile
        )
    }

    fun formatByteSize(bytes: Int): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
        }
    }

    fun buildSystemTabText(snapshot: DiagnosticSnapshot, anonymizePackages: Boolean): String {
        if (
            snapshot.daemonSnap.status.sensorDiag.isBlank() &&
            snapshot.lsposedSensorHook.isBlank() &&
            snapshot.sensorServiceMaps.isBlank() &&
            snapshot.systemLogcat.isBlank() &&
            snapshot.daemonSnap.status.gmsDiag.isBlank() &&
            snapshot.gmsStatusFile.isBlank() &&
            !snapshot.gmsHookPidAlive &&
            snapshot.gmsProcesses.isEmpty()
        ) {
            return ""
        }
        return buildSystemLogBundle(snapshot, anonymizePackages).trimEnd()
    }

    private fun buildSystemLogBundle(snapshot: DiagnosticSnapshot, anonymizePackages: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("# STATUS.sensorDiag: ${snapshot.daemonSnap.status.sensorDiag.ifBlank { "(empty)" }}")
        if (snapshot.lsposedSensorHook.isNotBlank()) {
            sb.appendLine("# lsposed kuroko.sensor.hook: ${snapshot.lsposedSensorHook}")
        }
        val gmsEnabledEff = if (snapshot.daemonSnap.connected) snapshot.daemonSnap.status.gmsEnabled else !snapshot.gmsDisabledFile
        val gmsPendingRestart = !gmsEnabledEff &&
            (snapshot.daemonSnap.status.gmsDiag.equals("disabled_pending_restart", ignoreCase = true) || snapshot.gmsHookPidAlive)
        val gmsDiagEff = when {
            gmsPendingRestart -> "disabled_pending_restart"
            !gmsEnabledEff -> "disabled"
            else -> snapshot.daemonSnap.status.gmsDiag.ifBlank { snapshot.gmsStatusFile }.ifBlank { "(empty)" }
        }
        sb.appendLine(
            "# GMS FLP: enabled=$gmsEnabledEff shm=${snapshot.daemonSnap.status.gmsShm} " +
                "hookPidAlive=${snapshot.gmsHookPidAlive} diag=$gmsDiagEff"
        )
        if (snapshot.gmsProcesses.isNotEmpty()) {
            sb.appendLine("# GMS processes: ${snapshot.gmsProcesses.joinToString(", ")}")
        }
        sb.appendLine(
            "# libsensorservice.so: ${snapshot.sensorServiceInstances} instance(s) (${snapshot.sensorServiceVmaCount} VMAs)"
        )
        if (snapshot.sensorServiceMaps.isNotBlank()) {
            sb.appendLine(snapshot.sensorServiceMaps)
        }
        if (snapshot.systemLogcat.isNotBlank()) {
            sb.appendLine()
            sb.appendLine("# Filtered logcat (Kuroko tags + related SELinux AVC):")
            sb.appendLine(snapshot.systemLogcat)
        }
        return prepareExportLog(sb.toString(), snapshot.savedTargets, anonymizePackages)
    }

    private fun prepareExportLog(
        raw: String,
        savedTargets: List<SavedTargetApp>,
        anonymizePackages: Boolean
    ): String {
        val normalized = raw.replace("\r\n", "\n").trimEnd()
        val anonymized = applyPackageAnonymization(
            redactBase(normalized, emptyList()),
            savedTargets,
            anonymizePackages
        )
        return if (anonymized.isEmpty()) "(empty)\n" else "$anonymized\n"
    }

    private fun writeZipTextEntry(zos: ZipOutputStream, name: String, content: String) {
        val entry = ZipEntry(name)
        zos.putNextEntry(entry)
        zos.write(content.replace("\r\n", "\n").toByteArray(StandardCharsets.UTF_8))
        zos.closeEntry()
    }

    internal fun redactBase(input: String, apiKeysToScrub: List<String>): String {
        if (input.isEmpty()) return input
        var out = input
        for (key in apiKeysToScrub) {
            if (key.length >= 3) {
                out = out.replace(key, "<REDACTED_KEY>")
            }
        }
        out = AUTH_HEX_REGEX.replace(out, "AUTH <REDACTED>")
        out = TOKEN_KV_REGEX.replace(out, "token=<REDACTED>")
        out = URL_KEY_REGEX.replace(out, "\$1<REDACTED_KEY>")
        out = SET_POINT_REGEX.replace(out, "SET_POINT <REDACTED_COORD>")
        out = SET_ROUTE_REGEX.replace(out, "SET_ROUTE <REDACTED_COORD>")
        out = SET_RF_PIN_REGEX.replace(out, "SET_RF <REDACTED_COORD>")
        out = NMEA_REGEX.replace(out, "<REDACTED_COORD>")
        out = KEYED_COORD_REGEX.replace(out, "\$1\$2<REDACTED_COORD>")
        out = BARE_COORD_PAIR_REGEX.replace(out, "<REDACTED_COORD>")
        out = MAC_ADDR_REGEX.replace(out, "<REDACTED_MAC>")
        out = EMAIL_REGEX.replace(out) { m ->
            val v = m.value.lowercase(Locale.US)
            if (v.endsWith("@example.com") || v.endsWith("@localhost")) m.value else "<REDACTED_EMAIL>"
        }
        return out
    }

    internal fun isEngineRelevantAvc(line: String): Boolean {
        if (!AVC_DENIED_REGEX.containsMatchIn(line)) return false
        if (AVC_UI_SANDBOX_SRC_REGEX.containsMatchIn(line) &&
            AVC_UI_GPU_NOISE_TARGET_REGEX.containsMatchIn(line)
        ) {
            return false
        }
        if (AVC_GMS_SCONTEXT_REGEX.containsMatchIn(line)) {
            return AVC_GMS_SUBJECT_REGEX.containsMatchIn(line)
        }
        if (AVC_DIRECT_TARGET_REGEX.containsMatchIn(line)) {
            return true
        }
        return AVC_ENGINE_SCONTEXT_REGEX.containsMatchIn(line) &&
            AVC_ENGINE_SUBJECT_REGEX.containsMatchIn(line)
    }

    private fun isNoisyUiOemTag(tag: String): Boolean {
        val t = tag.trim()
        if (t in NOISY_UI_OEM_TAGS) return true
        return NOISY_UI_OEM_TAGS.any { noisy ->
            t.startsWith("$noisy[") || t.startsWith("$noisy-") || t.startsWith("$noisy:")
        }
    }

    internal fun isWhitelistedLogcatLine(line: String, selfCrashPids: Set<String> = emptySet()): Boolean {
        if (line.isBlank() || line.startsWith("---------")) return false

        // 1. SELinux AVC lines MUST pass strict engine-relevance filter (never short-circuit via com.kuroko.teleport)
        if (AVC_DENIED_REGEX.containsMatchIn(line)) {
            return isEngineRelevantAvc(line)
        }

        // 2. Always keep native engine tags and Kuroko-* Xposed tags
        if (line.contains("KurokoDaemon") ||
            line.contains("KurokoZygisk") ||
            line.contains("KurokoSensorHook") ||
            line.contains("KurokoGmsHook") ||
            line.contains("Kuroko-")
        ) {
            return true
        }

        // 3. Own-package lines and AndroidRuntime / FATAL EXCEPTION crashes belonging to com.kuroko.teleport
        val parsed = THREADTIME_LINE_REGEX.find(line)
        val pid = parsed?.groupValues?.getOrNull(1)
        val mentionsSelf = line.contains("com.kuroko.teleport")
        val isOwnCrashLine = (line.contains("FATAL EXCEPTION") || line.contains("AndroidRuntime")) &&
            (mentionsSelf || (pid != null && pid in selfCrashPids))
        if (!mentionsSelf && !isOwnCrashLine) return false

        if (parsed != null) {
            val level = parsed.groupValues[2]
            val tag = parsed.groupValues[3].trim()
            if (isNoisyUiOemTag(tag)) return false
            if (isOwnCrashLine || tag == "AndroidRuntime") return true
            return level == "W" || level == "E" || level == "F" || level == "A"
        }

        return isOwnCrashLine ||
            line.contains("Exception") ||
            line.contains("Error") ||
            line.trimStart().startsWith("at com.kuroko.teleport")
    }

    private fun filterWhitelistedLogcat(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        val rawLines = raw.lineSequence().map { it.trimEnd('\r') }.toList()
        val selfCrashPids = HashSet<String>()
        for (line in rawLines) {
            if (line.contains("AndroidRuntime") && line.contains("com.kuroko.teleport")) {
                THREADTIME_LINE_REGEX.find(line)?.groupValues?.getOrNull(1)?.let { selfCrashPids.add(it) }
            }
        }
        val seen = LinkedHashSet<String>()
        for (line in rawLines) {
            if (isWhitelistedLogcatLine(line, selfCrashPids)) {
                seen.add(line)
            }
        }
        return seen.toList()
    }

    internal fun isWhitelistedLsposedLine(line: String): Boolean {
        if (line.isBlank()) return false
        if (line.contains("[com.kuroko.teleport,")) return true
        if (!line.contains("Kuroko-")) return false
        val headerMatch = FOREIGN_LSPOSED_HEADER_REGEX.find(line)
        if (headerMatch != null && headerMatch.groupValues[1] != "com.kuroko.teleport") {
            return false
        }
        return true
    }

    private fun filterWhitelistedLsposed(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { isWhitelistedLsposedLine(it) }
            .toList()
    }

    internal fun extractLsposedSensorHook(lsposedLog: String): String {
        if (lsposedLog.isBlank()) return ""
        return LSPOSED_SENSOR_HOOK_REGEX.findAll(lsposedLog)
            .map { it.groupValues[1].trim().trimEnd(',', ';') }
            .filter {
                it.isNotBlank() &&
                    !it.equals("null", ignoreCase = true) &&
                    !it.equals("none", ignoreCase = true)
            }
            .lastOrNull()
            .orEmpty()
    }

    internal fun parseSensorMapStats(sensorServiceMaps: String): Pair<Int, Int> {
        if (sensorServiceMaps.isBlank()) return 0 to 0
        val lines = sensorServiceMaps.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        val vmaCount = lines.size
        val headerInstances = lines.count { line ->
            val parts = line.split(Regex("\\s+"), limit = 4)
            parts.size >= 3 &&
                parts[1] == "r--p" &&
                parts[2].isNotEmpty() &&
                parts[2].all { it == '0' }
        }
        val effectiveInstances = if (headerInstances == 0 && vmaCount > 0) 1 else headerInstances
        return effectiveInstances to vmaCount
    }

    internal fun sanitizeGmsStatusFile(lines: List<String>): String {
        val explicitStatus = lines.firstOrNull { it.startsWith("status=") }
            ?.removePrefix("status=")
            ?.trim()
        if (!explicitStatus.isNullOrEmpty()) return explicitStatus
        return lines
            .filterNot { line ->
                line.startsWith("gms_disabled=") ||
                    line.startsWith("pid=") ||
                    line.startsWith("uid=") ||
                    line.startsWith("daemon_pid=") ||
                    line.startsWith("shm_ino=")
            }
            .joinToString(" ")
            .trim()
    }

    internal fun isGmsFlpHostProcess(entry: String): Boolean {
        val proc = entry.trim().substringAfterLast(' ')
        return proc == "com.google.android.gms" || proc == "com.google.android.gms.persistent"
    }

    internal fun sanitizeGmsProcs(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split(Regex("\\s+"))
                if (parts.size < 2) return@mapNotNull null
                val domain = parts.first()
                val proc = parts.last()
                val validGmsName = proc == "com.google.android.gms" ||
                    proc.startsWith("com.google.android.gms.")
                if (!domain.startsWith("u:r:") || !validGmsName) {
                    return@mapNotNull null
                }
                "$domain $proc"
            }
            .distinct()
            .take(8)
            .toList()
    }

    private fun sanitizeSensorMaps(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.contains("libsensorservice.so") }
            .mapNotNull { line ->
                // /proc/<pid>/maps format: addr perms offset dev inode pathname
                val parts = line.split(Regex("\\s+"), limit = 6)
                if (parts.size >= 6) {
                    "${parts[0]} ${parts[1]} ${parts[2]} ${parts[5]}"
                } else if (parts.size >= 3) {
                    "${parts[0]} ${parts[1]} ${parts[2]}"
                } else {
                    null
                }
            }
            .joinToString("\n")
    }

    private fun parseSections(stdout: String): Map<String, String> {
        val map = LinkedHashMap<String, StringBuilder>()
        var currentKey: String? = null
        for (rawLine in stdout.lineSequence()) {
            val line = rawLine.trimEnd('\r')
            if (line.startsWith("===") && line.endsWith("===") && line.length > 6) {
                val marker = line.substring(3, line.length - 3)
                if (marker == "END") {
                    currentKey = null
                } else {
                    currentKey = marker
                    map.getOrPut(marker) { StringBuilder() }
                }
                continue
            }
            val key = currentKey ?: continue
            val sb = map.getOrPut(key) { StringBuilder() }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return map.mapValues { it.value.toString() }
    }

    private data class RootRunResult(
        val success: Boolean,
        val stdout: String,
        val error: String
    )

    private fun runRootSnapshotScript(): RootRunResult {
        val process = try {
            ProcessBuilder("su", "-c", ROOT_SNAPSHOT_SCRIPT)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            return RootRunResult(false, "", "${e.javaClass.simpleName}: ${e.message ?: "su failed"}")
        }

        val buffer = ByteArrayOutputStream(32 * 1024)
        val readerThread = Thread {
            runCatching {
                process.inputStream.use { input ->
                    val chunk = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val read = input.read(chunk)
                        if (read <= 0) break
                        val allowed = (MAX_STDOUT_BYTES - total).coerceAtLeast(0)
                        if (allowed > 0) {
                            buffer.write(chunk, 0, minOf(read, allowed))
                            total += minOf(read, allowed)
                        }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "kuroko-diag-reader"
            start()
        }

        val finished = runCatching {
            process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }.getOrDefault(false)

        if (!finished) {
            runCatching { process.destroyForcibly() }
            runCatching { readerThread.join(400) }
            val partial = buffer.toString(StandardCharsets.UTF_8.name())
            return if (partial.contains("===MOD_PROP===")) {
                RootRunResult(true, partial, "root snapshot timed out after ${SHELL_TIMEOUT_SECONDS}s (partial)")
            } else {
                RootRunResult(false, "", "root snapshot timed out after ${SHELL_TIMEOUT_SECONDS}s")
            }
        }

        runCatching { readerThread.join(1000) }
        val stdout = buffer.toString(StandardCharsets.UTF_8.name())
        val exitCode = runCatching { process.exitValue() }.getOrDefault(-1)
        val hasMarkers = stdout.contains("===MOD_PROP===") && stdout.contains("===END===")
        if (!hasMarkers) {
            val detail = stdout.trim().take(180).ifBlank { "exitCode=$exitCode" }
            return RootRunResult(false, "", detail)
        }
        return RootRunResult(true, stdout, "")
    }
}
