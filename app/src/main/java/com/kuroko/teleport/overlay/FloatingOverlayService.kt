package com.kuroko.teleport.overlay

import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kuroko.teleport.R
import com.kuroko.teleport.geo.CoordTransform
import com.kuroko.teleport.ipc.DaemonSnapshot
import com.kuroko.teleport.ipc.KurokoProtocol
import com.kuroko.teleport.ipc.KurokoRuntime
import com.kuroko.teleport.ipc.evaluateVersionCompat
import com.kuroko.teleport.ui.AppLocaleManager
import com.kuroko.teleport.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Foreground overlay service hosting the Braun Light Neumorphic Floating HUD
 * (Morph A docked capsule <-> Morph B 252dp mini console), bottom dismiss hotzone,
 * smart auto-hide when MainActivity is foreground, realtime FLAG_SECURE, and
 * low-distraction notification bar quick controls.
 */
class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var notificationManager: NotificationManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var lifecycleOwner: OverlayLifecycleOwner? = null
    private var hudContainer: OverlayDragContainer? = null
    private var hudComposeView: ComposeView? = null
    private var hudLayoutListener: View.OnLayoutChangeListener? = null
    private var hudParams: WindowManager.LayoutParams? = null
    private var dismissView: ComposeView? = null
    private var dismissParams: WindowManager.LayoutParams? = null
    private var dismissAttached = false

    private var snapAnimator: ValueAnimator? = null
    private var hudAttached = false
    private var pendingExpandAlign = false

    // Compose UI observable states
    private val expandedState = mutableStateOf(false)
    private val idleDimmedState = mutableStateOf(false)
    private val draggingState = mutableStateOf(false)
    private val dismissHoveredState = mutableStateOf(false)

    // Notification throttling state
    private var lastNotifTimeMs = 0L
    private var lastNotifModeKey = ""

    private val idleDimRunnable = Runnable {
        if (!expandedState.value && !draggingState.value) {
            idleDimmedState.value = true
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocaleManager.wrapContext(newBase))
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshLocalizedUi()
    }

    private fun uiContext(): Context = AppLocaleManager.localizedContext(this)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        KurokoRuntime.init(applicationContext)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureNotificationChannel()
        ensureForegroundNotification(KurokoRuntime.snapshot.value)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        KurokoRuntime.init(applicationContext)
        // Always satisfy Android 14–17 FGS startForeground requirement immediately
        ensureForegroundNotification(KurokoRuntime.snapshot.value)

        when (intent?.action) {
            AppLocaleManager.ACTION_REFRESH_LOCALE -> {
                refreshLocalizedUi()
                return if (hudAttached) {
                    START_STICKY
                } else {
                    stopSelf()
                    START_NOT_STICKY
                }
            }
            ACTION_PAUSE, ACTION_RESUME, ACTION_STOP_SIM -> {
                val action = intent.action
                val shouldRunHud = Settings.canDrawOverlays(this) && KurokoRuntime.overlayPrefs.value.enabled
                if (!hudAttached && shouldRunHud) {
                    setupOverlayWindows()
                    KurokoRuntime.setOverlayRunning(true)
                    KurokoRuntime.attachObserver(OBSERVER_TAG)
                    startStateCollectors()
                }
                val headless = !hudAttached
                serviceScope.launch {
                    when (action) {
                        ACTION_PAUSE -> KurokoRuntime.pauseSimulation()
                        ACTION_RESUME -> {
                            KurokoRuntime.resumeOrStartRoute(applicationContext).onFailure { err ->
                                val toastCtx = uiContext()
                                Toast.makeText(
                                    toastCtx,
                                    err.message ?: toastCtx.getString(R.string.hud_err_version_resume_blocked),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        ACTION_STOP_SIM -> KurokoRuntime.stopSimulation()
                    }
                    if (headless) {
                        stopSelf()
                    }
                }
                return if (headless) START_NOT_STICKY else START_STICKY
            }
            ACTION_CLOSE_OVERLAY -> {
                KurokoRuntime.updateOverlayPrefs(applicationContext, enabled = false)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (!Settings.canDrawOverlays(this)) {
            KurokoRuntime.updateOverlayPrefs(applicationContext, enabled = false)
            KurokoRuntime.setOverlayRunning(false)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!hudAttached) {
            setupOverlayWindows()
            KurokoRuntime.setOverlayRunning(true)
            KurokoRuntime.attachObserver(OBSERVER_TAG)
            startStateCollectors()
        } else {
            applyVisibilityAndSecureFlag()
        }

        return START_STICKY
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(idleDimRunnable)
        snapAnimator?.cancel()
        snapAnimator = null
        serviceScope.cancel()

        KurokoRuntime.setOverlayExpanded(false)
        KurokoRuntime.setOverlayRunning(false)
        KurokoRuntime.detachObserver(OBSERVER_TAG)

        removeDismissZone()
        hudComposeView?.let { runCatching { it.disposeComposition() } }
        hudComposeView = null
        hudContainer?.let { view ->
            hudLayoutListener?.let { view.removeOnLayoutChangeListener(it) }
            hudLayoutListener = null
            if (hudAttached) {
                runCatching { windowManager.removeViewImmediate(view) }
                hudAttached = false
            }
        }
        hudContainer = null
        hudParams = null

        lifecycleOwner?.onDestroy()
        lifecycleOwner = null
        super.onDestroy()
    }

    private fun refreshLocalizedUi() {
        ensureNotificationChannel()
        lastNotifModeKey = ""
        lastNotifTimeMs = 0L
        runCatching {
            notificationManager.notify(NOTIF_ID, buildNotification(KurokoRuntime.snapshot.value))
        }

        val container = hudContainer
        if (hudAttached && container != null) {
            hudComposeView?.let { oldView ->
                runCatching { oldView.disposeComposition() }
                container.removeView(oldView)
            }
            val freshView = createHudComposeView()
            hudComposeView = freshView
            container.addView(
                freshView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
            container.post {
                if (!expandedState.value && hudAttached) {
                    snapToSavedEdgeImmediate()
                }
            }
        }

        if (dismissAttached) {
            removeDismissZone()
            showDismissZone()
        }
    }

    private fun createHudComposeView(): ComposeView {
        val uiCtx = uiContext()
        return ComposeView(uiCtx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                val snap by KurokoRuntime.snapshot.collectAsState()
                val compat by KurokoRuntime.versionCompat.collectAsState()
                val motion by KurokoRuntime.motionState.collectAsState()
                val presets by KurokoRuntime.speedPresets.collectAsState()
                val hasDraft by KurokoRuntime.hasDraftRoute.collectAsState()

                FloatingHudRoot(
                    snapshot = snap,
                    versionCompat = compat,
                    motionState = motion,
                    speedPresets = presets,
                    hasDraftRoute = hasDraft,
                    isExpanded = expandedState.value,
                    isIdleDimmed = idleDimmedState.value,
                    isDragging = draggingState.value,
                    onCollapse = { setExpanded(false) },
                    onCloseOverlay = { closeOverlayCompletely() },
                    onOpenMain = { openMainActivity() },
                    onPause = {
                        resetIdleTimer()
                        serviceScope.launch { KurokoRuntime.pauseSimulation() }
                    },
                    onStartOrResume = {
                        resetIdleTimer()
                        serviceScope.launch {
                            KurokoRuntime.resumeOrStartRoute(applicationContext).onFailure { err ->
                                val toastCtx = uiContext()
                                Toast.makeText(
                                    toastCtx,
                                    err.message ?: toastCtx.getString(R.string.hud_err_version_start_blocked),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    onStop = {
                        resetIdleTimer()
                        serviceScope.launch { KurokoRuntime.stopSimulation() }
                    },
                    onAdjustSpeed = { delta ->
                        resetIdleTimer()
                        val target = (KurokoRuntime.motionState.value.speedMps + delta).coerceIn(0.5f, 8.0f)
                        serviceScope.launch {
                            KurokoRuntime.applySpeed(applicationContext, target, KurokoProtocol.MOTION_AUTO)
                        }
                    },
                    onSelectPreset = { preset ->
                        resetIdleTimer()
                        serviceScope.launch { KurokoRuntime.applyPreset(applicationContext, preset) }
                    },
                    onToggleCarry = {
                        resetIdleTimer()
                        val nextCarry = if (KurokoRuntime.motionState.value.carryMode == KurokoProtocol.CARRY_POCKET) {
                            KurokoProtocol.CARRY_HAND_SWING
                        } else {
                            KurokoProtocol.CARRY_POCKET
                        }
                        serviceScope.launch { KurokoRuntime.applyCarryMode(applicationContext, nextCarry) }
                    },
                    onResetSteps = {
                        resetIdleTimer()
                        serviceScope.launch { KurokoRuntime.resetSteps() }
                    }
                )
            }
        }
    }

    private fun setupOverlayWindows() {
        val owner = OverlayLifecycleOwner().also {
            it.onCreate()
            it.onStart()
            it.onResume()
        }
        lifecycleOwner = owner

        val metrics = getSafeScreenMetrics()
        val prefs = KurokoRuntime.overlayPrefs.value
        val edgeMargin = dpToPx(6)
        val estCapsuleW = dpToPx(116)
        val initX = if (prefs.sideRight) {
            (metrics.width - estCapsuleW - edgeMargin).coerceAtLeast(edgeMargin)
        } else {
            edgeMargin
        }
        val usableH = (metrics.height - metrics.topInset - metrics.bottomInset - dpToPx(44)).coerceAtLeast(1)
        val minInitY = metrics.topInset + dpToPx(6)
        val maxInitY = (metrics.height - metrics.bottomInset - dpToPx(46)).coerceAtLeast(minInitY)
        val initY = (metrics.topInset + (usableH * prefs.yFraction).roundToInt())
            .coerceIn(minInitY, maxInitY)

        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (prefs.secureFlag) WindowManager.LayoutParams.FLAG_SECURE else 0)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
        }
        hudParams = params

        val container = OverlayDragContainer(this)
        val composeView = createHudComposeView()
        hudComposeView = composeView
        container.addView(
            composeView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // Bind ViewTree owners BEFORE windowManager.addView
        container.setViewTreeLifecycleOwner(owner)
        container.setViewTreeViewModelStoreOwner(owner)
        container.setViewTreeSavedStateRegistryOwner(owner)

        // React to real post-Compose measured width/height changes (expand/collapse/capsule text resize)
        val layoutListener = View.OnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            val newW = right - left
            val newH = bottom - top
            val oldW = oldRight - oldLeft
            val oldH = oldBottom - oldTop
            if (newW <= 0 || newH <= 0) return@OnLayoutChangeListener
            if (newW == oldW && newH == oldH && !pendingExpandAlign) return@OnLayoutChangeListener
            if (!hudAttached || draggingState.value || snapAnimator?.isRunning == true) return@OnLayoutChangeListener
            v.post { alignWindowForMeasuredSize(newW, newH) }
        }
        hudLayoutListener = layoutListener
        container.addOnLayoutChangeListener(layoutListener)

        hudContainer = container
        runCatching {
            windowManager.addView(container, params)
            hudAttached = true
        }.onFailure {
            KurokoRuntime.setOverlayRunning(false)
            stopSelf()
            return
        }

        // After first layout pass, align capsule cleanly to the saved edge
        container.post {
            if (!expandedState.value) {
                snapToSavedEdgeImmediate()
            }
            applyVisibilityAndSecureFlag()
            resetIdleTimer()
        }
    }

    private fun startStateCollectors() {
        // 1. Smart Auto-Hide & Realtime FLAG_SECURE observer
        serviceScope.launch {
            combine(
                KurokoRuntime.isMainActivityForeground,
                KurokoRuntime.overlayPrefs
            ) { fg, prefs -> fg to prefs }.collect {
                applyVisibilityAndSecureFlag()
            }
        }

        // 2. Notification bar live telemetry & action buttons observer
        serviceScope.launch {
            KurokoRuntime.snapshot.collect { snap ->
                val st = snap.status
                val hasRoute = st.routeLengthM > 1f
                val compatState = evaluateVersionCompat(snap.connected, st, uiContext()).state.name
                val modeKey = "${snap.connected}|${st.active}|${st.moving}|${isSimCompleted(st)}|$hasRoute|${st.targetCount}|$compatState"
                val now = System.currentTimeMillis()
                if (modeKey != lastNotifModeKey || (st.active && now - lastNotifTimeMs >= 1000L)) {
                    lastNotifModeKey = modeKey
                    lastNotifTimeMs = now
                    runCatching {
                        notificationManager.notify(NOTIF_ID, buildNotification(snap))
                    }
                }
            }
        }
    }

    private fun applyVisibilityAndSecureFlag() {
        val view = hudContainer ?: return
        val params = hudParams ?: return
        if (!hudAttached) return

        val prefs = KurokoRuntime.overlayPrefs.value
        val fg = KurokoRuntime.isMainActivityForeground.value
        val shouldShow = !fg || !prefs.autoHideInApp

        val curSecure = (params.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (curSecure != prefs.secureFlag) {
            params.flags = if (prefs.secureFlag) {
                params.flags or WindowManager.LayoutParams.FLAG_SECURE
            } else {
                params.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            }
            runCatching { windowManager.updateViewLayout(view, params) }
        }

        if (!shouldShow) {
            if (expandedState.value) {
                expandedState.value = false
                KurokoRuntime.setOverlayExpanded(false)
            }
            removeDismissZone()
            if (view.visibility != View.GONE) {
                view.visibility = View.GONE
            }
        } else {
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                view.post {
                    if (!expandedState.value) {
                        snapToSavedEdgeImmediate()
                    }
                    resetIdleTimer()
                }
            }
        }
    }

    private fun setExpanded(expand: Boolean) {
        if (expandedState.value == expand) return
        snapAnimator?.cancel()
        mainHandler.removeCallbacks(idleDimRunnable)
        idleDimmedState.value = false
        pendingExpandAlign = expand
        expandedState.value = expand
        KurokoRuntime.setOverlayExpanded(expand)

        if (!expand) {
            resetIdleTimer()
        }
        // Post-Compose layout alignment runs deterministically inside hudLayoutListener
        // once Compose remeasures the new morph dimensions.
    }

    private fun alignWindowForMeasuredSize(measuredW: Int, measuredH: Int) {
        val view = hudContainer ?: return
        val params = hudParams ?: return
        if (!hudAttached || draggingState.value || snapAnimator?.isRunning == true) return

        val metrics = getSafeScreenMetrics()
        val prefs = KurokoRuntime.overlayPrefs.value
        val expanded = expandedState.value
        val edgeMargin = dpToPx(if (expanded) 8 else 6)
        val minY = metrics.topInset + dpToPx(8)
        val maxY = (metrics.height - metrics.bottomInset - measuredH - dpToPx(8)).coerceAtLeast(minY)
        val maxX = (metrics.width - measuredW - edgeMargin).coerceAtLeast(edgeMargin)

        val targetX: Int
        val targetY: Int
        if (!expanded) {
            targetX = if (prefs.sideRight) maxX else edgeMargin
            val usableH = (maxY - minY).coerceAtLeast(1)
            targetY = (minY + (usableH * prefs.yFraction).roundToInt()).coerceIn(minY, maxY)
        } else {
            targetX = if (pendingExpandAlign) {
                pendingExpandAlign = false
                if (prefs.sideRight) maxX else edgeMargin
            } else {
                params.x.coerceIn(edgeMargin, maxX)
            }
            targetY = params.y.coerceIn(minY, maxY)
        }

        if (params.x != targetX || params.y != targetY) {
            params.x = targetX
            params.y = targetY
            runCatching { windowManager.updateViewLayout(view, params) }
        }
    }

    private fun snapToSavedEdgeImmediate() {
        val view = hudContainer ?: return
        val params = hudParams ?: return
        if (!hudAttached) return

        val metrics = getSafeScreenMetrics()
        val prefs = KurokoRuntime.overlayPrefs.value
        val edgeMargin = dpToPx(6)
        val viewW = view.width.takeIf { it > 0 } ?: dpToPx(116)
        val viewH = view.height.takeIf { it > 0 } ?: dpToPx(42)
        val minY = metrics.topInset + dpToPx(8)
        val maxY = (metrics.height - metrics.bottomInset - viewH - dpToPx(8)).coerceAtLeast(minY)
        val usableH = (maxY - minY).coerceAtLeast(1)

        params.x = if (prefs.sideRight) {
            (metrics.width - viewW - edgeMargin).coerceAtLeast(edgeMargin)
        } else {
            edgeMargin
        }
        params.y = (minY + (usableH * prefs.yFraction).roundToInt()).coerceIn(minY, maxY)
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    private fun animateSnapToNearestEdge() {
        val view = hudContainer ?: return
        val params = hudParams ?: return
        if (!hudAttached) return

        val metrics = getSafeScreenMetrics()
        val edgeMargin = dpToPx(6)
        val viewW = view.width.takeIf { it > 0 } ?: dpToPx(116)
        val viewH = view.height.takeIf { it > 0 } ?: dpToPx(42)
        val minY = metrics.topInset + dpToPx(8)
        val maxY = (metrics.height - metrics.bottomInset - viewH - dpToPx(8)).coerceAtLeast(minY)

        val centerX = params.x + viewW / 2
        val snapRight = centerX >= metrics.width / 2
        val targetX = if (snapRight) {
            (metrics.width - viewW - edgeMargin).coerceAtLeast(edgeMargin)
        } else {
            edgeMargin
        }
        val clampedY = params.y.coerceIn(minY, maxY)
        val usableH = (maxY - minY).coerceAtLeast(1)
        val yFraction = ((clampedY - minY).toFloat() / usableH.toFloat()).coerceIn(0.05f, 0.85f)

        KurokoRuntime.updateOverlayPrefs(
            applicationContext,
            sideRight = snapRight,
            yFraction = yFraction
        )

        val startX = params.x
        val startY = params.y
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 230L
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                val f = anim.animatedValue as Float
                params.x = (startX + (targetX - startX) * f).roundToInt()
                params.y = (startY + (clampedY - startY) * f).roundToInt()
                if (hudAttached) {
                    runCatching { windowManager.updateViewLayout(view, params) }
                }
            }
            start()
        }
    }

    private fun showDismissZone() {
        if (dismissAttached) return
        val owner = lifecycleOwner ?: return
        val metrics = getSafeScreenMetrics()
        val secure = if (KurokoRuntime.overlayPrefs.value.secureFlag) {
            WindowManager.LayoutParams.FLAG_SECURE
        } else {
            0
        }

        val dParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                secure,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = metrics.bottomInset + dpToPx(28)
        }
        dismissParams = dParams

        val dView = ComposeView(uiContext()).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                FloatingDismissZoneContent(isHovered = dismissHoveredState.value)
            }
        }
        dView.setViewTreeLifecycleOwner(owner)
        dView.setViewTreeViewModelStoreOwner(owner)
        dView.setViewTreeSavedStateRegistryOwner(owner)

        dismissView = dView
        runCatching {
            windowManager.addView(dView, dParams)
            dismissAttached = true
        }
    }

    private fun removeDismissZone() {
        dismissHoveredState.value = false
        val dView = dismissView ?: return
        if (dismissAttached) {
            runCatching { windowManager.removeViewImmediate(dView) }
            dismissAttached = false
        }
        runCatching { dView.disposeComposition() }
        dismissView = null
        dismissParams = null
    }

    private fun isInsideDismissZone(rawX: Float, rawY: Float): Boolean {
        val metrics = getSafeScreenMetrics()
        val targetX = metrics.width / 2f
        val targetY = metrics.height - metrics.bottomInset - dpToPx(64).toFloat()
        val hitRadius = dpToPx(66).toFloat()
        return hypot((rawX - targetX).toDouble(), (rawY - targetY).toDouble()) <= hitRadius
    }

    private fun triggerLightHaptic() {
        runCatching {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }
    }

    private fun resetIdleTimer() {
        mainHandler.removeCallbacks(idleDimRunnable)
        idleDimmedState.value = false
        if (!expandedState.value && !draggingState.value) {
            mainHandler.postDelayed(idleDimRunnable, 4000L)
        }
    }

    private fun closeOverlayCompletely() {
        KurokoRuntime.updateOverlayPrefs(applicationContext, enabled = false)
        stopSelf()
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        runCatching { startActivity(intent) }
    }

    // ===== Foreground Notification & Quick Controls =====

    private fun ensureNotificationChannel() {
        val ctx = uiContext()
        val channel = NotificationChannel(
            NOTIF_CHANNEL_ID,
            ctx.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = ctx.getString(R.string.notif_channel_desc)
            setShowBadge(false)
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun ensureForegroundNotification(snapshot: DaemonSnapshot) {
        val notif = buildNotification(snapshot)
        ServiceCompat.startForeground(
            this,
            NOTIF_ID,
            notif,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
    }

    private fun buildNotification(snapshot: DaemonSnapshot): Notification {
        val ctx = uiContext()
        val st = snapshot.status
        val isCompleted = isSimCompleted(st)
        val openMainIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title: String
        val body: String
        when {
            !snapshot.connected -> {
                title = ctx.getString(R.string.notif_title_offline)
                body = ctx.getString(R.string.notif_body_offline)
            }
            st.active && st.moving -> {
                val dist = CoordTransform.formatDistance(st.totalDistanceM.toDouble())
                val pace = CoordTransform.formatPace(st.speedMps)
                title = ctx.getString(R.string.notif_title_moving, dist, pace)
                body = ctx.getString(
                    R.string.notif_body_moving,
                    st.virtualSteps,
                    st.cadenceSpm.roundToInt(),
                    st.targetCount
                )
            }
            isCompleted -> {
                title = ctx.getString(R.string.notif_title_done)
                body = ctx.getString(
                    R.string.notif_body_done,
                    CoordTransform.formatDistance(st.totalDistanceM.toDouble())
                )
            }
            st.active && st.routeLengthM <= 1f -> {
                title = ctx.getString(R.string.notif_title_static)
                body = ctx.getString(
                    R.string.notif_body_static,
                    String.format(Locale.US, "%.5f", st.latWgs84),
                    String.format(Locale.US, "%.5f", st.lonWgs84),
                    st.targetCount
                )
            }
            st.active -> {
                title = ctx.getString(R.string.notif_title_paused)
                body = ctx.getString(
                    R.string.notif_body_paused,
                    CoordTransform.formatDistance(st.totalDistanceM.toDouble()),
                    st.virtualSteps
                )
            }
            else -> {
                title = ctx.getString(R.string.notif_title_ready)
                body = ctx.getString(R.string.notif_body_ready, st.targetCount)
            }
        }

        val builder = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_kuroko)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(openMainIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        val compat = evaluateVersionCompat(snapshot.connected, st, ctx)
        val versionLocked = snapshot.connected && !compat.canStartSimulation
        val closePending = serviceActionPendingIntent(ACTION_CLOSE_OVERLAY, 104)
        when {
            st.active && st.moving -> {
                builder.addAction(0, ctx.getString(R.string.notif_action_pause), serviceActionPendingIntent(ACTION_PAUSE, 101))
                builder.addAction(0, ctx.getString(R.string.notif_action_stop), serviceActionPendingIntent(ACTION_STOP_SIM, 103))
                builder.addAction(0, ctx.getString(R.string.notif_action_close_hud), closePending)
            }
            st.active -> {
                // Only offer Resume when a route is loaded AND version lock passes (never during Static Point hold or mismatch!)
                if (st.routeLengthM > 1f && !versionLocked) {
                    builder.addAction(0, ctx.getString(R.string.notif_action_resume), serviceActionPendingIntent(ACTION_RESUME, 102))
                } else if (versionLocked) {
                    builder.addAction(0, ctx.getString(R.string.notif_action_view_lock), openMainIntent)
                }
                builder.addAction(0, ctx.getString(R.string.notif_action_stop), serviceActionPendingIntent(ACTION_STOP_SIM, 103))
                builder.addAction(0, ctx.getString(R.string.notif_action_close_hud), closePending)
            }
            else -> {
                builder.addAction(0, ctx.getString(R.string.notif_action_open_app), openMainIntent)
                builder.addAction(0, ctx.getString(R.string.notif_action_close_hud), closePending)
            }
        }

        return builder.build()
    }

    private fun serviceActionPendingIntent(action: String, reqCode: Int): PendingIntent {
        val intent = Intent(this, FloatingOverlayService::class.java).apply { this.action = action }
        return PendingIntent.getService(
            this,
            reqCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Raw-screen-coordinate (`MotionEvent.rawX`/`rawY`) touch container eliminating window drag jitter.
     * - Capsule mode: entire capsule supports silky drag + bottom dismiss hotzone + edge snap; tap expands.
     * - Expanded mode: top header drag area (left of the 3 icon buttons) drags the console smoothly;
     *   all buttons, presets, and toggles below pass cleanly to Compose with zero conflict.
     */
    private inner class OverlayDragContainer(context: Context) : FrameLayout(context) {
        private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
        private var trackingDrag = false
        private var isDragging = false
        private var downRawX = 0f
        private var downRawY = 0f
        private var startWinX = 0
        private var startWinY = 0

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (!expandedState.value) {
                // In capsule mode, own the full gesture cleanly for tap-vs-drag
                return true
            }
            // In expanded mode, intercept ONLY if touch starts in the draggable top header bar (excluding right 3 buttons)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val headerH = dpToPx(44)
                    val rightButtonsW = dpToPx(104)
                    val inHeaderDragArea = ev.y <= headerH && ev.x < (width - rightButtonsW)
                    if (inHeaderDragArea) {
                        beginTracking(ev)
                    } else {
                        trackingDrag = false
                        isDragging = false
                    }
                    return false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (trackingDrag) {
                        val dist = hypot((ev.rawX - downRawX).toDouble(), (ev.rawY - downRawY).toDouble())
                        if (dist > touchSlop) {
                            isDragging = true
                            draggingState.value = true
                            return true
                        }
                    }
                    return false
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    trackingDrag = false
                    isDragging = false
                    return false
                }
            }
            return false
        }

        @Suppress("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val params = hudParams ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    beginTracking(ev)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!trackingDrag) return false
                    val dx = (ev.rawX - downRawX).roundToInt()
                    val dy = (ev.rawY - downRawY).roundToInt()
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > touchSlop) {
                        isDragging = true
                        draggingState.value = true
                        if (!expandedState.value) {
                            showDismissZone()
                        }
                    }
                    if (isDragging) {
                        val metrics = getSafeScreenMetrics()
                        val viewW = width.takeIf { it > 0 } ?: dpToPx(116)
                        val viewH = height.takeIf { it > 0 } ?: dpToPx(42)
                        val minY = metrics.topInset + dpToPx(4)
                        val maxY = (metrics.height - metrics.bottomInset - viewH - dpToPx(4)).coerceAtLeast(minY)

                        if (expandedState.value) {
                            val margin = dpToPx(6)
                            val maxX = (metrics.width - viewW - margin).coerceAtLeast(margin)
                            params.x = (startWinX + dx).coerceIn(margin, maxX)
                            params.y = (startWinY + dy).coerceIn(minY, maxY)
                        } else {
                            // Capsule can drag down to bottom dismiss zone
                            val maxCapsuleY = (metrics.height - viewH).coerceAtLeast(minY)
                            params.x = startWinX + dx
                            params.y = (startWinY + dy).coerceIn(minY, maxCapsuleY)

                            val hovered = isInsideDismissZone(ev.rawX, ev.rawY)
                            if (hovered != dismissHoveredState.value) {
                                dismissHoveredState.value = hovered
                                if (hovered) triggerLightHaptic()
                            }
                        }
                        if (hudAttached) {
                            runCatching { windowManager.updateViewLayout(this, params) }
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = isDragging
                    val droppedInDismiss = !expandedState.value && wasDragging &&
                        ev.actionMasked == MotionEvent.ACTION_UP && dismissHoveredState.value
                    trackingDrag = false
                    isDragging = false
                    draggingState.value = false
                    removeDismissZone()

                    if (droppedInDismiss) {
                        closeOverlayCompletely()
                    } else if (wasDragging) {
                        if (!expandedState.value) {
                            animateSnapToNearestEdge()
                            resetIdleTimer()
                        }
                    } else if (!expandedState.value && ev.actionMasked == MotionEvent.ACTION_UP) {
                        // Clean single tap on capsule -> expand Morph B!
                        setExpanded(true)
                    }
                    return true
                }
            }
            return super.onTouchEvent(ev)
        }

        private fun beginTracking(ev: MotionEvent) {
            val params = hudParams ?: return
            snapAnimator?.cancel()
            mainHandler.removeCallbacks(idleDimRunnable)
            idleDimmedState.value = false
            trackingDrag = true
            isDragging = false
            downRawX = ev.rawX
            downRawY = ev.rawY
            startWinX = params.x
            startWinY = params.y
        }
    }

    private data class SafeScreenMetrics(
        val width: Int,
        val height: Int,
        val topInset: Int,
        val bottomInset: Int
    )

    private fun getSafeScreenMetrics(): SafeScreenMetrics {
        val wmMetrics = windowManager.currentWindowMetrics
        val bounds = wmMetrics.bounds
        val insets = wmMetrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
        )
        return SafeScreenMetrics(
            width = bounds.width(),
            height = bounds.height(),
            topInset = insets.top,
            bottomInset = insets.bottom
        )
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).roundToInt()

    /**
     * Dedicated Lifecycle/ViewModelStore/SavedStateRegistry owner for ComposeView inside a Service.
     */
    private class OverlayLifecycleOwner : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {
        private val lifecycleRegistry = LifecycleRegistry(this)
        private val store = ViewModelStore()
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = lifecycleRegistry
        override val viewModelStore: ViewModelStore get() = store
        override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

        fun onCreate() {
            savedStateController.performRestore(null)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }

        fun onStart() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        fun onResume() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun onDestroy() {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }

    companion object {
        private const val OBSERVER_TAG = "floating_overlay_service"
        private const val NOTIF_CHANNEL_ID = "kuroko_hud_channel"
        private const val NOTIF_ID = 28765

        const val ACTION_START = "com.kuroko.teleport.overlay.ACTION_START"
        const val ACTION_PAUSE = "com.kuroko.teleport.overlay.ACTION_PAUSE"
        const val ACTION_RESUME = "com.kuroko.teleport.overlay.ACTION_RESUME"
        const val ACTION_STOP_SIM = "com.kuroko.teleport.overlay.ACTION_STOP_SIM"
        const val ACTION_CLOSE_OVERLAY = "com.kuroko.teleport.overlay.ACTION_CLOSE_OVERLAY"
        const val ACTION_REFRESH_LOCALE = AppLocaleManager.ACTION_REFRESH_LOCALE

        fun start(context: Context) {
            val intent = Intent(context, FloatingOverlayService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }
}
