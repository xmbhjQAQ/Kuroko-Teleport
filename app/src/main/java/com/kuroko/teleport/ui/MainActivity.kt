package com.kuroko.teleport.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kuroko.teleport.R
import com.kuroko.teleport.geo.CoordTransform
import com.kuroko.teleport.geo.GeoPoint
import com.kuroko.teleport.geo.GeocoderClient
import com.kuroko.teleport.geo.MapLayerType
import com.kuroko.teleport.geo.SearchPlace
import com.kuroko.teleport.ipc.DaemonTargetSpec
import com.kuroko.teleport.ipc.DaemonWaypoint
import com.kuroko.teleport.ipc.SimStatus
import com.kuroko.teleport.update.ReleaseUpdateInfo
import com.kuroko.teleport.update.UpdateCheckOutcome
import com.kuroko.teleport.update.UpdateChecker
import com.kuroko.teleport.ipc.VersionCompatInfo
import com.kuroko.teleport.ipc.VersionCompatState
import com.kuroko.teleport.ipc.KurokoProtocol
import com.kuroko.teleport.ipc.KurokoRuntime
import com.kuroko.teleport.overlay.FloatingOverlayService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

private val REPEAT_PRESET_VALUES = listOf(0, 1, 2, 3, 5, 10)

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
        )
        setContent {
            MaterialTheme(colorScheme = KurokoLightColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = NeuColors.Surface,
                    contentColor = NeuColors.InkPrimary
                ) {
                    KurokoControllerScreen()
                }
            }
        }
    }
}

@Composable
private fun KurokoControllerScreen() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val snackbarHostState = remember { SnackbarHostState() }

    val routeStore = remember { RouteStore(context.applicationContext) }
    val pointStore = remember { PointStore(context.applicationContext) }
    remember { KurokoRuntime.init(context.applicationContext) }
    val daemon = KurokoRuntime.daemon
    val mapController = remember { KurokoMapController() }

    // ===== Layer 1: Restore complete persisted workspace on startup =====
    val initialSettings = remember { routeStore.loadSettings() }
    val initialDraft = remember { routeStore.loadDraft() }

    var settings by remember { mutableStateOf(initialSettings) }
    var savedPoints by remember { mutableStateOf(pointStore.loadPoints()) }
    var savedRoutes by remember { mutableStateOf(routeStore.loadRoutes()) }
    val speedPresets by KurokoRuntime.speedPresets.collectAsState()
    var targetApps by remember { mutableStateOf(routeStore.resolveActiveTargets(persistIfChanged = true)) }

    // Shared process-level runtime states (synchronized with FloatingOverlayService)
    val daemonSnap by KurokoRuntime.snapshot.collectAsState()
    val versionCompat by KurokoRuntime.versionCompat.collectAsState()
    val daemonStatus = daemonSnap.status
    val daemonConnected = daemonSnap.connected
    val daemonMessage = daemonSnap.message
    val runtimeMotion by KurokoRuntime.motionState.collectAsState()
    val overlayPrefs by KurokoRuntime.overlayPrefs.collectAsState()
    val overlayRunning by KurokoRuntime.overlayRunning.collectAsState()
    val appLanguageTag by KurokoRuntime.appLanguageTag.collectAsState()
    val rfConfig by KurokoRuntime.rfConfig.collectAsState()

    // 0 = 定点模拟 (Static), 1 = 运动模拟 (Dynamic)
    var activeTab by remember { mutableIntStateOf(initialSettings.activeTab) }
    var sheetExpanded by remember { mutableStateOf(initialSettings.sheetExpanded) }
    var activeRouteProfileId by remember { mutableStateOf(initialSettings.activeRouteProfileId) }
    var activePointProfileId by remember { mutableStateOf(initialSettings.activePointProfileId) }

    // Camera & Static Mode State (Tab 0)
    var centerWgs84 by remember {
        mutableStateOf(GeoPoint(initialSettings.lastLatWgs84, initialSettings.lastLonWgs84))
    }
    var lastZoom by remember { mutableStateOf(initialSettings.lastZoom) }
    var staticPointWgs84 by remember { mutableStateOf(initialDraft.lastStaticPoint) }
    var staticPointLabel by remember { mutableStateOf(initialDraft.lastStaticLabel) }
    var staticJitterM by remember { mutableFloatStateOf(initialSettings.staticJitterM) }

    // Dynamic Mode Draft State (Tab 1)
    val waypoints = remember {
        mutableStateListOf<GeoPoint>().apply { addAll(initialDraft.draftWaypoints) }
    }
    var loopEnabled by remember { mutableStateOf(initialDraft.draftLoop) }
    var maxLaps by remember { mutableIntStateOf(initialDraft.draftMaxLaps) }
    var targetDistanceKm by remember { mutableFloatStateOf(initialDraft.draftTargetDistKm) }

    // Motion & physics parameters (separate dynamicJitterM from staticJitterM!)
    var speedMps by remember { mutableFloatStateOf(runtimeMotion.speedMps.coerceIn(0.5f, 8.0f)) }
    var motionMode by remember { mutableIntStateOf(runtimeMotion.motionMode) }
    var carryMode by remember { mutableIntStateOf(runtimeMotion.carryMode) }
    var dynamicJitterM by remember { mutableFloatStateOf(initialSettings.dynamicJitterM) }
    var paceVarRatio by remember { mutableFloatStateOf(runtimeMotion.paceVarRatio.coerceIn(0f, 0.20f)) }

    // Keep UI controls aligned whenever FloatingOverlayService adjusts speed/preset/carry in background
    LaunchedEffect(runtimeMotion) {
        speedMps = runtimeMotion.speedMps.coerceIn(0.5f, 8.0f)
        paceVarRatio = runtimeMotion.paceVarRatio.coerceIn(0f, 0.20f)
        motionMode = runtimeMotion.motionMode
        carryMode = runtimeMotion.carryMode
    }

    // Real GPS marker state
    var realLocationWgs84 by remember { mutableStateOf<GeoPoint?>(null) }
    var realAccuracyM by remember { mutableFloatStateOf(18f) }
    var locatingRealGps by remember { mutableStateOf(false) }

    // Search state
    var searchQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchPlace>>(emptyList()) }

    // Sheets & Dialogs
    var showTargetSheet by remember { mutableStateOf(false) }
    var showPointsSheet by remember { mutableStateOf(false) }
    var showRoutesSheet by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showRfConfigDialog by remember { mutableStateOf(false) }
    var showDiagnosticsDialog by remember { mutableStateOf(false) }
    var showHelpSheet by remember { mutableStateOf(false) }
    var showTargetDistDialog by remember { mutableStateOf(false) }
    var showCustomLapsDialog by remember { mutableStateOf(false) }
    var showOverlayPermDialog by remember { mutableStateOf(false) }
    var showVersionLockDialog by remember { mutableStateOf(false) }
    var pendingOverlayAfterPermission by remember { mutableStateOf(false) }

    // Release-only update check state (compile-time disabled for CI / local builds)
    var autoCheckUpdate by remember { mutableStateOf(UpdateChecker.isAutoCheckEnabled(context)) }
    var updateCheckBusy by remember { mutableStateOf(false) }
    var updateStatusText by remember { mutableStateOf<String?>(null) }
    var pendingUpdateInfo by remember { mutableStateOf<ReleaseUpdateInfo?>(null) }

    LaunchedEffect(Unit) {
        if (!UpdateChecker.shouldAutoCheckNow(context)) return@LaunchedEffect
        delay(1500L)
        when (val outcome = UpdateChecker.checkForUpdate(context, manual = false)) {
            is UpdateCheckOutcome.UpdateAvailable -> {
                if (!outcome.isIgnored) {
                    pendingUpdateInfo = outcome.info
                }
            }
            else -> Unit
        }
    }

    fun buildCurrentSettingsSnapshot(): AppSettings = settings.copy(
        lastLatWgs84 = centerWgs84.lat,
        lastLonWgs84 = centerWgs84.lon,
        lastZoom = lastZoom,
        speedMps = speedMps,
        motionMode = motionMode,
        carryMode = carryMode,
        staticJitterM = staticJitterM,
        dynamicJitterM = dynamicJitterM,
        paceVarRatio = paceVarRatio,
        activeTab = activeTab,
        sheetExpanded = sheetExpanded,
        activeRouteProfileId = activeRouteProfileId,
        activePointProfileId = activePointProfileId,
        overlayEnabled = overlayPrefs.enabled,
        overlayAutoHideInApp = overlayPrefs.autoHideInApp,
        overlaySecureFlag = overlayPrefs.secureFlag,
        overlaySideRight = overlayPrefs.sideRight,
        overlayYFraction = overlayPrefs.yFraction
    )

    fun buildCurrentDraftSnapshot(): WorkspaceDraft = WorkspaceDraft(
        lastStaticPoint = staticPointWgs84,
        lastStaticLabel = staticPointLabel,
        draftWaypoints = waypoints.toList(),
        draftLoop = loopEnabled,
        draftMaxLaps = maxLaps,
        draftTargetDistKm = targetDistanceKm
    )

    // Debounced auto-save for Layer 1 workspace survival (zero UI jank while dragging sliders/panning)
    val waypointsSnapshot = waypoints.toList()
    LaunchedEffect(waypointsSnapshot.size) {
        KurokoRuntime.syncDraftRouteState(waypointsSnapshot.size >= 2)
    }

    LaunchedEffect(
        settings.layerType,
        settings.tiandituToken,
        settings.amapWebKey,
        centerWgs84,
        lastZoom,
        speedMps,
        motionMode,
        carryMode,
        staticJitterM,
        dynamicJitterM,
        paceVarRatio,
        activeTab,
        sheetExpanded,
        activeRouteProfileId,
        activePointProfileId,
        staticPointWgs84,
        staticPointLabel,
        waypointsSnapshot,
        loopEnabled,
        maxLaps,
        targetDistanceKm
    ) {
        delay(300L)
        routeStore.saveSettings(buildCurrentSettingsSnapshot())
        routeStore.saveDraft(buildCurrentDraftSnapshot())
    }

    // Pulse animation for active simulated dot
    val infiniteTransition = rememberInfiniteTransition(label = "sim_pulse")
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_phase"
    )

    fun showToast(msg: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
        }
    }

    fun openExternalUrl(url: String) {
        val ok = runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }.isSuccess
        if (!ok) {
            showToast(context.getString(R.string.update_toast_open_failed))
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Gentle request only — never block Floating HUD if notification permission is denied */ }

    fun enableOverlayNow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        KurokoRuntime.updateOverlayPrefs(context, enabled = true)
        FloatingOverlayService.start(context)
        showToast(
            context.getString(
                if (KurokoRuntime.overlayPrefs.value.autoHideInApp) {
                    R.string.main_toast_hud_on_autohide
                } else {
                    R.string.main_toast_hud_on
                }
            )
        )
    }

    fun toggleOverlay(wantEnable: Boolean) {
        if (!wantEnable) {
            KurokoRuntime.updateOverlayPrefs(context, enabled = false)
            FloatingOverlayService.stop(context)
            showToast(context.getString(R.string.main_toast_hud_off))
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            showOverlayPermDialog = true
        } else {
            enableOverlayNow()
        }
    }

    // Lifecycle observer: foreground tracking, reference-counted polling, draft flush, and overlay permission return
    val latestFlush by rememberUpdatedState {
        routeStore.saveSettings(buildCurrentSettingsSnapshot())
        routeStore.saveDraft(buildCurrentDraftSnapshot())
    }
    val latestOnResumeOverlayCheck by rememberUpdatedState {
        KurokoRuntime.refreshAppLanguageFromSystem(context)
        val resolvedTargets = routeStore.resolveTargets(routeStore.loadRawTargets(), persistIfChanged = true)
        if (resolvedTargets.activeTargets != targetApps) {
            targetApps = resolvedTargets.activeTargets
        }
        if (resolvedTargets.changed) {
            scope.launch {
                KurokoRuntime.pushResolvedTargetsIfIdle(resolvedTargets.activeTargets)
            }
        }
        if (pendingOverlayAfterPermission) {
            pendingOverlayAfterPermission = false
            if (Settings.canDrawOverlays(context)) {
                enableOverlayNow()
            } else {
                showToast(context.getString(R.string.main_toast_hud_perm_denied))
            }
        } else if (overlayPrefs.enabled && !Settings.canDrawOverlays(context)) {
            KurokoRuntime.updateOverlayPrefs(context, enabled = false)
            FloatingOverlayService.stop(context)
        } else if (overlayPrefs.enabled && !overlayRunning && Settings.canDrawOverlays(context)) {
            FloatingOverlayService.start(context)
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    KurokoRuntime.setMainActivityForeground(true)
                    KurokoRuntime.attachObserver("main_activity")
                }
                Lifecycle.Event.ON_RESUME -> {
                    latestOnResumeOverlayCheck()
                }
                Lifecycle.Event.ON_PAUSE -> {
                    latestFlush()
                }
                Lifecycle.Event.ON_STOP -> {
                    latestFlush()
                    KurokoRuntime.setMainActivityForeground(false)
                    KurokoRuntime.detachObserver("main_activity")
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            KurokoRuntime.setMainActivityForeground(false)
            KurokoRuntime.detachObserver("main_activity")
        }
    }

    fun triggerLocateMe() {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (lm == null) {
            showToast(context.getString(R.string.main_toast_loc_unavailable))
            return
        }
        if (!lm.isLocationEnabled) {
            showToast(context.getString(R.string.main_toast_loc_disabled))
            return
        }

        locatingRealGps = true
        requestRealDeviceLocationFast(
            context = context,
            lm = lm,
            onImmediateCached = { cached ->
                realLocationWgs84 = GeoPoint(cached.latitude, cached.longitude, cached.altitude)
                realAccuracyM = cached.accuracy.coerceIn(5f, 200f)
                mapController.flyToWgs84(cached.latitude, cached.longitude, 16.5)
            },
            onFreshResult = { fresh, hadCached ->
                locatingRealGps = false
                if (fresh != null) {
                    realLocationWgs84 = GeoPoint(fresh.latitude, fresh.longitude, fresh.altitude)
                    realAccuracyM = fresh.accuracy.coerceIn(5f, 200f)
                    mapController.flyToWgs84(fresh.latitude, fresh.longitude, 16.5)
                    showToast(context.getString(R.string.main_toast_located, fresh.accuracy.roundToInt()))
                } else if (hadCached) {
                    showToast(context.getString(R.string.main_toast_located_cached))
                } else {
                    showToast(context.getString(R.string.main_toast_loc_failed))
                }
            }
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            triggerLocateMe()
        } else {
            showToast(context.getString(R.string.main_toast_loc_perm_needed))
        }
    }

    fun ensureVersionCompatForStart(): Boolean {
        if (daemonConnected && !versionCompat.canStartSimulation) {
            showVersionLockDialog = true
            return false
        }
        return true
    }

    suspend fun startStaticPoint() {
        if (!ensureVersionCompatForStart()) return
        val targetPt = staticPointWgs84 ?: (mapController.getCenterWgs84() ?: centerWgs84)
        staticPointWgs84 = targetPt
        val resolvedTargets = routeStore.resolveActiveTargets(persistIfChanged = true)
        if (resolvedTargets != targetApps) {
            targetApps = resolvedTargets
        }
        if (resolvedTargets.isNotEmpty() || daemonStatus.targetCount > 0) {
            daemon.setTargets(resolvedTargets.map { DaemonTargetSpec(it.uid, it.policyFlags) })
        }
        val ptRes = daemon.setPoint(targetPt.lat, targetPt.lon, targetPt.alt)
        if (ptRes.isFailure) {
            showToast(
                context.getString(
                    R.string.main_toast_set_point_failed,
                    ptRes.exceptionOrNull()?.message.orEmpty()
                )
            )
            return
        }
        daemon.setMode(KurokoProtocol.MOTION_STILL, KurokoProtocol.CARRY_HAND_SWING)
        daemon.setJitter(staticJitterM)
        val holdRes = daemon.hold()
        KurokoRuntime.refreshNow()
        if (holdRes.isSuccess) {
            showToast(context.getString(R.string.main_toast_static_started, staticJitterM))
        } else {
            showToast(
                context.getString(
                    R.string.main_toast_static_failed,
                    holdRes.exceptionOrNull()?.message.orEmpty()
                )
            )
        }
    }

    suspend fun startDynamicRoute(moving: Boolean) {
        if (!ensureVersionCompatForStart()) return
        val resolvedTargets = routeStore.resolveActiveTargets(persistIfChanged = true)
        if (resolvedTargets != targetApps) {
            targetApps = resolvedTargets
        }
        if (resolvedTargets.isNotEmpty() || daemonStatus.targetCount > 0) {
            daemon.setTargets(resolvedTargets.map { DaemonTargetSpec(it.uid, it.policyFlags) })
        }
        KurokoRuntime.syncMotionStateFromUi(speedMps, paceVarRatio, motionMode, carryMode)
        daemon.setSpeed(speedMps)
        daemon.setMode(motionMode, carryMode)
        daemon.setJitter(dynamicJitterM)
        daemon.setPaceVar(paceVarRatio)

        val pts = waypoints.toList()
        if (pts.size < 2) {
            showToast(context.getString(R.string.main_toast_need_two_points))
            return
        }
        val effectiveMaxLaps = if (maxLaps > 0) maxLaps else if (loopEnabled) 0 else 1
        val routeRes = daemon.setRoute(
            points = pts.map { DaemonWaypoint(it.lat, it.lon, it.alt) },
            loop = loopEnabled,
            maxDistanceM = targetDistanceKm * 1000f,
            maxLaps = effectiveMaxLaps
        )
        if (routeRes.isFailure) {
            showToast(
                context.getString(
                    R.string.main_toast_set_route_failed,
                    routeRes.exceptionOrNull()?.message.orEmpty()
                )
            )
            return
        }
        val actRes = if (moving) daemon.start() else daemon.hold()
        KurokoRuntime.refreshNow()
        if (actRes.isSuccess) {
            showToast(
                context.getString(
                    if (moving) R.string.main_toast_motion_started else R.string.main_toast_holding
                )
            )
        } else {
            showToast(
                context.getString(
                    R.string.main_toast_action_failed,
                    actRes.exceptionOrNull()?.message.orEmpty()
                )
            )
        }
    }

    fun triggerSearch() {
        focusManager.clearFocus()
        if (searchQuery.isBlank()) return
        scope.launch {
            searching = true
            val res = GeocoderClient.search(
                query = searchQuery,
                biasLatWgs84 = centerWgs84.lat,
                biasLonWgs84 = centerWgs84.lon,
                amapWebKey = settings.amapWebKey,
                tiandituToken = settings.tiandituToken,
                context = context
            )
            searching = false
            res.onSuccess { list ->
                searchResults = list
                if (list.isEmpty()) showToast(context.getString(R.string.main_toast_search_empty))
            }.onFailure { err ->
                showToast(
                    context.getString(
                        R.string.main_toast_search_failed,
                        err.message.orEmpty()
                    )
                )
            }
        }
    }

    val routeLapMeters = remember(waypointsSnapshot, loopEnabled) {
        CoordTransform.routeLengthMeters(waypointsSnapshot, loopEnabled)
    }

    val effectivePreviewMaxLaps = if (maxLaps > 0) maxLaps else if (loopEnabled) 0 else 1
    val previewPlannedDistM = remember(routeLapMeters, targetDistanceKm, effectivePreviewMaxLaps) {
        computePlannedDistanceM(routeLapMeters, targetDistanceKm * 1000f, effectivePreviewMaxLaps)
    }
    val hasFiniteGoalPreview = (targetDistanceKm > 0f) || (effectivePreviewMaxLaps > 0)

    val isRunCompleted = daemonStatus.active && !daemonStatus.moving && (
        (daemonStatus.maxLaps > 0 && daemonStatus.laps >= daemonStatus.maxLaps) ||
            (daemonStatus.maxDistanceM > 1f && daemonStatus.totalDistanceM >= daemonStatus.maxDistanceM - 1f)
        )

    // ===== Root Layout with Dynamic Draggable Spring Bottom Sheet & Lockstep Floating Tools =====
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val collapsedHeightDp = 58.dp
        // Dynamically cap expanded height strictly below 50% vertical center crosshair even in compact floating windows
        val expandedHeightDp = minOf(
            370.dp,
            (maxHeight * 0.46f - 16.dp).coerceAtLeast(collapsedHeightDp + 64.dp)
        )

        val collapsedPx = with(density) { collapsedHeightDp.toPx() }
        val expandedPx = with(density) { expandedHeightDp.toPx() }
        val sheetHeightPx = remember {
            Animatable(if (sheetExpanded) expandedPx else collapsedPx)
        }

        // Keep sheet bounds synced when window resizes (e.g. entering/exiting HyperOS floating window)
        LaunchedEffect(collapsedPx, expandedPx) {
            val target = if (sheetExpanded) expandedPx else collapsedPx
            sheetHeightPx.snapTo(target.coerceIn(collapsedPx, expandedPx))
        }

        fun animateSheetTo(expand: Boolean) {
            sheetExpanded = expand
            scope.launch {
                sheetHeightPx.animateTo(
                    targetValue = if (expand) expandedPx else collapsedPx,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMediumLow
                    )
                )
            }
        }

        val currentSheetHeightDp = with(density) { sheetHeightPx.value.toDp() }
        val expandFraction = ((sheetHeightPx.value - collapsedPx) /
            (expandedPx - collapsedPx).coerceAtLeast(1f)).coerceIn(0f, 1f)

        val velocityTracker = remember { VelocityTracker() }
        val sheetDragModifier = Modifier.pointerInput(collapsedPx, expandedPx) {
            var cumulativeDragY = 0f
            detectVerticalDragGestures(
                onDragStart = {
                    cumulativeDragY = 0f
                    velocityTracker.resetTracking()
                },
                onVerticalDrag = { change, dragAmount ->
                    change.consume()
                    cumulativeDragY += dragAmount
                    velocityTracker.addPosition(change.uptimeMillis, Offset(0f, cumulativeDragY))
                    scope.launch {
                        val next = (sheetHeightPx.value - dragAmount).coerceIn(collapsedPx, expandedPx)
                        sheetHeightPx.snapTo(next)
                    }
                },
                onDragEnd = {
                    val velY = velocityTracker.calculateVelocity().y
                    val progress = (sheetHeightPx.value - collapsedPx) /
                        (expandedPx - collapsedPx).coerceAtLeast(1f)
                    val shouldExpand = when {
                        velY < -350f -> true
                        velY > 350f -> false
                        else -> progress >= 0.40f
                    }
                    animateSheetTo(shouldExpand)
                },
                onDragCancel = {
                    val progress = (sheetHeightPx.value - collapsedPx) /
                        (expandedPx - collapsedPx).coerceAtLeast(1f)
                    animateSheetTo(progress >= 0.40f)
                }
            )
        }

        // 1. Full-screen MapLibre Native Map (tapping map dismisses keyboard & search dropdown)
        MapViewContainer(
            modifier = Modifier.fillMaxSize(),
            controller = mapController,
            layerType = settings.layerType,
            tiandituToken = settings.tiandituToken,
            initialLatWgs84 = settings.lastLatWgs84,
            initialLonWgs84 = settings.lastLonWgs84,
            initialZoom = settings.lastZoom,
            activeTab = activeTab,
            staticPointWgs84 = staticPointWgs84,
            jitterM = staticJitterM,
            waypointsWgs84 = waypointsSnapshot,
            loopEnabled = loopEnabled,
            realLocationWgs84 = realLocationWgs84,
            realAccuracyM = realAccuracyM,
            simActive = daemonStatus.active,
            simPointWgs84 = if (daemonStatus.active) {
                GeoPoint(daemonStatus.latWgs84, daemonStatus.lonWgs84, daemonStatus.altM)
            } else {
                null
            },
            pulsePhase = pulsePhase,
            onCenterChangedWgs84 = { pt, zoom ->
                centerWgs84 = pt
                lastZoom = zoom
            },
            onMapTap = {
                focusManager.clearFocus()
                if (searchResults.isNotEmpty()) searchResults = emptyList()
            }
        )

        // 2. Dual-Ringed High-Contrast Center Crosshair (always unobstructed above bottom sheet!)
        Box(
            modifier = Modifier.align(Alignment.Center),
            contentAlignment = Alignment.Center
        ) {
            DualRingCrosshairReticle(
                accentColor = if (activeTab == 0) NeuColors.Coral else NeuColors.CobaltDeep
            )
            NeuFloatingSurface(
                modifier = Modifier.padding(top = 62.dp),
                cornerRadius = 10.dp,
                surfaceColor = NeuColors.SurfaceGlassStrong,
                elevation = 5.dp
            ) {
                Text(
                    text = String.format(
                        Locale.US,
                        "%.5f, %.5f",
                        centerWgs84.lat,
                        centerWgs84.lon
                    ),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    color = NeuColors.InkPrimary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // 3. Top Floating Glassmorphic Bar: Status LEDs + Search + Layers + Braun LCD Telemetry HUD
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            NeuFloatingSurface(
                modifier = Modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
                surfaceColor = NeuColors.SurfaceGlass,
                elevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Row A: Braun Hardware LED Strip (`引擎 ● | L✓ P✓ S✓`) + HUD + ? + ⚙
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .neuInset(cornerRadius = 10.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    val engLed = when {
                                        !daemonConnected -> NeuColors.Coral
                                        daemonStatus.active && daemonStatus.moving -> NeuColors.LedGreen
                                        daemonStatus.active -> NeuColors.Amber
                                        else -> NeuColors.Cobalt
                                    }
                                    NeuLedDot(
                                        color = engLed,
                                        lit = true,
                                        isPulsing = daemonConnected && daemonStatus.active,
                                        size = 6.dp
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = stringResource(
                                            when {
                                                !daemonConnected -> R.string.main_status_offline
                                                isRunCompleted -> R.string.main_status_completed
                                                daemonStatus.active && daemonStatus.moving -> R.string.main_status_moving
                                                daemonStatus.active -> R.string.main_status_static
                                                else -> R.string.main_status_ready
                                            }
                                        ),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeuColors.InkPrimary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Text("|", fontSize = 10.sp, color = NeuColors.InkMuted)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    NeuLedDot(
                                        color = NeuColors.LedGreen,
                                        lit = daemonConnected && daemonStatus.locShm,
                                        size = 5.5.dp
                                    )
                                    Spacer(Modifier.width(2.5.dp))
                                    Text(
                                        text = if (daemonConnected && daemonStatus.locShm) "L✓" else "L·",
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (daemonConnected && daemonStatus.locShm) NeuColors.InkPrimary else NeuColors.InkMuted,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    NeuLedDot(
                                        color = NeuColors.LedGreen,
                                        lit = daemonConnected && daemonStatus.phoneShm,
                                        size = 5.5.dp
                                    )
                                    Spacer(Modifier.width(2.5.dp))
                                    Text(
                                        text = if (daemonConnected && daemonStatus.phoneShm) "P✓" else "P·",
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (daemonConnected && daemonStatus.phoneShm) NeuColors.InkPrimary else NeuColors.InkMuted,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    NeuLedDot(
                                        color = NeuColors.LedGreen,
                                        lit = daemonConnected && daemonStatus.sensorShm,
                                        size = 5.5.dp
                                    )
                                    Spacer(Modifier.width(2.5.dp))
                                    Text(
                                        text = if (daemonConnected && daemonStatus.sensorShm) "S✓" else "S·",
                                        fontSize = 10.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (daemonConnected && daemonStatus.sensorShm) NeuColors.InkPrimary else NeuColors.InkMuted,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.width(6.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                            val hudActive = overlayRunning || overlayPrefs.enabled
                            Box {
                                NeuSquareIconButton(
                                    onClick = { toggleOverlay(!hudActive) },
                                    size = 32.dp,
                                    cornerRadius = 10.dp,
                                    contentColor = if (hudActive) NeuColors.EmeraldDeep else NeuColors.InkSecondary
                                ) {
                                    Icon(
                                        Icons.Default.PictureInPictureAlt,
                                        contentDescription = stringResource(R.string.main_cd_hud_toggle),
                                        modifier = Modifier.size(17.dp)
                                    )
                                }
                                if (hudActive) {
                                    NeuLedDot(
                                        color = NeuColors.LedGreen,
                                        lit = true,
                                        isPulsing = overlayRunning,
                                        size = 5.dp,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .padding(top = 3.dp, end = 3.dp)
                                    )
                                }
                            }
                            NeuSquareIconButton(
                                onClick = { showHelpSheet = true },
                                size = 32.dp,
                                cornerRadius = 10.dp,
                                contentColor = NeuColors.CobaltDeep
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.HelpOutline,
                                    contentDescription = stringResource(R.string.main_cd_help),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            NeuSquareIconButton(
                                onClick = { showSettingsDialog = true },
                                size = 32.dp,
                                cornerRadius = 10.dp,
                                contentColor = NeuColors.InkPrimary
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = stringResource(R.string.main_cd_settings),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    // Strict 4-Tier Version Lock Compact Pill (zero UI when matched or offline)
                    AnimatedVisibility(visible = daemonConnected && !versionCompat.canStartSimulation) {
                        val isReboot = versionCompat.state == VersionCompatState.REBOOT_REQUIRED
                        val pillAccent = if (isReboot) NeuColors.AmberDeep else NeuColors.Coral
                        val pillBg = if (isReboot) NeuColors.Amber.copy(alpha = 0.16f) else NeuColors.Coral.copy(alpha = 0.13f)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(pillBg)
                                .border(1.dp, pillAccent.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .clickable { showVersionLockDialog = true }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = null,
                                    tint = pillAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(pillAccent)
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (isReboot) R.string.main_pill_reboot else R.string.main_pill_locked
                                        ),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                Text(
                                    text = versionCompat.actionHint.ifBlank { versionCompat.detail },
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = pillAccent,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.common_details),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = pillAccent
                            )
                        }
                    }

                    // Offline Troubleshooting Compact Pill (one-tap access to Diagnostics & Logs)
                    AnimatedVisibility(visible = !daemonConnected) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(NeuColors.Coral.copy(alpha = 0.11f))
                                .border(1.dp, NeuColors.Coral.copy(alpha = 0.38f), RoundedCornerShape(10.dp))
                                .clickable { showDiagnosticsDialog = true }
                                .padding(horizontal = 9.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.HealthAndSafety,
                                    contentDescription = null,
                                    tint = NeuColors.CoralDeep,
                                    modifier = Modifier.size(13.dp)
                                )
                                Text(
                                    text = daemonMessage,
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NeuColors.CoralDeep,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.compat_btn_open_diag),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.CoralDeep,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }

                    // Row B: Carved Inset Search Input
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .neuInset(cornerRadius = 12.dp, surfaceColor = NeuColors.InsetBg, depth = 3.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                placeholder = {
                                    Text(
                                        stringResource(R.string.main_search_hint),
                                        fontSize = 12.sp,
                                        color = NeuColors.InkMuted,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                    focusedTextColor = NeuColors.InkPrimary,
                                    unfocusedTextColor = NeuColors.InkPrimary,
                                    cursorColor = NeuColors.CobaltDeep
                                ),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { triggerSearch() })
                            )
                            if (searching) {
                                CircularProgressIndicator(
                                    modifier = Modifier
                                        .padding(end = 12.dp)
                                        .size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = NeuColors.CobaltDeep
                                )
                            } else {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            searchQuery = ""
                                            searchResults = emptyList()
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = stringResource(R.string.main_cd_clear),
                                            tint = NeuColors.InkSecondary,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { triggerSearch() },
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Search,
                                        contentDescription = stringResource(R.string.main_cd_search),
                                        tint = NeuColors.CobaltDeep,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Smooth Animated Search Results Dropdown (capped height so it never covers bottom sheet)
                    AnimatedVisibility(
                        visible = searchResults.isNotEmpty(),
                        enter = expandVertically(animationSpec = tween(200)) + fadeIn(tween(180)),
                        exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(140))
                    ) {
                        Column {
                            HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.45f))
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 185.dp)
                            ) {
                                items(searchResults) { place ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                mapController.flyToWgs84(place.latWgs84, place.lonWgs84, 16.8)
                                                if (activeTab == 0) {
                                                    staticPointWgs84 = GeoPoint(place.latWgs84, place.lonWgs84)
                                                    staticPointLabel = place.name
                                                    activePointProfileId = null
                                                }
                                                searchResults = emptyList()
                                                focusManager.clearFocus()
                                            }
                                            .padding(horizontal = 8.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = place.name,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = NeuColors.InkPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (place.subtitle.isNotBlank()) {
                                                Text(
                                                    text = place.subtitle,
                                                    fontSize = 11.sp,
                                                    color = NeuColors.InkSecondary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = place.providerLabel(context),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = NeuColors.CobaltDeep
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Layer Switcher Chips Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                MapLayerType.entries.forEach { layer ->
                    NeuChip(
                        label = stringResource(layer.shortLabelRes),
                        selected = settings.layerType == layer,
                        floatingOverMap = true,
                        onClick = {
                            if (layer.requiresTiandituToken && settings.tiandituToken.isBlank()) {
                                showToast(context.getString(R.string.main_toast_need_tdt_token))
                                showSettingsDialog = true
                            } else {
                                settings = settings.copy(layerType = layer)
                            }
                        }
                    )
                }
            }

            // Braun Recessed LCD Telemetry HUD
            if (activeTab == 1 && (daemonStatus.active || waypoints.isNotEmpty())) {
                val lapDisplayM = if (daemonStatus.routeLengthM > 1f) {
                    daemonStatus.routeLengthM.toDouble()
                } else {
                    routeLapMeters
                }
                val effMaxLaps = if (daemonStatus.active) daemonStatus.maxLaps else effectivePreviewMaxLaps
                val lapCounterStr = if (effMaxLaps > 0) "${daemonStatus.laps}/$effMaxLaps" else "${daemonStatus.laps}/∞"

                val etaLabel: String
                val etaValue: String
                if (isRunCompleted) {
                    etaLabel = stringResource(R.string.main_hud_state)
                    etaValue = stringResource(R.string.main_status_completed)
                } else if (daemonStatus.active && daemonStatus.moving) {
                    val runPlannedM = computePlannedDistanceM(
                        daemonStatus.routeLengthM.toDouble(),
                        daemonStatus.maxDistanceM,
                        daemonStatus.maxLaps
                    )
                    val effSpd = (if (daemonStatus.speedMps > 0.3f) daemonStatus.speedMps else daemonStatus.targetSpeedMps)
                        .coerceAtLeast(0.3f) * 0.97
                    if (daemonStatus.maxDistanceM > 0f || daemonStatus.maxLaps > 0) {
                        val remM = (runPlannedM - daemonStatus.totalDistanceM).coerceAtLeast(0.0)
                        etaLabel = stringResource(R.string.main_hud_eta)
                        etaValue = formatDurationClock(remM / effSpd)
                    } else {
                        etaLabel = stringResource(R.string.main_hud_lap_est)
                        etaValue = formatDurationClock(daemonStatus.routeLengthM / effSpd)
                    }
                } else {
                    val estSec = previewPlannedDistM / (speedMps.coerceAtLeast(0.3f) * 0.97)
                    etaLabel = stringResource(
                        if (hasFiniteGoalPreview) R.string.main_hud_est_time else R.string.main_hud_lap_est
                    )
                    etaValue = if (previewPlannedDistM > 1.0) formatDurationClock(estSec) else "--:--"
                }

                NeuFloatingSurface(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 16.dp,
                    surfaceColor = NeuColors.SurfaceGlass,
                    elevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        if (isRunCompleted) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(NeuColors.EmeraldSoft)
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NeuLedDot(color = NeuColors.LedGreen, lit = true, isPulsing = true, size = 7.dp)
                                Spacer(Modifier.width(7.dp))
                                Text(
                                    text = stringResource(R.string.main_completed_banner),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF065F46)
                                )
                            }
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .neuInset(cornerRadius = 11.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                BraunHudMetric(stringResource(R.string.main_hud_lap), CoordTransform.formatDistance(lapDisplayM))
                                BraunHudMetric(stringResource(R.string.main_hud_dist), CoordTransform.formatDistance(daemonStatus.totalDistanceM.toDouble()))
                                BraunHudMetric(stringResource(R.string.main_hud_laps), lapCounterStr)
                                BraunHudMetric(stringResource(R.string.main_hud_pace), CoordTransform.formatPace(daemonStatus.speedMps).removeSuffix("/km"))
                                BraunHudMetric(stringResource(R.string.main_hud_steps), "${daemonStatus.virtualSteps}")
                                BraunHudMetric(etaLabel, etaValue)
                            }
                        }
                    }
                }
            } else if (activeTab == 0 && daemonStatus.active) {
                NeuFloatingSurface(
                    modifier = Modifier.fillMaxWidth(),
                    cornerRadius = 15.dp,
                    surfaceColor = NeuColors.SurfaceGlass,
                    elevation = 8.dp
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .neuInset(cornerRadius = 10.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NeuLedDot(color = NeuColors.LedGreen, lit = true, isPulsing = true, size = 6.5.dp)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.main_static_active_badge),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.InkPrimary
                                )
                            }
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%.5f, %.5f · ±%.1fm",
                                    daemonStatus.latWgs84,
                                    daemonStatus.lonWgs84,
                                    daemonStatus.jitterSigmaM
                                ),
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                color = NeuColors.InkPrimary
                            )
                        }
                    }
                }
            }
        }

        // 4. Right-Side Braun Floating Map Tools — LOCKSTEP with live animated bottom sheet height!
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 12.dp, bottom = currentSheetHeightDp + 18.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            NeuSquareIconButton(
                onClick = {
                    val hasFine = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val hasCoarse = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    if (hasFine || hasCoarse) {
                        triggerLocateMe()
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                },
                size = 44.dp,
                cornerRadius = 14.dp,
                floatingOverMap = true,
                contentColor = NeuColors.CobaltDeep
            ) {
                if (locatingRealGps) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = NeuColors.CobaltDeep
                    )
                } else {
                    Icon(
                        Icons.Default.MyLocation,
                        contentDescription = stringResource(R.string.main_cd_locate_me),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            if (activeTab == 0) {
                NeuButton(
                    onClick = {
                        val pt = mapController.getCenterWgs84() ?: centerWgs84
                        staticPointWgs84 = pt
                        staticPointLabel = String.format(Locale.US, "%.5f, %.5f", pt.lat, pt.lon)
                        showToast(context.getString(R.string.main_toast_crosshair_locked))
                    },
                    tone = NeuTone.CORAL,
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.PinDrop, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.main_btn_pick_here), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                NeuFloatingSurface(
                    cornerRadius = 14.dp,
                    surfaceColor = NeuColors.SurfaceGlassStrong,
                    elevation = 7.dp,
                    onClick = { showPointsSheet = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Bookmarks,
                            contentDescription = stringResource(R.string.main_cd_saved_points),
                            tint = NeuColors.CobaltDeep,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            stringResource(R.string.main_btn_saved_points_short, savedPoints.size),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NeuColors.InkPrimary
                        )
                    }
                }
            } else {
                NeuButton(
                    onClick = {
                        val pt = mapController.getCenterWgs84() ?: centerWgs84
                        waypoints.add(pt)
                    },
                    tone = NeuTone.COBALT,
                    cornerRadius = 14.dp,
                    contentPadding = PaddingValues(horizontal = 13.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.AddLocationAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.main_btn_add_point, waypoints.size),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeuFloatingSurface(
                        cornerRadius = 13.dp,
                        surfaceColor = NeuColors.SurfaceGlassStrong,
                        elevation = 7.dp,
                        onClick = { if (waypoints.isNotEmpty()) waypoints.removeAt(waypoints.lastIndex) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Undo,
                                contentDescription = stringResource(R.string.main_cd_undo),
                                tint = NeuColors.InkPrimary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                stringResource(R.string.main_btn_undo),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NeuColors.InkPrimary
                            )
                        }
                    }
                    NeuFloatingSurface(
                        cornerRadius = 13.dp,
                        surfaceColor = NeuColors.SurfaceGlassStrong,
                        elevation = 7.dp,
                        onClick = {
                            waypoints.clear()
                            activeRouteProfileId = null
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                contentDescription = stringResource(R.string.main_btn_clear),
                                tint = NeuColors.Coral,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                stringResource(R.string.main_btn_clear),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NeuColors.Coral
                            )
                        }
                    }
                }

                NeuFloatingSurface(
                    cornerRadius = 13.dp,
                    surfaceColor = NeuColors.SurfaceGlassStrong,
                    elevation = 7.dp,
                    onClick = { loopEnabled = !loopEnabled }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NeuLedDot(color = NeuColors.LedGreen, lit = loopEnabled, size = 6.dp)
                        Spacer(Modifier.width(5.dp))
                        Icon(
                            Icons.Default.Loop,
                            contentDescription = stringResource(R.string.main_cd_loop),
                            tint = if (loopEnabled) NeuColors.EmeraldDeep else NeuColors.InkSecondary,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(
                                if (loopEnabled) R.string.main_btn_loop else R.string.main_btn_one_way
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (loopEnabled) NeuColors.EmeraldDeep else NeuColors.InkSecondary
                        )
                    }
                }
            }
        }

        // 5. Real Draggable Spring Bottom Sheet (capped strictly below 50% center crosshair!)
        NeuFloatingSurface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 10.dp, vertical = 8.dp)
                .height(currentSheetHeightDp),
            cornerRadius = 22.dp,
            surfaceColor = NeuColors.SurfaceGlassStrong,
            elevation = 12.dp
        ) {
            // Persistent Column + persistent drag-header Box so pointerInput NEVER unmounts mid-drag across 0..1!
            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(sheetDragModifier)
                ) {
                    if (expandFraction < 0.22f) {
                        // ===== COLLAPSED COMPACT PILL (~58dp) — draggable up OR tap 展开! =====
                        val collapsedAlpha = (1f - (expandFraction / 0.22f)).coerceIn(0f, 1f)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(collapsedHeightDp)
                                .graphicsLayer { alpha = collapsedAlpha }
                                .padding(horizontal = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val activeColor = when {
                                daemonStatus.active && daemonStatus.moving -> NeuColors.LedGreen
                                daemonStatus.active -> NeuColors.Amber
                                else -> NeuColors.InkMuted
                            }
                            NeuLedDot(
                                color = activeColor,
                                lit = daemonStatus.active,
                                isPulsing = daemonStatus.active,
                                size = 8.dp
                            )
                            Text(
                                text = stringResource(
                                    if (activeTab == 0) R.string.main_mode_static_short else R.string.main_mode_motion_short
                                ),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.InkPrimary,
                                maxLines = 1,
                                softWrap = false
                            )

                            if (daemonStatus.active) {
                                NeuButton(
                                    onClick = {
                                        scope.launch {
                                            KurokoRuntime.stopSimulation()
                                                .onSuccess { showToast(context.getString(R.string.main_toast_sim_stopped)) }
                                                .onFailure {
                                                    showToast(
                                                        context.getString(
                                                            R.string.main_toast_stop_failed,
                                                            it.message.orEmpty()
                                                        )
                                                    )
                                                }
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    tone = NeuTone.CORAL,
                                    cornerRadius = 12.dp,
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        stringResource(R.string.main_btn_stop_sim),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            } else {
                                NeuButton(
                                    onClick = {
                                        scope.launch {
                                            if (activeTab == 0) startStaticPoint() else startDynamicRoute(moving = true)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    tone = NeuTone.EMERALD,
                                    cornerRadius = 12.dp,
                                    contentPadding = PaddingValues(vertical = 8.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        stringResource(
                                            if (activeTab == 0) R.string.main_btn_start_static else R.string.main_btn_start_motion
                                        ),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }

                            NeuButton(
                                onClick = { showTargetSheet = true },
                                tone = NeuTone.NEUTRAL,
                                cornerRadius = 12.dp,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Apps,
                                    contentDescription = null,
                                    tint = NeuColors.CobaltDeep,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text("${targetApps.size}", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }

                            NeuButton(
                                onClick = { animateSheetTo(true) },
                                tone = NeuTone.NEUTRAL,
                                cornerRadius = 12.dp,
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.Default.ExpandLess,
                                    contentDescription = stringResource(R.string.main_cd_expand_sheet),
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    stringResource(R.string.main_btn_expand),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    } else {
                        // ===== EXPANDED DRAG HANDLE & COLLAPSE HEADER BAR =====
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { animateSheetTo(false) }
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Spacer(Modifier.width(48.dp))
                            Box(
                                modifier = Modifier
                                    .width(44.dp)
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(NeuColors.InsetDark)
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = stringResource(R.string.main_cd_collapse_sheet),
                                    tint = NeuColors.InkSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                                Text(
                                    text = stringResource(R.string.main_btn_collapse),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = NeuColors.InkSecondary
                                )
                            }
                        }
                    }
                }

                // Expanded body sits as a sibling outside sheetDragModifier so verticalScroll & sliders never conflict!
                if (expandFraction >= 0.22f) {
                    val bodyAlpha = ((expandFraction - 0.18f) / 0.35f).coerceIn(0f, 1f)
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
                    ) {
                        // 2-Tab Mode Switcher with Smooth Spring-Sliding Pill
                        NeuSegmentedControl(
                            options = listOf(
                                stringResource(R.string.main_mode_static),
                                stringResource(R.string.main_mode_motion)
                            ),
                            selectedIndex = activeTab,
                            onSelect = { idx -> activeTab = idx },
                            modifier = Modifier
                                .fillMaxWidth()
                                .graphicsLayer { alpha = bodyAlpha },
                            height = 38.dp
                        )

                        Spacer(Modifier.height(8.dp))

                        // Smooth AnimatedContent between Static & Dynamic Tabs (independently scrollable!)
                        AnimatedContent(
                            targetState = activeTab,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .graphicsLayer { alpha = bodyAlpha },
                            transitionSpec = {
                                val dir = if (targetState > initialState) 1 else -1
                                (fadeIn(tween(180)) + slideInHorizontally(tween(200)) { dir * it / 8 }) togetherWith
                                    (fadeOut(tween(130)) + slideOutHorizontally(tween(160)) { -dir * it / 8 })
                            },
                            label = "mode_tab_switch"
                        ) { tab ->
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                if (tab == 0) {
                                    // =============== TAB 1: Static Location ===============
                                    val displayPt = staticPointWgs84 ?: centerWgs84
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .neuInset(cornerRadius = 12.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                                            .padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = if (staticPointWgs84 != null) {
                                                        stringResource(R.string.main_static_selected, staticPointLabel)
                                                    } else {
                                                        stringResource(R.string.main_static_follow_crosshair)
                                                    },
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = NeuColors.InkPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = String.format(
                                                        Locale.US,
                                                        "WGS-84  %.6f, %.6f",
                                                        displayPt.lat,
                                                        displayPt.lon
                                                    ),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    color = NeuColors.InkSecondary
                                                )
                                            }
                                            if (staticPointWgs84 != null) {
                                                Text(
                                                    text = stringResource(R.string.main_btn_back_crosshair),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeuColors.CobaltDeep,
                                                    modifier = Modifier
                                                        .clickable {
                                                            staticPointWgs84 = null
                                                            staticPointLabel = context.getString(R.string.main_default_crosshair_label)
                                                            activePointProfileId = null
                                                        }
                                                        .padding(horizontal = 6.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }

                                    // Primary Actions: Hold Point / Stop
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        val isStaticRunning = daemonStatus.active
                                        NeuButton(
                                            onClick = { scope.launch { startStaticPoint() } },
                                            modifier = Modifier.weight(1.35f),
                                            tone = if (isStaticRunning) NeuTone.COBALT else NeuTone.EMERALD
                                        ) {
                                            Icon(
                                                if (isStaticRunning) Icons.Default.PinDrop else Icons.Default.PlayArrow,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                stringResource(
                                                    if (isStaticRunning) R.string.main_btn_update_static else R.string.main_btn_start_static
                                                ),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        NeuButton(
                                            onClick = {
                                                scope.launch {
                                                    KurokoRuntime.stopSimulation()
                                                        .onSuccess { showToast(context.getString(R.string.main_toast_static_stopped)) }
                                                        .onFailure {
                                                            showToast(
                                                                context.getString(
                                                                    R.string.main_toast_stop_failed,
                                                                    it.message.orEmpty()
                                                                )
                                                            )
                                                        }
                                                }
                                            },
                                            modifier = Modifier.weight(0.95f),
                                            tone = NeuTone.CORAL,
                                            enabled = daemonStatus.active
                                        ) {
                                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                stringResource(R.string.common_stop),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // Quick Drawers: Saved Points + Target Apps
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        NeuButton(
                                            onClick = { showPointsSheet = true },
                                            modifier = Modifier.weight(1f),
                                            tone = NeuTone.NEUTRAL,
                                            contentPadding = PaddingValues(vertical = 8.dp)
                                        ) {
                                            Icon(Icons.Default.Bookmarks, contentDescription = null, tint = NeuColors.Coral, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                stringResource(R.string.main_btn_saved_points, savedPoints.size),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        NeuButton(
                                            onClick = { showTargetSheet = true },
                                            modifier = Modifier.weight(1f),
                                            tone = NeuTone.NEUTRAL,
                                            contentPadding = PaddingValues(vertical = 8.dp)
                                        ) {
                                            Icon(Icons.Default.Apps, contentDescription = null, tint = NeuColors.CobaltDeep, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                stringResource(R.string.main_btn_target_apps, targetApps.size),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // Separate Static GPS Jitter Slider (independent of dynamicJitterM!)
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                stringResource(R.string.main_label_static_jitter),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = NeuColors.InkPrimary
                                            )
                                            Text(
                                                String.format(Locale.US, "±%.1f m", staticJitterM),
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = NeuColors.Coral
                                            )
                                        }
                                        NeuSlider(
                                            value = staticJitterM,
                                            onValueChange = { v -> staticJitterM = (v * 2f).roundToInt() / 2f },
                                            onValueChangeFinished = {
                                                if (daemonStatus.active && !daemonStatus.moving) {
                                                    scope.launch { daemon.setJitter(staticJitterM) }
                                                }
                                            },
                                            valueRange = 0f..8f,
                                            activeColor = NeuColors.Coral
                                        )
                                    }
                                } else {
                                    // =============== TAB 2: Dynamic Trajectory ===============
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val isActive = daemonStatus.active
                                        val isMoving = daemonStatus.active && daemonStatus.moving
                                        val isPaused = daemonStatus.active && !daemonStatus.moving
                                        NeuButton(
                                            onClick = {
                                                scope.launch {
                                                    if (!ensureVersionCompatForStart()) return@launch
                                                    if (isPaused && !isRunCompleted && daemonStatus.routeLengthM > 1f) {
                                                        daemon.start()
                                                            .onSuccess {
                                                                KurokoRuntime.refreshNow()
                                                                showToast(context.getString(R.string.main_toast_resumed))
                                                            }
                                                            .onFailure {
                                                                showToast(
                                                                    context.getString(
                                                                        R.string.main_toast_resume_failed,
                                                                        it.message.orEmpty()
                                                                    )
                                                                )
                                                            }
                                                    } else {
                                                        startDynamicRoute(moving = true)
                                                    }
                                                }
                                            },
                                            modifier = Modifier.weight(1.35f),
                                            tone = if (isMoving) NeuTone.COBALT else NeuTone.EMERALD
                                        ) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                text = stringResource(
                                                    when {
                                                        isMoving -> R.string.main_btn_update_route
                                                        isPaused && !isRunCompleted -> R.string.common_resume
                                                        else -> R.string.main_btn_start_motion
                                                    }
                                                ),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        NeuButton(
                                            onClick = {
                                                scope.launch {
                                                    KurokoRuntime.pauseSimulation()
                                                        .onSuccess { showToast(context.getString(R.string.main_toast_paused)) }
                                                        .onFailure {
                                                            showToast(
                                                                context.getString(
                                                                    R.string.main_toast_pause_failed,
                                                                    it.message.orEmpty()
                                                                )
                                                            )
                                                        }
                                                }
                                            },
                                            modifier = Modifier.weight(0.95f),
                                            tone = NeuTone.NEUTRAL,
                                            enabled = isMoving
                                        ) {
                                            Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(17.dp))
                                            Spacer(Modifier.width(3.dp))
                                            Text(
                                                stringResource(R.string.common_pause),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        NeuButton(
                                            onClick = {
                                                scope.launch {
                                                    KurokoRuntime.stopSimulation()
                                                        .onSuccess { showToast(context.getString(R.string.main_toast_motion_stopped)) }
                                                        .onFailure {
                                                            showToast(
                                                                context.getString(
                                                                    R.string.main_toast_stop_failed,
                                                                    it.message.orEmpty()
                                                                )
                                                            )
                                                        }
                                                }
                                            },
                                            modifier = Modifier.weight(0.85f),
                                            tone = NeuTone.CORAL,
                                            enabled = isActive
                                        ) {
                                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(17.dp))
                                            Spacer(Modifier.width(2.dp))
                                            Text(
                                                stringResource(R.string.common_stop),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // Quick Drawers: Saved Route Profiles + Target Apps
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        NeuButton(
                                            onClick = { showRoutesSheet = true },
                                            modifier = Modifier.weight(1f),
                                            tone = NeuTone.NEUTRAL,
                                            contentPadding = PaddingValues(vertical = 8.dp)
                                        ) {
                                            Icon(Icons.Default.Route, contentDescription = null, tint = NeuColors.CobaltDeep, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                stringResource(R.string.main_btn_saved_routes, savedRoutes.size),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        NeuButton(
                                            onClick = { showTargetSheet = true },
                                            modifier = Modifier.weight(1f),
                                            tone = NeuTone.NEUTRAL,
                                            contentPadding = PaddingValues(vertical = 8.dp)
                                        ) {
                                            Icon(Icons.Default.Apps, contentDescription = null, tint = NeuColors.CobaltDeep, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(5.dp))
                                            Text(
                                                stringResource(R.string.main_btn_target_apps, targetApps.size),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }

                                    // Planned Distance & Estimated Time Preview Bar
                                    if (waypoints.size >= 2) {
                                        val estSec = previewPlannedDistM / (speedMps.coerceAtLeast(0.3f) * 0.97)
                                        val previewLine = if (hasFiniteGoalPreview) {
                                            stringResource(
                                                R.string.main_preview_finite,
                                                CoordTransform.formatDistance(previewPlannedDistM),
                                                formatDurationLocalized(context, estSec)
                                            )
                                        } else {
                                            stringResource(
                                                R.string.main_preview_loop,
                                                CoordTransform.formatDistance(routeLapMeters),
                                                formatDurationClock(estSec)
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .neuInset(cornerRadius = 10.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                                                .padding(horizontal = 10.dp, vertical = 6.dp)
                                        ) {
                                            Text(
                                                text = previewLine,
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                                color = NeuColors.InkPrimary
                                            )
                                        }
                                    }

                                    // Repeat Count + Stepper + Target Distance km
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            val repeatValText = if (maxLaps == 0) {
                                                stringResource(R.string.main_repeat_infinite)
                                            } else {
                                                stringResource(R.string.main_repeat_times, maxLaps)
                                            }
                                            Text(
                                                text = stringResource(R.string.main_repeat_label, repeatValText),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = NeuColors.InkPrimary
                                            )
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                NeuSquareIconButton(
                                                    onClick = { maxLaps = (maxLaps - 1).coerceAtLeast(0) },
                                                    size = 28.dp,
                                                    cornerRadius = 9.dp
                                                ) {
                                                    Icon(
                                                        Icons.Default.Remove,
                                                        contentDescription = stringResource(R.string.main_cd_dec_laps),
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                }
                                                NeuSquareIconButton(
                                                    onClick = { maxLaps = (maxLaps + 1).coerceAtMost(999) },
                                                    size = 28.dp,
                                                    cornerRadius = 9.dp
                                                ) {
                                                    Icon(
                                                        Icons.Default.Add,
                                                        contentDescription = stringResource(R.string.main_cd_inc_laps),
                                                        modifier = Modifier.size(15.dp)
                                                    )
                                                }
                                                NeuChip(
                                                    label = if (targetDistanceKm > 0f) {
                                                        stringResource(R.string.main_target_km_set, targetDistanceKm)
                                                    } else {
                                                        stringResource(R.string.main_target_km_unset)
                                                    },
                                                    selected = targetDistanceKm > 0f,
                                                    accentColor = NeuColors.AmberDeep,
                                                    onClick = { showTargetDistDialog = true }
                                                )
                                            }
                                        }
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            REPEAT_PRESET_VALUES.forEach { lapsVal ->
                                                val chipLabel = if (lapsVal == 0) {
                                                    stringResource(R.string.main_repeat_infinite_short)
                                                } else {
                                                    stringResource(R.string.main_repeat_chip_times, lapsVal)
                                                }
                                                NeuChip(
                                                    label = chipLabel,
                                                    selected = maxLaps == lapsVal,
                                                    onClick = { maxLaps = lapsVal }
                                                )
                                            }
                                            NeuChip(
                                                label = stringResource(R.string.main_chip_custom),
                                                selected = maxLaps !in REPEAT_PRESET_VALUES,
                                                onClick = { showCustomLapsDialog = true }
                                            )
                                        }
                                    }

                                    // Layer 3: Customizable Speed Presets (Tap to apply, Long-press to save current!)
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .horizontalScroll(rememberScrollState()),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            speedPresets.forEachIndexed { idx, preset ->
                                                val presetLabel = preset.displayName(context)
                                                val isSelected = abs(speedMps - preset.speedMps) < 0.05f &&
                                                    abs(paceVarRatio - preset.paceVarRatio) < 0.005f &&
                                                    carryMode == preset.carryMode &&
                                                    motionMode == preset.motionMode
                                                NeuChip(
                                                    label = String.format(Locale.US, "%s %.1f", presetLabel, preset.speedMps),
                                                    selected = isSelected,
                                                    onClick = {
                                                        speedMps = preset.speedMps
                                                        paceVarRatio = preset.paceVarRatio
                                                        carryMode = preset.carryMode
                                                        motionMode = preset.motionMode
                                                        KurokoRuntime.syncMotionStateFromUi(
                                                            speedMps,
                                                            paceVarRatio,
                                                            motionMode,
                                                            carryMode
                                                        )
                                                        scope.launch {
                                                            daemon.setSpeed(speedMps)
                                                            daemon.setPaceVar(paceVarRatio)
                                                            daemon.setMode(motionMode, carryMode)
                                                            KurokoRuntime.refreshNow()
                                                        }
                                                    },
                                                    onLongClick = {
                                                        val updatedPreset = preset.copy(
                                                            speedMps = speedMps,
                                                            paceVarRatio = paceVarRatio,
                                                            carryMode = carryMode,
                                                            motionMode = motionMode
                                                        )
                                                        val nextList = speedPresets.toMutableList().apply {
                                                            this[idx] = updatedPreset
                                                        }
                                                        KurokoRuntime.updateSpeedPresets(context, nextList)
                                                        showToast(
                                                            context.getString(
                                                                R.string.main_toast_preset_saved,
                                                                presetLabel,
                                                                speedMps,
                                                                (paceVarRatio * 100f).roundToInt()
                                                            )
                                                        )
                                                    }
                                                )
                                            }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                stringResource(R.string.main_label_avg_speed),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = NeuColors.InkPrimary
                                            )
                                            Text(
                                                text = String.format(
                                                    Locale.US,
                                                    "%.1f m/s · %s",
                                                    speedMps,
                                                    CoordTransform.formatPace(speedMps)
                                                ),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = NeuColors.CobaltDeep
                                            )
                                        }
                                        NeuSlider(
                                            value = speedMps.coerceIn(0.5f, 8.0f),
                                            onValueChange = { v ->
                                                speedMps = (v * 10f).roundToInt() / 10f
                                                motionMode = KurokoProtocol.MOTION_AUTO
                                            },
                                            onValueChangeFinished = {
                                                KurokoRuntime.syncMotionStateFromUi(speedMps, paceVarRatio, motionMode, carryMode)
                                                scope.launch {
                                                    daemon.setSpeed(speedMps)
                                                    daemon.setMode(motionMode, carryMode)
                                                    KurokoRuntime.refreshNow()
                                                }
                                            },
                                            valueRange = 0.5f..8.0f,
                                            activeColor = NeuColors.CobaltDeep
                                        )
                                    }

                                    // Configurable Pace Fluctuation Range (0% ~ 20%)
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                stringResource(R.string.main_label_pace_var),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = NeuColors.InkPrimary
                                            )
                                            Text(
                                                text = formatPaceRangePreview(context, speedMps, paceVarRatio),
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.SemiBold,
                                                color = NeuColors.EmeraldDeep,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        NeuSlider(
                                            value = paceVarRatio * 100f,
                                            onValueChange = { pct -> paceVarRatio = (pct.roundToInt() / 100f).coerceIn(0f, 0.20f) },
                                            onValueChangeFinished = {
                                                KurokoRuntime.syncMotionStateFromUi(speedMps, paceVarRatio, motionMode, carryMode)
                                                scope.launch {
                                                    daemon.setPaceVar(paceVarRatio)
                                                    KurokoRuntime.refreshNow()
                                                }
                                            },
                                            valueRange = 0f..20f,
                                            activeColor = NeuColors.Emerald
                                        )
                                    }

                                    // Carry Mode Segmented Control + Separate Dynamic GPS Jitter Slider
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        NeuSegmentedControl(
                                            options = listOf(
                                                stringResource(R.string.main_carry_hand),
                                                stringResource(R.string.main_carry_pocket)
                                            ),
                                            selectedIndex = if (carryMode == KurokoProtocol.CARRY_HAND_SWING) 0 else 1,
                                            onSelect = { idx ->
                                                carryMode = if (idx == 0) {
                                                    KurokoProtocol.CARRY_HAND_SWING
                                                } else {
                                                    KurokoProtocol.CARRY_POCKET
                                                }
                                                KurokoRuntime.syncMotionStateFromUi(speedMps, paceVarRatio, motionMode, carryMode)
                                                scope.launch {
                                                    daemon.setMode(motionMode, carryMode)
                                                    KurokoRuntime.refreshNow()
                                                }
                                            },
                                            modifier = Modifier.width(156.dp),
                                            height = 34.dp,
                                            fontSize = 11.sp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Text(
                                                    stringResource(R.string.main_label_dynamic_jitter),
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = NeuColors.InkPrimary
                                                )
                                                Text(
                                                    String.format(Locale.US, "±%.1fm", dynamicJitterM),
                                                    fontSize = 11.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = FontWeight.Bold,
                                                    color = NeuColors.CobaltDeep
                                                )
                                            }
                                            NeuSlider(
                                                value = dynamicJitterM,
                                                onValueChange = { v -> dynamicJitterM = (v * 2f).roundToInt() / 2f },
                                                onValueChangeFinished = {
                                                    if (daemonStatus.active && daemonStatus.moving) {
                                                        scope.launch { daemon.setJitter(dynamicJitterM) }
                                                    }
                                                },
                                                valueRange = 0f..8f,
                                                activeColor = NeuColors.Cobalt
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Snackbar Host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 152.dp)
        )
    }

    // ===== Sheets & Dialogs =====

    if (showTargetSheet) {
        TargetAppSheet(
            initialTargets = targetApps,
            btShmOk = daemonStatus.btShm,
            onDismiss = { showTargetSheet = false },
            onApplyTargets = { updated ->
                scope.launch {
                    val (activeTargets, res) = KurokoRuntime.syncTargetsToDaemon(context, updated)
                    targetApps = activeTargets
                    if (res.isSuccess) showToast(context.getString(R.string.main_toast_targets_applied, activeTargets.size))
                    else showToast(
                        context.getString(
                            R.string.main_toast_targets_sync_failed,
                            res.exceptionOrNull()?.message.orEmpty()
                        )
                    )
                }
            },
            onOpenRfConfig = { showRfConfigDialog = true }
        )
    }

    if (showPointsSheet) {
        SavedPointsSheet(
            points = savedPoints,
            activeProfileId = activePointProfileId,
            currentPointWgs84 = staticPointWgs84 ?: (mapController.getCenterWgs84() ?: centerWgs84),
            currentStaticJitterM = staticJitterM,
            onDismiss = { showPointsSheet = false },
            onSaveAsNew = { name, pt ->
                val item = SavedPoint(
                    name = name,
                    latWgs84 = pt.lat,
                    lonWgs84 = pt.lon,
                    altM = pt.alt,
                    jitterM = staticJitterM,
                    updatedAtMs = System.currentTimeMillis()
                )
                savedPoints = listOf(item) + savedPoints
                activePointProfileId = item.id
                staticPointLabel = item.name
                pointStore.savePoints(savedPoints)
                showToast(context.getString(R.string.main_toast_point_saved, name))
            },
            onUpdateExisting = { existing, name, pt ->
                val updated = existing.copy(
                    name = name,
                    latWgs84 = pt.lat,
                    lonWgs84 = pt.lon,
                    altM = pt.alt,
                    jitterM = staticJitterM,
                    updatedAtMs = System.currentTimeMillis()
                )
                savedPoints = listOf(updated) + savedPoints.filterNot { it.id == existing.id }
                staticPointLabel = updated.name
                pointStore.savePoints(savedPoints)
                showToast(context.getString(R.string.main_toast_point_updated, name))
            },
            onSelectPoint = { pt ->
                val gp = GeoPoint(pt.latWgs84, pt.lonWgs84, pt.altM)
                staticPointWgs84 = gp
                staticPointLabel = pt.name
                staticJitterM = pt.jitterM
                activePointProfileId = pt.id
                mapController.flyToWgs84(pt.latWgs84, pt.lonWgs84, 16.8)
                showToast(context.getString(R.string.main_toast_point_loaded, pt.name, pt.jitterM))
            },
            onDeletePoint = { pt ->
                savedPoints = savedPoints.filterNot { it.id == pt.id }
                if (activePointProfileId == pt.id) activePointProfileId = null
                pointStore.savePoints(savedPoints)
            },
            onExportJson = { pointStore.exportPointsJson(savedPoints) },
            onImportJson = { raw ->
                pointStore.importPointsJson(raw).map { imported ->
                    val byId = savedPoints.associateBy { it.id }.toMutableMap()
                    for (p in imported) {
                        byId[p.id] = p
                    }
                    savedPoints = byId.values.sortedByDescending { it.updatedAtMs }
                    pointStore.savePoints(savedPoints)
                    imported.size
                }
            },
            onNotify = { msg -> showToast(msg) }
        )
    }

    if (showRoutesSheet) {
        SavedRoutesSheet(
            routes = savedRoutes,
            activeProfileId = activeRouteProfileId,
            currentWaypoints = waypointsSnapshot,
            currentLoop = loopEnabled,
            currentMaxLaps = maxLaps,
            currentSpeedMps = speedMps,
            currentPaceVarRatio = paceVarRatio,
            currentCarryMode = carryMode,
            currentJitterM = dynamicJitterM,
            onDismiss = { showRoutesSheet = false },
            onSaveAsNew = { name ->
                val newRoute = SavedRoute(
                    name = name,
                    waypoints = waypoints.toList(),
                    loop = loopEnabled,
                    maxLaps = maxLaps,
                    targetDistanceKm = targetDistanceKm,
                    speedMps = speedMps,
                    paceVarRatio = paceVarRatio,
                    motionMode = motionMode,
                    carryMode = carryMode,
                    jitterM = dynamicJitterM,
                    updatedAtMs = System.currentTimeMillis()
                )
                savedRoutes = listOf(newRoute) + savedRoutes
                activeRouteProfileId = newRoute.id
                routeStore.saveRoutes(savedRoutes)
                showToast(context.getString(R.string.main_toast_route_saved, name))
            },
            onUpdateExisting = { existing, name ->
                val updated = existing.copy(
                    name = name,
                    waypoints = waypoints.toList(),
                    loop = loopEnabled,
                    maxLaps = maxLaps,
                    targetDistanceKm = targetDistanceKm,
                    speedMps = speedMps,
                    paceVarRatio = paceVarRatio,
                    motionMode = motionMode,
                    carryMode = carryMode,
                    jitterM = dynamicJitterM,
                    updatedAtMs = System.currentTimeMillis()
                )
                savedRoutes = listOf(updated) + savedRoutes.filterNot { it.id == existing.id }
                routeStore.saveRoutes(savedRoutes)
                showToast(context.getString(R.string.main_toast_route_updated, name))
            },
            onLoadFullProfile = { route ->
                waypoints.clear()
                waypoints.addAll(route.waypoints)
                loopEnabled = route.loop
                maxLaps = route.maxLaps
                targetDistanceKm = route.targetDistanceKm
                speedMps = route.speedMps
                paceVarRatio = route.paceVarRatio
                motionMode = route.motionMode
                carryMode = route.carryMode
                dynamicJitterM = route.jitterM
                activeRouteProfileId = route.id
                KurokoRuntime.syncMotionStateFromUi(
                    route.speedMps,
                    route.paceVarRatio,
                    route.motionMode,
                    route.carryMode
                )
                mapController.fitRouteWgs84(route.waypoints)
                showToast(context.getString(R.string.main_toast_route_loaded_full, route.name))
            },
            onLoadRouteOnly = { route ->
                waypoints.clear()
                waypoints.addAll(route.waypoints)
                loopEnabled = route.loop
                activeRouteProfileId = route.id
                mapController.fitRouteWgs84(route.waypoints)
                showToast(context.getString(R.string.main_toast_route_loaded_only, route.name))
            },
            onDeleteRoute = { route ->
                savedRoutes = savedRoutes.filterNot { it.id == route.id }
                if (activeRouteProfileId == route.id) activeRouteProfileId = null
                routeStore.saveRoutes(savedRoutes)
            },
            onExportJson = { routeStore.exportRoutesJson(savedRoutes) },
            onImportJson = { raw ->
                routeStore.importRoutesJson(raw).map { imported ->
                    val byId = savedRoutes.associateBy { it.id }.toMutableMap()
                    for (r in imported) {
                        byId[r.id] = r
                    }
                    savedRoutes = byId.values.sortedByDescending { it.updatedAtMs }
                    routeStore.saveRoutes(savedRoutes)
                    imported.size
                }
            },
            onNotify = { msg -> showToast(msg) }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            tiandituToken = settings.tiandituToken,
            amapWebKey = settings.amapWebKey,
            daemonConnected = daemonConnected,
            locShmOk = daemonStatus.locShm,
            phoneShmOk = daemonStatus.phoneShm,
            sensorShmOk = daemonStatus.sensorShm,
            btShmOk = daemonStatus.btShm,
            sensorDiag = daemonStatus.sensorDiag,
            simStatus = daemonStatus,
            versionCompat = versionCompat,
            simActive = daemonStatus.active,
            simCadenceSpm = daemonStatus.cadenceSpm,
            savedTargets = targetApps.map { DaemonTargetSpec(it.uid, it.policyFlags) },
            daemonMessage = daemonMessage,
            overlayEnabled = overlayRunning || overlayPrefs.enabled,
            overlayAutoHideInApp = overlayPrefs.autoHideInApp,
            overlaySecureFlag = overlayPrefs.secureFlag,
            appLanguageTag = appLanguageTag,
            onSelectLanguage = { tag ->
                KurokoRuntime.setAppLanguage(context, tag)
            },
            onToggleOverlay = { enable -> toggleOverlay(enable) },
            onToggleOverlayAutoHide = { autoHide ->
                KurokoRuntime.updateOverlayPrefs(context, autoHideInApp = autoHide)
            },
            onToggleOverlaySecure = { secure ->
                KurokoRuntime.updateOverlayPrefs(context, secureFlag = secure)
                showToast(
                    context.getString(
                        if (secure) R.string.main_toast_secure_on else R.string.main_toast_secure_off
                    )
                )
            },
            onDismiss = { showSettingsDialog = false },
            onSaveKeys = { tdt, amap ->
                settings = settings.copy(tiandituToken = tdt, amapWebKey = amap)
                showToast(context.getString(R.string.main_toast_keys_saved))
            },
            onReconnectDaemon = {
                scope.launch {
                    daemon.resetConnection()
                    KurokoRuntime.invalidateTargetSync()
                    KurokoRuntime.invalidateRfSync()
                    daemon.ping()
                        .onSuccess {
                            KurokoRuntime.refreshNow()
                            showToast(context.getString(R.string.main_toast_reconnected))
                        }
                        .onFailure {
                            showToast(
                                context.getString(
                                    R.string.main_toast_reconnect_failed,
                                    it.message.orEmpty()
                                )
                            )
                        }
                }
            },
            onResetSteps = {
                scope.launch {
                    KurokoRuntime.resetSteps()
                        .onSuccess { showToast(context.getString(R.string.main_toast_steps_reset)) }
                        .onFailure {
                            showToast(
                                context.getString(
                                    R.string.main_toast_reset_failed,
                                    it.message.orEmpty()
                                )
                            )
                        }
                }
            },
            onResetSpeedPresets = {
                val defaults = defaultSpeedPresets()
                KurokoRuntime.updateSpeedPresets(context, defaults)
                showToast(context.getString(R.string.main_toast_presets_restored))
            },
            onOpenHelp = { showHelpSheet = true },
            onOpenRfConfig = { showRfConfigDialog = true },
            onOpenDiagnostics = { showDiagnosticsDialog = true },
            autoCheckUpdate = autoCheckUpdate,
            updateCheckBusy = updateCheckBusy,
            updateStatusText = updateStatusText,
            onToggleAutoCheckUpdate = { enabled ->
                autoCheckUpdate = enabled
                UpdateChecker.setAutoCheckEnabled(context, enabled)
            },
            onManualCheckUpdate = {
                if (!updateCheckBusy) {
                    updateCheckBusy = true
                    updateStatusText = null
                    scope.launch {
                        try {
                            when (val outcome = UpdateChecker.checkForUpdate(context, manual = true)) {
                                is UpdateCheckOutcome.UpdateAvailable -> {
                                    updateStatusText = context.getString(
                                        R.string.update_status_found_fmt,
                                        outcome.info.tagName
                                    )
                                    showSettingsDialog = false
                                    pendingUpdateInfo = outcome.info
                                }
                                is UpdateCheckOutcome.UpToDate -> {
                                    val msg = context.getString(
                                        R.string.update_status_up_to_date_fmt,
                                        outcome.currentVersion
                                    )
                                    updateStatusText = msg
                                    showToast(msg)
                                }
                                is UpdateCheckOutcome.Failed -> {
                                    val msg = context.getString(
                                        R.string.update_status_failed_fmt,
                                        outcome.message
                                    )
                                    updateStatusText = msg
                                    showToast(msg)
                                }
                                is UpdateCheckOutcome.Disabled -> Unit
                            }
                        } finally {
                            updateCheckBusy = false
                        }
                    }
                }
            }
        )
    }

    pendingUpdateInfo?.let { updateInfo ->
        UpdateDialog(
            info = updateInfo,
            onUpdateNow = { targetUrl ->
                pendingUpdateInfo = null
                openExternalUrl(targetUrl)
            },
            onSkipVersion = { tag ->
                UpdateChecker.setIgnoredTag(context, tag)
                pendingUpdateInfo = null
                showToast(context.getString(R.string.update_toast_skipped_fmt, tag))
            },
            onDismiss = { pendingUpdateInfo = null }
        )
    }

    if (showRfConfigDialog) {
        RfConfigDialog(
            initialConfig = rfConfig,
            daemonSnap = daemonSnap,
            mapCenterWgs84 = mapController.getCenterWgs84() ?: centerWgs84,
            onDismiss = { showRfConfigDialog = false },
            onSaveAndSync = { updated ->
                scope.launch {
                    val res = KurokoRuntime.saveAndSyncRfConfig(context, updated)
                    showToast(
                        context.getString(
                            if (res.isSuccess) R.string.rf_sync_ok else R.string.rf_sync_offline_saved
                        )
                    )
                }
            },
            onSetGmsEnabled = { enabled ->
                KurokoRuntime.setGmsEnabled(enabled)
            },
            onNotify = { msg -> showToast(msg) }
        )
    }

    if (showDiagnosticsDialog) {
        DiagnosticsDialog(
            daemonSnap = daemonSnap,
            versionCompat = versionCompat,
            motionState = runtimeMotion,
            overlayPrefs = overlayPrefs,
            savedTargets = targetApps,
            appLangTag = appLanguageTag,
            tiandituToken = settings.tiandituToken,
            amapWebKey = settings.amapWebKey,
            openCellIdToken = rfConfig.openCellIdToken,
            onDismiss = { showDiagnosticsDialog = false },
            onNotify = { msg -> showToast(msg) }
        )
    }

    if (showOverlayPermDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayPermDialog = false },
            containerColor = NeuColors.Surface,
            titleContentColor = NeuColors.InkPrimary,
            textContentColor = NeuColors.InkPrimary,
            icon = {
                Icon(Icons.Default.PictureInPictureAlt, contentDescription = null, tint = NeuColors.CobaltDeep)
            },
            title = { Text(stringResource(R.string.dialog_overlay_perm_title), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = stringResource(R.string.dialog_overlay_perm_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuColors.InkSecondary
                )
            },
            confirmButton = {
                NeuButton(
                    onClick = {
                        showOverlayPermDialog = false
                        pendingOverlayAfterPermission = true
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        runCatching { context.startActivity(intent) }
                            .onFailure {
                                pendingOverlayAfterPermission = false
                                showToast(context.getString(R.string.main_toast_overlay_settings_failed))
                            }
                    },
                    tone = NeuTone.COBALT,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        stringResource(R.string.dialog_overlay_perm_confirm),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayPermDialog = false }) {
                    Text(stringResource(R.string.dialog_overlay_perm_dismiss), color = NeuColors.InkSecondary)
                }
            }
        )
    }

    if (showHelpSheet) {
        HelpSheet(onDismiss = { showHelpSheet = false })
    }

    if (showVersionLockDialog) {
        VersionLockDialog(
            simStatus = daemonStatus,
            versionCompat = versionCompat,
            onDismiss = { showVersionLockDialog = false },
            onOpenSettings = {
                showVersionLockDialog = false
                showSettingsDialog = true
            },
            onOpenDiagnostics = {
                showVersionLockDialog = false
                showDiagnosticsDialog = true
            }
        )
    }

    if (showTargetDistDialog) {
        TargetDistanceDialog(
            currentKm = targetDistanceKm,
            onDismiss = { showTargetDistDialog = false },
            onSelectKm = { km ->
                targetDistanceKm = km.coerceAtLeast(0f)
                showTargetDistDialog = false
                showToast(
                    if (targetDistanceKm > 0f) {
                        context.getString(R.string.main_toast_target_km_set, targetDistanceKm)
                    } else {
                        context.getString(R.string.main_toast_target_km_cleared)
                    }
                )
            }
        )
    }

    if (showCustomLapsDialog) {
        CustomRepeatLapsDialog(
            currentLaps = maxLaps,
            onDismiss = { showCustomLapsDialog = false },
            onConfirm = { laps ->
                maxLaps = laps.coerceIn(0, 999)
                showCustomLapsDialog = false
                showToast(
                    if (maxLaps == 0) {
                        context.getString(R.string.main_toast_repeat_infinite)
                    } else {
                        context.getString(R.string.main_toast_repeat_set, maxLaps)
                    }
                )
            }
        )
    }
}

@Composable
private fun DualRingCrosshairReticle(accentColor: Color) {
    Canvas(modifier = Modifier.size(46.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = 12.dp.toPx()
        val gap = 4.5.dp.toPx()
        val arm = 19.dp.toPx()

        drawCircle(
            color = Color.White.copy(alpha = 0.94f),
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 4.6.dp.toPx())
        )
        drawLine(Color.White.copy(alpha = 0.94f), Offset(cx - arm, cy), Offset(cx - gap, cy), 4.4.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.94f), Offset(cx + gap, cy), Offset(cx + arm, cy), 4.4.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.94f), Offset(cx, cy - arm), Offset(cx, cy - gap), 4.4.dp.toPx(), StrokeCap.Round)
        drawLine(Color.White.copy(alpha = 0.94f), Offset(cx, cy + gap), Offset(cx, cy + arm), 4.4.dp.toPx(), StrokeCap.Round)

        drawCircle(
            color = accentColor,
            radius = radius,
            center = Offset(cx, cy),
            style = Stroke(width = 2.0.dp.toPx())
        )
        drawLine(accentColor, Offset(cx - arm, cy), Offset(cx - gap, cy), 2.0.dp.toPx(), StrokeCap.Round)
        drawLine(accentColor, Offset(cx + gap, cy), Offset(cx + arm, cy), 2.0.dp.toPx(), StrokeCap.Round)
        drawLine(accentColor, Offset(cx, cy - arm), Offset(cx, cy - gap), 2.0.dp.toPx(), StrokeCap.Round)
        drawLine(accentColor, Offset(cx, cy + gap), Offset(cx, cy + arm), 2.0.dp.toPx(), StrokeCap.Round)

        drawCircle(color = Color.White, radius = 3.5.dp.toPx(), center = Offset(cx, cy))
        drawCircle(color = accentColor, radius = 2.2.dp.toPx(), center = Offset(cx, cy))
    }
}

@Composable
private fun BraunHudMetric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = NeuColors.InkSecondary
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = NeuColors.InkPrimary
        )
    }
}

@Composable
private fun TargetDistanceDialog(
    currentKm: Float,
    onDismiss: () -> Unit,
    onSelectKm: (Float) -> Unit
) {
    var customInput by remember(currentKm) {
        mutableStateOf(if (currentKm > 0f) String.format(Locale.US, "%.1f", currentKm) else "")
    }
    val presets = listOf(
        0f to stringResource(R.string.dialog_target_dist_unlimited),
        2f to "2 km",
        3f to "3 km",
        5f to "5 km",
        10f to "10 km"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeuColors.Surface,
        titleContentColor = NeuColors.InkPrimary,
        textContentColor = NeuColors.InkPrimary,
        title = { Text(stringResource(R.string.dialog_target_dist_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = stringResource(R.string.dialog_target_dist_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuColors.InkSecondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { (km, label) ->
                        NeuChip(
                            label = label,
                            selected = currentKm == km,
                            onClick = { onSelectKm(km) }
                        )
                    }
                }
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.dialog_target_dist_input_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }
        },
        confirmButton = {
            NeuButton(
                onClick = { onSelectKm(customInput.trim().toFloatOrNull() ?: 0f) },
                tone = NeuTone.COBALT,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.common_ok), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = NeuColors.InkSecondary)
            }
        }
    )
}

@Composable
private fun CustomRepeatLapsDialog(
    currentLaps: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var input by remember(currentLaps) { mutableStateOf(if (currentLaps > 0) currentLaps.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeuColors.Surface,
        titleContentColor = NeuColors.InkPrimary,
        textContentColor = NeuColors.InkPrimary,
        title = { Text(stringResource(R.string.dialog_custom_laps_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.dialog_custom_laps_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuColors.InkSecondary
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = { s -> input = s.filter { it.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.dialog_custom_laps_input_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            NeuButton(
                onClick = { onConfirm(input.toIntOrNull() ?: 0) },
                tone = NeuTone.COBALT,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.common_ok), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = NeuColors.InkSecondary)
            }
        }
    )
}

private fun formatPaceRangePreview(context: Context, speedMps: Float, ratio: Float): String {
    val pct = (ratio * 100f).roundToInt()
    val avgPace = CoordTransform.formatPace(speedMps)
    if (pct <= 0 || speedMps < 0.3f) {
        return context.getString(R.string.main_pace_constant, avgPace)
    }
    val fastSpeed = speedMps * (1f + ratio)
    val slowSpeed = (speedMps * (1f - ratio)).coerceAtLeast(0.35f)
    val fastPace = CoordTransform.formatPace(fastSpeed).removeSuffix("/km")
    val slowPace = CoordTransform.formatPace(slowSpeed).removeSuffix("/km")
    return context.getString(R.string.main_pace_range, avgPace, pct, fastPace, slowPace)
}

private fun computePlannedDistanceM(
    routeLengthM: Double,
    maxDistanceM: Float,
    maxLaps: Int
): Double {
    val maxDist = maxDistanceM.toDouble()
    return when {
        maxDist > 0.0 && maxLaps > 0 -> minOf(maxDist, routeLengthM * maxLaps)
        maxDist > 0.0 -> maxDist
        maxLaps > 0 -> routeLengthM * maxLaps
        else -> routeLengthM
    }
}

private fun formatDurationClock(totalSec: Double): String {
    val s = totalSec.roundToInt().coerceIn(0, 359999)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(Locale.US, "%02d:%02d", m, sec)
    }
}

private fun formatDurationLocalized(context: Context, totalSec: Double): String {
    val s = totalSec.roundToInt().coerceIn(0, 359999)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        context.getString(R.string.main_duration_hms, h, m, sec)
    } else {
        context.getString(R.string.main_duration_ms, m, sec)
    }
}

@SuppressLint("MissingPermission")
private fun requestRealDeviceLocationFast(
    context: Context,
    lm: LocationManager,
    onImmediateCached: (Location) -> Unit,
    onFreshResult: (Location?, Boolean) -> Unit
) {
    val cached = bestLastKnownLocation(lm)
    val hadCached = cached != null
    if (cached != null) {
        onImmediateCached(cached)
    }

    val providers = listOf(
        LocationManager.FUSED_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER
    ).filter { p -> runCatching { lm.isProviderEnabled(p) }.getOrDefault(false) }

    if (providers.isEmpty()) {
        onFreshResult(null, hadCached)
        return
    }

    val delivered = AtomicBoolean(false)
    val signals = mutableListOf<CancellationSignal>()
    val mainHandler = Handler(Looper.getMainLooper())
    val executor = ContextCompat.getMainExecutor(context)

    val timeoutRunnable = Runnable {
        if (delivered.compareAndSet(false, true)) {
            signals.forEach { runCatching { it.cancel() } }
            onFreshResult(null, hadCached)
        }
    }
    mainHandler.postDelayed(timeoutRunnable, 6000L)

    var remaining = providers.size
    for (provider in providers) {
        val sig = CancellationSignal()
        signals.add(sig)
        runCatching {
            lm.getCurrentLocation(provider, sig, executor) { loc ->
                remaining--
                if (loc != null && delivered.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    signals.forEach { if (it !== sig) runCatching { it.cancel() } }
                    onFreshResult(loc, hadCached)
                } else if (remaining <= 0 && delivered.compareAndSet(false, true)) {
                    mainHandler.removeCallbacks(timeoutRunnable)
                    onFreshResult(null, hadCached)
                }
            }
        }.onFailure {
            remaining--
            if (remaining <= 0 && delivered.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeoutRunnable)
                onFreshResult(null, hadCached)
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun bestLastKnownLocation(lm: LocationManager): Location? {
    val candidates = listOf(
        LocationManager.FUSED_PROVIDER,
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )
    return candidates
        .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
        .maxByOrNull { it.time }
}

@Composable
private fun VersionLockDialog(
    simStatus: SimStatus,
    versionCompat: VersionCompatInfo,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit
) {
    val isReboot = versionCompat.state == VersionCompatState.REBOOT_REQUIRED
    val accent = if (isReboot) NeuColors.AmberDeep else NeuColors.Coral
    val appVer = KurokoProtocol.APP_VERSION_NAME
    val appCode = KurokoProtocol.APP_VERSION_CODE
    val appProto = KurokoProtocol.IPC_PROTOCOL_VERSION
    val appShm = KurokoProtocol.SHM_SCHEMA_VERSION

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeuColors.Surface,
        titleContentColor = NeuColors.InkPrimary,
        textContentColor = NeuColors.InkPrimary,
        icon = {
            Icon(Icons.Default.Lock, contentDescription = null, tint = accent)
        },
        title = {
            Text(
                text = versionCompat.headline,
                fontWeight = FontWeight.Bold,
                color = NeuColors.InkPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = versionCompat.detail,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = NeuColors.InkSecondary
                )

                // 5-Row Four-Tier Version Comparison Matrix (Braun Recessed Cool-Sage LCD Well)
                val legacyText = stringResource(R.string.common_legacy)
                val offlineTag = stringResource(R.string.lock_tag_offline)
                val rebootTag = stringResource(R.string.lock_tag_reboot)
                val mismatchTag = stringResource(R.string.lock_tag_mismatch)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuInset(cornerRadius = 12.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        VersionCompareRow(
                            label = stringResource(R.string.lock_row_app),
                            value = "v$appVer (#$appCode)",
                            ok = true,
                            statusTag = stringResource(R.string.lock_tag_base)
                        )
                        VersionCompareRow(
                            label = stringResource(R.string.lock_row_xposed),
                            value = when {
                                !simStatus.locShm -> stringResource(R.string.lock_val_no_loc_shm)
                                simStatus.xposedCode > 0 -> "#${simStatus.xposedCode}"
                                else -> legacyText
                            },
                            ok = versionCompat.xposedOk,
                            statusTag = when {
                                !simStatus.locShm -> offlineTag
                                versionCompat.xposedOk -> "✓"
                                else -> rebootTag
                            },
                            mismatchColor = NeuColors.AmberDeep
                        )
                        VersionCompareRow(
                            label = stringResource(R.string.lock_row_daemon),
                            value = if (simStatus.modCode > 0) "v${simStatus.modVer} (#${simStatus.modCode})" else legacyText,
                            ok = versionCompat.daemonOk,
                            statusTag = if (versionCompat.daemonOk) "✓" else mismatchTag,
                            mismatchColor = NeuColors.Coral
                        )
                        VersionCompareRow(
                            label = stringResource(R.string.lock_row_zygisk),
                            value = when {
                                !simStatus.sensorShm -> stringResource(R.string.lock_val_no_sensor_shm)
                                simStatus.zygiskCode > 0 -> "#${simStatus.zygiskCode}"
                                else -> legacyText
                            },
                            ok = versionCompat.zygiskOk,
                            statusTag = when {
                                !simStatus.sensorShm -> offlineTag
                                versionCompat.zygiskOk -> "✓"
                                else -> rebootTag
                            },
                            mismatchColor = NeuColors.AmberDeep
                        )
                        VersionCompareRow(
                            label = stringResource(R.string.lock_row_proto),
                            value = stringResource(
                                R.string.lock_proto_value,
                                simStatus.protoVer,
                                simStatus.shmVer,
                                appProto,
                                appShm
                            ),
                            ok = versionCompat.protocolOk,
                            statusTag = if (versionCompat.protocolOk) "✓" else mismatchTag,
                            mismatchColor = NeuColors.Coral
                        )
                    }
                }

                if (versionCompat.actionHint.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(accent.copy(alpha = 0.12f))
                            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.lock_next_step, versionCompat.actionHint),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.lock_safe_note),
                    fontSize = 10.5.sp,
                    lineHeight = 14.sp,
                    color = NeuColors.InkSecondary
                )

                NeuButton(
                    onClick = onOpenDiagnostics,
                    modifier = Modifier.fillMaxWidth(),
                    tone = NeuTone.NEUTRAL,
                    cornerRadius = 10.dp,
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.HealthAndSafety,
                        contentDescription = null,
                        tint = NeuColors.CobaltDeep,
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.compat_btn_open_diag),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NeuColors.CobaltDeep
                    )
                }
            }
        },
        confirmButton = {
            NeuButton(
                onClick = onDismiss,
                tone = NeuTone.COBALT,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.lock_btn_got_it), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onOpenSettings) {
                Text(
                    stringResource(R.string.lock_btn_open_settings),
                    color = NeuColors.CobaltDeep,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    )
}

@Composable
private fun VersionCompareRow(
    label: String,
    value: String,
    ok: Boolean,
    statusTag: String,
    mismatchColor: Color = NeuColors.Coral
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = NeuColors.InkSecondary,
            modifier = Modifier.width(82.dp)
        )
        Text(
            text = value,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (ok) NeuColors.InkPrimary else mismatchColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = statusTag,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (ok) NeuColors.EmeraldDeep else mismatchColor,
            maxLines = 1,
            softWrap = false
        )
    }
}
