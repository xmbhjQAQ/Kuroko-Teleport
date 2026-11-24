package com.kuroko.teleport.ipc

import android.content.Context
import android.os.Process
import com.kuroko.teleport.R
import com.kuroko.teleport.diag.AppLogBuffer
import com.kuroko.teleport.ui.AppLocaleManager
import com.kuroko.teleport.ui.CustomSpeedPreset
import com.kuroko.teleport.ui.RouteStore
import com.kuroko.teleport.ui.defaultSpeedPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

data class DaemonSnapshot(
    val status: SimStatus = SimStatus(),
    val connected: Boolean = false,
    val message: String = "Connecting to 127.0.0.1:${KurokoProtocol.CTRL_PORT} …"
)

data class MotionControlState(
    val speedMps: Float = 2.8f,
    val paceVarRatio: Float = 0.08f,
    val motionMode: Int = KurokoProtocol.MOTION_AUTO,
    val carryMode: Int = KurokoProtocol.CARRY_HAND_SWING
)

data class OverlayPrefsState(
    val enabled: Boolean = false,
    val autoHideInApp: Boolean = true,
    val secureFlag: Boolean = false,
    val sideRight: Boolean = true,
    val yFraction: Float = 0.28f
)

/**
 * Process-wide singleton runtime hub shared by MainActivity and FloatingOverlayService.
 * Holds a single DaemonClient, reference-counted polling, and bidirectional state synchronization
 * so foreground UI and background Floating HUD stay 100% consistent without state fighting.
 */
object KurokoRuntime {
    val daemon = DaemonClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val lock = Any()
    private val observers = mutableSetOf<String>()
    private var pollJob: Job? = null

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var initialTargetsSynced = false

    @Volatile
    private var overlayExpanded = false

    @Volatile
    private var lastLoggedConnected: Boolean? = null

    @Volatile
    private var lastLoggedCompatState: VersionCompatState? = null

    @Volatile
    private var lastLoggedHooksSignature: String? = null

    private val _snapshot = MutableStateFlow(DaemonSnapshot())
    val snapshot: StateFlow<DaemonSnapshot> = _snapshot.asStateFlow()

    private val _versionCompat = MutableStateFlow(VersionCompatInfo())
    val versionCompat: StateFlow<VersionCompatInfo> = _versionCompat.asStateFlow()

    private val _isMainActivityForeground = MutableStateFlow(false)
    val isMainActivityForeground: StateFlow<Boolean> = _isMainActivityForeground.asStateFlow()

    private val _overlayRunning = MutableStateFlow(false)
    val overlayRunning: StateFlow<Boolean> = _overlayRunning.asStateFlow()

    private val _speedPresets = MutableStateFlow(defaultSpeedPresets())
    val speedPresets: StateFlow<List<CustomSpeedPreset>> = _speedPresets.asStateFlow()

    private val _motionState = MutableStateFlow(MotionControlState())
    val motionState: StateFlow<MotionControlState> = _motionState.asStateFlow()

    private val _overlayPrefs = MutableStateFlow(OverlayPrefsState())
    val overlayPrefs: StateFlow<OverlayPrefsState> = _overlayPrefs.asStateFlow()

    private val _hasDraftRoute = MutableStateFlow(false)
    val hasDraftRoute: StateFlow<Boolean> = _hasDraftRoute.asStateFlow()

    private val _appLanguageTag = MutableStateFlow(AppLocaleManager.TAG_AUTO)
    val appLanguageTag: StateFlow<String> = _appLanguageTag.asStateFlow()

    // Temporary in-app sensor self-test session state (never modifies persisted RouteStore targets!)
    private val selfTestMutex = Mutex()
    private val selfTestSeqLock = Any()

    @Volatile
    private var selfTestSessionId = 0L

    @Volatile
    private var selfTestTargetsActive = false
    private var selfTestSavedTargets: List<DaemonTargetSpec> = emptyList()
    private var selfTestTempJogActive = false
    private var selfTestPrevActive = false
    private var selfTestPrevMotionMode = KurokoProtocol.MOTION_AUTO
    private var selfTestPrevCarryMode = KurokoProtocol.CARRY_HAND_SWING

    private val _sensorSelfTestTempJogActive = MutableStateFlow(false)
    val sensorSelfTestTempJogActive: StateFlow<Boolean> = _sensorSelfTestTempJogActive.asStateFlow()

    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        daemon.contextProvider = { localizedContextOrNull() }
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val store = RouteStore(ctx)
            val s = store.loadSettings()
            val d = store.loadDraft()
            val savedLang = AppLocaleManager.getSavedTag(ctx)
            _appLanguageTag.value = savedLang
            val locCtx = AppLocaleManager.localizedContext(ctx)
            _snapshot.value = _snapshot.value.copy(
                message = locCtx.getString(R.string.daemon_connecting, KurokoProtocol.CTRL_PORT)
            )
            _versionCompat.value = evaluateVersionCompat(false, _snapshot.value.status, locCtx)
            _speedPresets.value = store.loadSpeedPresets()
            _motionState.value = MotionControlState(
                speedMps = s.speedMps,
                paceVarRatio = s.paceVarRatio,
                motionMode = s.motionMode,
                carryMode = s.carryMode
            )
            _overlayPrefs.value = OverlayPrefsState(
                enabled = s.overlayEnabled,
                autoHideInApp = s.overlayAutoHideInApp,
                secureFlag = s.overlaySecureFlag,
                sideRight = s.overlaySideRight,
                yFraction = s.overlayYFraction
            )
            _hasDraftRoute.value = d.draftWaypoints.size >= 2
            initialized = true
        }
    }

    fun localizedContextOrNull(): Context? =
        appContext?.let { AppLocaleManager.localizedContext(it) }

    fun setAppLanguage(context: Context, tag: String) {
        init(context)
        val canonical = AppLocaleManager.normalizeTag(tag)
        _appLanguageTag.value = canonical
        AppLogBuffer.i("Locale", "Language switched to $canonical")
        AppLocaleManager.applyLanguage(context, canonical)
        refreshLocalizedMessages()
    }

    fun refreshAppLanguageFromSystem(context: Context) {
        init(context)
        val current = AppLocaleManager.getSavedTag(context)
        if (_appLanguageTag.value != current) {
            _appLanguageTag.value = current
            syncLangToDaemonAsync(current)
        }
        refreshLocalizedMessages()
    }

    fun syncLangToDaemonAsync(tag: String) {
        val canonical = AppLocaleManager.normalizeTag(tag)
        scope.launch {
            runCatching { daemon.setLang(canonical) }
        }
    }

    private fun refreshLocalizedMessages() {
        val locCtx = localizedContextOrNull() ?: return
        val cur = _snapshot.value
        val updatedMsg = if (cur.connected) {
            locCtx.getString(
                R.string.daemon_status_connected,
                KurokoProtocol.CTRL_PORT,
                cur.status.targetCount
            )
        } else {
            cur.message
        }
        _snapshot.value = cur.copy(message = updatedMsg)
        _versionCompat.value = evaluateVersionCompat(cur.connected, cur.status, locCtx)
    }

    fun setMainActivityForeground(foreground: Boolean) {
        _isMainActivityForeground.value = foreground
    }

    fun setOverlayRunning(running: Boolean) {
        _overlayRunning.value = running
    }

    fun setOverlayExpanded(expanded: Boolean) {
        overlayExpanded = expanded
    }

    fun attachObserver(tag: String) {
        synchronized(lock) {
            observers.add(tag)
            if (pollJob == null || pollJob?.isActive != true) {
                pollJob = scope.launch { pollLoop() }
            }
        }
    }

    fun detachObserver(tag: String) {
        synchronized(lock) {
            observers.remove(tag)
            if (observers.isEmpty()) {
                pollJob?.cancel()
                pollJob = null
            }
        }
    }

    fun refreshNow() {
        scope.launch { pollOnce() }
    }

    fun syncDraftRouteState(hasRoute: Boolean) {
        _hasDraftRoute.value = hasRoute
    }

    fun syncMotionStateFromUi(
        speedMps: Float,
        paceVarRatio: Float,
        motionMode: Int,
        carryMode: Int
    ) {
        _motionState.value = MotionControlState(
            speedMps = speedMps.coerceIn(0.5f, 8.0f),
            paceVarRatio = paceVarRatio.coerceIn(0f, 0.20f),
            motionMode = motionMode,
            carryMode = carryMode.coerceIn(0, 1)
        )
    }

    fun updateSpeedPresets(context: Context, presets: List<CustomSpeedPreset>) {
        init(context)
        _speedPresets.value = presets
        RouteStore(context.applicationContext).saveSpeedPresets(presets)
    }

    fun updateOverlayPrefs(
        context: Context,
        enabled: Boolean? = null,
        autoHideInApp: Boolean? = null,
        secureFlag: Boolean? = null,
        sideRight: Boolean? = null,
        yFraction: Float? = null
    ) {
        init(context)
        val cur = _overlayPrefs.value
        val next = cur.copy(
            enabled = enabled ?: cur.enabled,
            autoHideInApp = autoHideInApp ?: cur.autoHideInApp,
            secureFlag = secureFlag ?: cur.secureFlag,
            sideRight = sideRight ?: cur.sideRight,
            yFraction = (yFraction ?: cur.yFraction).coerceIn(0.05f, 0.85f)
        )
        _overlayPrefs.value = next
        RouteStore(context.applicationContext).updateOverlayPrefs(
            enabled = enabled,
            autoHideInApp = autoHideInApp,
            secureFlag = secureFlag,
            sideRight = sideRight,
            yFraction = yFraction
        )
    }

    // ===== Unified Daemon Commands (with RouteStore + StateFlow lockstep sync) =====

    suspend fun applySpeed(
        context: Context,
        speedMps: Float,
        motionMode: Int = KurokoProtocol.MOTION_AUTO
    ): Result<String> {
        init(context)
        val clamped = ((speedMps.coerceIn(0.5f, 8.0f) * 10f).roundToInt()) / 10f
        val cur = _motionState.value
        _motionState.value = cur.copy(speedMps = clamped, motionMode = motionMode)
        RouteStore(context.applicationContext).updateMotionPrefs(
            speedMps = clamped,
            motionMode = motionMode
        )
        val res = daemon.setSpeed(clamped)
        daemon.setMode(motionMode, cur.carryMode)
        pollOnce()
        return res
    }

    suspend fun applyPreset(context: Context, preset: CustomSpeedPreset): Result<String> {
        init(context)
        val cur = _motionState.value
        _motionState.value = cur.copy(
            speedMps = preset.speedMps,
            paceVarRatio = preset.paceVarRatio,
            motionMode = preset.motionMode
        )
        RouteStore(context.applicationContext).updateMotionPrefs(
            speedMps = preset.speedMps,
            paceVarRatio = preset.paceVarRatio,
            motionMode = preset.motionMode
        )
        val res = daemon.setSpeed(preset.speedMps)
        daemon.setPaceVar(preset.paceVarRatio)
        daemon.setMode(preset.motionMode, cur.carryMode)
        pollOnce()
        return res
    }

    suspend fun applyCarryMode(context: Context, carryMode: Int): Result<String> {
        init(context)
        val clamped = carryMode.coerceIn(0, 1)
        val cur = _motionState.value
        _motionState.value = cur.copy(carryMode = clamped)
        RouteStore(context.applicationContext).updateMotionPrefs(carryMode = clamped)
        val res = daemon.setMode(cur.motionMode, clamped)
        pollOnce()
        return res
    }

    suspend fun applyPaceVar(context: Context, ratio: Float): Result<String> {
        init(context)
        val clamped = ratio.coerceIn(0f, 0.20f)
        val cur = _motionState.value
        _motionState.value = cur.copy(paceVarRatio = clamped)
        RouteStore(context.applicationContext).updateMotionPrefs(paceVarRatio = clamped)
        val res = daemon.setPaceVar(clamped)
        pollOnce()
        return res
    }

    suspend fun pauseSimulation(): Result<String> {
        val res = daemon.hold()
        if (res.isSuccess) {
            AppLogBuffer.i("Runtime", "Simulation paused (HOLD)")
        } else {
            AppLogBuffer.w("Runtime", "Pause failed: ${res.exceptionOrNull()?.message ?: "error"}")
        }
        pollOnce()
        return res
    }

    suspend fun stopSimulation(): Result<String> {
        val res = daemon.stop()
        if (res.isSuccess) {
            AppLogBuffer.i("Runtime", "Simulation stopped (STOP)")
        } else {
            AppLogBuffer.w("Runtime", "Stop failed: ${res.exceptionOrNull()?.message ?: "error"}")
        }
        pollOnce()
        return res
    }

    suspend fun resetSteps(): Result<String> {
        val res = daemon.resetSteps()
        if (res.isSuccess) {
            AppLogBuffer.i("Runtime", "Virtual step counter reset")
        } else {
            AppLogBuffer.w("Runtime", "Reset steps failed: ${res.exceptionOrNull()?.message ?: "error"}")
        }
        pollOnce()
        return res
    }

    /**
     * Resumes a paused route or starts the saved workspace draft route cleanly from background HUD / notification.
     */
    suspend fun resumeOrStartRoute(context: Context): Result<String> {
        init(context)
        if (!_snapshot.value.connected) {
            pollOnce()
        }
        val locCtx = AppLocaleManager.localizedContext(context)
        val snap = _snapshot.value
        val compat = evaluateVersionCompat(snap.connected, snap.status, locCtx)
        _versionCompat.value = compat
        if (snap.connected && !compat.canStartSimulation) {
            AppLogBuffer.w("VersionLock", "Start blocked by version lock: state=${compat.state}")
            val msg = if (compat.actionHint.isNotBlank()) {
                "${compat.headline}: ${compat.actionHint}"
            } else {
                compat.headline
            }
            return Result.failure(IllegalStateException(msg))
        }

        val st = snap.status
        val completed = st.active && !st.moving && (
            (st.maxLaps > 0 && st.laps >= st.maxLaps) ||
                (st.maxDistanceM > 1f && st.totalDistanceM >= st.maxDistanceM - 1f)
            )
        if (st.active && !st.moving && !completed && st.routeLengthM > 1f) {
            val res = daemon.start()
            if (res.isSuccess) {
                AppLogBuffer.i("Runtime", "Resumed active route simulation")
            } else {
                AppLogBuffer.w("Runtime", "Resume failed: ${res.exceptionOrNull()?.message ?: "error"}")
            }
            pollOnce()
            return res
        }

        val store = RouteStore(context.applicationContext)
        val draft = store.loadDraft()
        val cfg = store.loadSettings()
        val targets = store.loadTargets()
        if (draft.draftWaypoints.size < 2) {
            AppLogBuffer.w("Runtime", "HUD start aborted: no draft route configured")
            return Result.failure(
                IllegalStateException(locCtx.getString(R.string.daemon_err_no_draft_route))
            )
        }

        if (targets.isNotEmpty()) {
            daemon.setTargets(targets.map { DaemonTargetSpec(it.uid, it.policyFlags) })
        }
        val m = _motionState.value
        daemon.setSpeed(m.speedMps)
        daemon.setMode(m.motionMode, m.carryMode)
        daemon.setJitter(cfg.dynamicJitterM)
        daemon.setPaceVar(m.paceVarRatio)

        val effMaxLaps = if (draft.draftMaxLaps > 0) draft.draftMaxLaps else if (draft.draftLoop) 0 else 1
        val rRes = daemon.setRoute(
            points = draft.draftWaypoints.map { DaemonWaypoint(it.lat, it.lon, it.alt) },
            loop = draft.draftLoop,
            maxDistanceM = draft.draftTargetDistKm * 1000f,
            maxLaps = effMaxLaps
        )
        if (rRes.isFailure) {
            AppLogBuffer.w("Runtime", "Route push failed before start: ${rRes.exceptionOrNull()?.message ?: "error"}")
            return rRes
        }
        val sRes = daemon.start()
        if (sRes.isSuccess) {
            AppLogBuffer.i("Runtime", "Started draft route (${draft.draftWaypoints.size} pts, loop=${draft.draftLoop}, targets=${targets.size})")
        } else {
            AppLogBuffer.w("Runtime", "Start route failed: ${sRes.exceptionOrNull()?.message ?: "error"}")
        }
        pollOnce()
        return sRes
    }

    /**
     * Temporarily appends Kuroko Teleport's own UID with POLICY_STEPS | POLICY_IMU to daemon targets
     * for built-in pedometer / IMU self-test. Never modifies persisted RouteStore targets.
     */
    fun beginSensorSelfTestAsync(context: Context, savedTargets: List<DaemonTargetSpec>): Long {
        init(context)
        val mySession = synchronized(selfTestSeqLock) {
            selfTestSessionId += 1L
            selfTestSessionId
        }
        scope.launch {
            selfTestMutex.withLock {
                if (selfTestSessionId != mySession) return@withLock
                if (!selfTestTargetsActive) {
                    selfTestSavedTargets = savedTargets.toList()
                }
                val myUid = Process.myUid()
                val others = selfTestSavedTargets
                    .filter { it.uid != myUid }
                    .take(KurokoProtocol.MAX_TARGETS - 1)
                val existingSelf = selfTestSavedTargets.firstOrNull { it.uid == myUid }
                val selfFlags = (existingSelf?.policyFlags ?: 0) or
                    KurokoProtocol.POLICY_STEPS or
                    KurokoProtocol.POLICY_IMU
                val merged = others + DaemonTargetSpec(myUid, selfFlags)
                val res = daemon.setTargets(merged)
                if (res.isSuccess) {
                    selfTestTargetsActive = true
                    AppLogBuffer.i("SelfTest", "Sensor self-test started (app UID temporarily subscribed)")
                } else {
                    AppLogBuffer.w("SelfTest", "Sensor self-test target inject failed: ${res.exceptionOrNull()?.message ?: "error"}")
                }
                pollOnce()
            }
        }
        return mySession
    }

    /**
     * Temporarily enables stationary jog cadence (`SET_MODE 2 0` + `HOLD`) for in-app step self-test
     * when the daemon is not already generating steps. Restored automatically on `endSensorSelfTestAsync()`.
     */
    fun startSelfTestJogPulseAsync(sessionId: Long) {
        scope.launch {
            selfTestMutex.withLock {
                if (selfTestSessionId != sessionId) return@withLock
                val latestRes = daemon.getStatus()
                val connected = latestRes.isSuccess || _snapshot.value.connected
                val latest = latestRes.getOrNull() ?: _snapshot.value.status
                val compat = evaluateVersionCompat(connected, latest)
                _versionCompat.value = compat
                if (!compat.canStartSimulation) {
                    AppLogBuffer.w("SelfTest", "Temporary jog pulse blocked by version lock (${compat.state})")
                    pollOnce()
                    return@withLock
                }
                if (latest.active && latest.cadenceSpm >= 1f) {
                    pollOnce()
                    return@withLock
                }
                if (!selfTestTempJogActive) {
                    selfTestPrevActive = latest.active
                    selfTestPrevMotionMode = latest.motionMode
                    selfTestPrevCarryMode = latest.carryMode
                }
                daemon.setMode(KurokoProtocol.MOTION_JOG, KurokoProtocol.CARRY_HAND_SWING)
                val hRes = daemon.hold()
                if (hRes.isSuccess) {
                    selfTestTempJogActive = true
                    _sensorSelfTestTempJogActive.value = true
                    AppLogBuffer.i("SelfTest", "Temporary jog pulse activated")
                } else {
                    AppLogBuffer.w("SelfTest", "Temporary jog pulse failed: ${hRes.exceptionOrNull()?.message ?: "error"}")
                }
                pollOnce()
            }
        }
    }

    /**
     * Restores original daemon targets (and stops/reverts temporary jog pulse if started).
     * Runs on process-lifetime KurokoRuntime.scope so dialog dismissal never cancels cleanup.
     */
    fun endSensorSelfTestAsync() {
        synchronized(selfTestSeqLock) {
            selfTestSessionId += 1L
        }
        _sensorSelfTestTempJogActive.value = false
        scope.launch {
            selfTestMutex.withLock {
                val hadAny = selfTestTempJogActive || selfTestTargetsActive
                if (selfTestTempJogActive) {
                    daemon.setMode(selfTestPrevMotionMode, selfTestPrevCarryMode)
                    if (!selfTestPrevActive) {
                        daemon.stop()
                    }
                    selfTestTempJogActive = false
                }
                if (selfTestTargetsActive) {
                    daemon.setTargets(selfTestSavedTargets)
                    selfTestTargetsActive = false
                }
                if (hadAny) {
                    AppLogBuffer.i("SelfTest", "Sensor self-test ended and target list restored")
                }
                pollOnce()
            }
        }
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            pollOnce()
            val intervalMs = if (_isMainActivityForeground.value || overlayExpanded) 500L else 600L
            delay(intervalMs)
        }
    }

    private suspend fun pollOnce() {
        val locCtx = localizedContextOrNull()
        val res = daemon.getStatus()
        if (res.isSuccess) {
            val st = res.getOrThrow()
            val connMsg = locCtx?.getString(
                R.string.daemon_status_connected,
                KurokoProtocol.CTRL_PORT,
                st.targetCount
            ) ?: "127.0.0.1:${KurokoProtocol.CTRL_PORT} · ${st.targetCount}"
            _snapshot.value = DaemonSnapshot(
                status = st,
                connected = true,
                message = connMsg
            )
            val compat = evaluateVersionCompat(true, st, locCtx)
            _versionCompat.value = compat

            val hooksSig = "L=${st.locShm},P=${st.phoneShm},S=${st.sensorShm}"
            if (lastLoggedConnected != true) {
                lastLoggedConnected = true
                lastLoggedHooksSignature = hooksSig
                AppLogBuffer.i(
                    "Runtime",
                    "Daemon online: mod=v${st.modVer}(#${st.modCode}) xp=#${st.xposedCode} zy=#${st.zygiskCode} $hooksSig targets=${st.targetCount}"
                )
            } else if (lastLoggedHooksSignature != hooksSig) {
                lastLoggedHooksSignature = hooksSig
                AppLogBuffer.i("Runtime", "Hook state changed: $hooksSig")
            }
            if (lastLoggedCompatState != compat.state) {
                lastLoggedCompatState = compat.state
                if (compat.state == VersionCompatState.MATCHED) {
                    AppLogBuffer.i("VersionLock", "All 4 tiers aligned (v${KurokoProtocol.APP_VERSION_NAME} #${KurokoProtocol.APP_VERSION_CODE})")
                } else {
                    AppLogBuffer.w(
                        "VersionLock",
                        "Version lock state=${compat.state} app=#${KurokoProtocol.APP_VERSION_CODE} mod=#${st.modCode} xp=#${st.xposedCode} zy=#${st.zygiskCode} P=${st.protoVer} SHM=${st.shmVer}"
                    )
                }
            }

            if (!initialTargetsSynced && !selfTestTargetsActive) {
                initialTargetsSynced = true
                val ctx = appContext
                if (ctx != null && st.targetCount == 0) {
                    val savedTargets = RouteStore(ctx).loadTargets()
                    if (savedTargets.isNotEmpty()) {
                        daemon.setTargets(savedTargets.map { DaemonTargetSpec(it.uid, it.policyFlags) })
                    }
                }
            }
        } else {
            val fallbackMsg = locCtx?.getString(R.string.daemon_unresponsive) ?: "Daemon unresponsive"
            val nextSnap = _snapshot.value.copy(
                connected = false,
                message = res.exceptionOrNull()?.message ?: fallbackMsg
            )
            _snapshot.value = nextSnap
            val compat = evaluateVersionCompat(false, nextSnap.status, locCtx)
            _versionCompat.value = compat
            if (lastLoggedConnected != false) {
                lastLoggedConnected = false
                lastLoggedCompatState = compat.state
                AppLogBuffer.w("Runtime", "Daemon offline: ${nextSnap.message}")
            }
        }
    }
}
