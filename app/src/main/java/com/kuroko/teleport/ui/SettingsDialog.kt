package com.kuroko.teleport.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.kuroko.teleport.R
import com.kuroko.teleport.ipc.DaemonTargetSpec
import com.kuroko.teleport.ipc.SimStatus
import com.kuroko.teleport.ipc.VersionCompatInfo
import com.kuroko.teleport.ipc.VersionCompatState
import com.kuroko.teleport.ipc.KurokoProtocol
import com.kuroko.teleport.ipc.KurokoRuntime
import com.kuroko.teleport.update.UpdateChecker
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

private class SelfTestSessionHolder {
    var listener: SensorEventListener? = null
    var active: Boolean = false
    var sessionId: Long = 0L
    var lastAccelUpdateNs: Long = 0L
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    tiandituToken: String,
    amapWebKey: String,
    daemonConnected: Boolean,
    locShmOk: Boolean,
    phoneShmOk: Boolean,
    sensorShmOk: Boolean,
    btShmOk: Boolean = false,
    sensorDiag: String,
    simStatus: SimStatus,
    versionCompat: VersionCompatInfo,
    simActive: Boolean,
    simCadenceSpm: Float,
    savedTargets: List<DaemonTargetSpec>,
    daemonMessage: String,
    overlayEnabled: Boolean,
    overlayAutoHideInApp: Boolean,
    overlaySecureFlag: Boolean,
    appLanguageTag: String,
    onSelectLanguage: (String) -> Unit,
    onToggleOverlay: (Boolean) -> Unit,
    onToggleOverlayAutoHide: (Boolean) -> Unit,
    onToggleOverlaySecure: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onSaveKeys: (tiandituToken: String, amapWebKey: String) -> Unit,
    onReconnectDaemon: () -> Unit,
    onResetSteps: () -> Unit,
    onResetSpeedPresets: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenRfConfig: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    autoCheckUpdate: Boolean,
    updateCheckBusy: Boolean,
    updateStatusText: String?,
    onToggleAutoCheckUpdate: (Boolean) -> Unit,
    onManualCheckUpdate: () -> Unit
) {
    val context = LocalContext.current
    val sensorManager = remember(context) {
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }

    var tdt by remember(tiandituToken) { mutableStateOf(tiandituToken) }
    var amap by remember(amapWebKey) { mutableStateOf(amapWebKey) }

    // Built-in local pedometer / IMU self-test state
    val selfTestHolder = remember { SelfTestSessionHolder() }
    var selfTestRunning by remember { mutableStateOf(false) }
    var permDeniedHint by remember { mutableStateOf(false) }
    var stepCounterSupported by remember { mutableStateOf(true) }
    var stepDetectorSupported by remember { mutableStateOf(true) }
    var accelSupported by remember { mutableStateOf(true) }
    var stepCounterFirst by remember { mutableStateOf<Float?>(null) }
    var stepCounterLatest by remember { mutableStateOf<Float?>(null) }
    var stepCounterFrames by remember { mutableIntStateOf(0) }
    var stepDetectorCount by remember { mutableIntStateOf(0) }
    var hasAccel by remember { mutableStateOf(false) }
    var accelX by remember { mutableFloatStateOf(0f) }
    var accelY by remember { mutableFloatStateOf(0f) }
    var accelZ by remember { mutableFloatStateOf(0f) }
    val tempJogActive by KurokoRuntime.sensorSelfTestTempJogActive.collectAsState()

    fun stopSelfTest() {
        selfTestHolder.listener?.let { l ->
            runCatching { sensorManager?.unregisterListener(l) }
        }
        selfTestHolder.listener = null
        val wasActive = selfTestHolder.active
        selfTestHolder.active = false
        selfTestRunning = false
        if (wasActive) {
            KurokoRuntime.endSensorSelfTestAsync()
        }
    }

    fun startSelfTest() {
        stopSelfTest()
        permDeniedHint = false
        stepCounterFirst = null
        stepCounterLatest = null
        stepCounterFrames = 0
        stepDetectorCount = 0
        hasAccel = false
        accelX = 0f
        accelY = 0f
        accelZ = 0f
        selfTestHolder.lastAccelUpdateNs = 0L

        val scSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        val sdSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val accSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        stepCounterSupported = scSensor != null
        stepDetectorSupported = sdSensor != null
        accelSupported = accSensor != null

        val sessionId = KurokoRuntime.beginSensorSelfTestAsync(context, savedTargets)
        selfTestHolder.sessionId = sessionId
        selfTestHolder.active = true
        selfTestRunning = true

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_STEP_COUNTER -> {
                        val v = event.values.firstOrNull() ?: return
                        if (stepCounterFirst == null) {
                            stepCounterFirst = v
                        }
                        stepCounterLatest = v
                        stepCounterFrames += 1
                    }
                    Sensor.TYPE_STEP_DETECTOR -> {
                        stepDetectorCount += 1
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val nowNs = SystemClock.elapsedRealtimeNanos()
                        if (!hasAccel || nowNs - selfTestHolder.lastAccelUpdateNs >= 100_000_000L) {
                            selfTestHolder.lastAccelUpdateNs = nowNs
                            accelX = event.values.getOrElse(0) { 0f }
                            accelY = event.values.getOrElse(1) { 0f }
                            accelZ = event.values.getOrElse(2) { 0f }
                            hasAccel = true
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        selfTestHolder.listener = listener
        scSensor?.let { runCatching { sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) } }
        sdSensor?.let { runCatching { sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) } }
        accSensor?.let { runCatching { sensorManager?.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) } }
    }

    val activityRecognitionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startSelfTest()
        } else {
            permDeniedHint = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            stopSelfTest()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeuColors.Surface,
        titleContentColor = NeuColors.InkPrimary,
        textContentColor = NeuColors.InkPrimary,
        icon = {
            Icon(Icons.Default.Settings, contentDescription = null, tint = NeuColors.CobaltDeep)
        },
        title = {
            Text(
                text = stringResource(R.string.settings_title),
                fontWeight = FontWeight.Bold,
                color = NeuColors.InkPrimary
            )
        },
        text = {
            val legacyShort = stringResource(R.string.common_legacy_short)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuInset(cornerRadius = 12.dp, surfaceColor = NeuColors.LcdWellBg, depth = 3.dp)
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NeuLedDot(
                                    color = if (daemonConnected) NeuColors.LedGreen else NeuColors.Coral,
                                    lit = true,
                                    size = 6.dp
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(
                                        if (daemonConnected) R.string.settings_engine_online else R.string.settings_engine_offline
                                    ),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = NeuColors.InkPrimary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NeuLedDot(
                                    color = NeuColors.LedGreen,
                                    lit = daemonConnected && locShmOk,
                                    size = 5.5.dp
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = if (daemonConnected && locShmOk) "L✓" else "L×",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (daemonConnected && locShmOk) NeuColors.InkPrimary else NeuColors.InkMuted,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NeuLedDot(
                                    color = NeuColors.LedGreen,
                                    lit = daemonConnected && phoneShmOk,
                                    size = 5.5.dp
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = if (daemonConnected && phoneShmOk) "P✓" else "P×",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (daemonConnected && phoneShmOk) NeuColors.InkPrimary else NeuColors.InkMuted,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NeuLedDot(
                                    color = NeuColors.LedGreen,
                                    lit = daemonConnected && sensorShmOk,
                                    size = 5.5.dp
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = if (daemonConnected && sensorShmOk) "S✓" else "S×",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = when {
                                        daemonConnected && sensorShmOk -> NeuColors.InkPrimary
                                        daemonConnected -> NeuColors.AmberDeep
                                        else -> NeuColors.InkMuted
                                    },
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                NeuLedDot(
                                    color = NeuColors.Cobalt,
                                    lit = daemonConnected && btShmOk,
                                    size = 5.5.dp
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = if (daemonConnected && btShmOk) "B✓" else "B-",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (daemonConnected && btShmOk) NeuColors.InkPrimary else NeuColors.InkMuted,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        Text(
                            text = daemonMessage,
                            fontSize = 11.sp,
                            color = NeuColors.InkSecondary
                        )
                        val versionLine = buildAnnotatedString {
                            val appVer = KurokoProtocol.APP_VERSION_NAME
                            val appCode = KurokoProtocol.APP_VERSION_CODE
                            withStyle(SpanStyle(color = NeuColors.InkPrimary, fontWeight = FontWeight.Bold)) {
                                append("App v$appVer($appCode)")
                            }
                            if (daemonConnected) {
                                val hookTxt = when {
                                    !locShmOk -> "--"
                                    simStatus.xposedCode > 0 -> "${simStatus.xposedCode}"
                                    else -> legacyShort
                                }
                                val daemonTxt = if (simStatus.modCode > 0) "${simStatus.modCode}" else legacyShort
                                val sensorTxt = when {
                                    !sensorShmOk -> "--"
                                    simStatus.zygiskCode > 0 -> "${simStatus.zygiskCode}"
                                    else -> legacyShort
                                }
                                val protoTxt = if (simStatus.protoVer > 0) "P${simStatus.protoVer}" else "P?"

                                append(" · ")
                                withStyle(
                                    SpanStyle(
                                        color = if (versionCompat.xposedOk) NeuColors.InkPrimary else NeuColors.AmberDeep,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append("Hook($hookTxt)")
                                }
                                append(" · ")
                                withStyle(
                                    SpanStyle(
                                        color = if (versionCompat.daemonOk) NeuColors.InkPrimary else NeuColors.Coral,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append("Daemon($daemonTxt)")
                                }
                                append(" · ")
                                withStyle(
                                    SpanStyle(
                                        color = if (versionCompat.zygiskOk) NeuColors.InkPrimary else NeuColors.AmberDeep,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append("Sensor($sensorTxt)")
                                }
                                append(" · ")
                                withStyle(
                                    SpanStyle(
                                        color = if (versionCompat.protocolOk) NeuColors.InkPrimary else NeuColors.Coral,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append(protoTxt)
                                }
                            } else {
                                append(" · P${KurokoProtocol.IPC_PROTOCOL_VERSION} · SHM${KurokoProtocol.SHM_SCHEMA_VERSION}")
                            }
                        }
                        Text(
                            text = versionLine,
                            fontSize = 10.sp,
                            lineHeight = 13.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = NeuColors.InkSecondary
                        )
                        if (daemonConnected && !versionCompat.canStartSimulation) {
                            val lockTint = if (versionCompat.state == VersionCompatState.REBOOT_REQUIRED) {
                                NeuColors.AmberDeep
                            } else {
                                NeuColors.Coral
                            }
                            Text(
                                text = "${versionCompat.headline}: ${versionCompat.detail} ${versionCompat.actionHint}",
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = lockTint
                            )
                        }
                        if (sensorDiag.isNotBlank()) {
                            Text(
                                text = "Native: $sensorDiag",
                                fontSize = 10.sp,
                                lineHeight = 13.sp,
                                fontFamily = FontFamily.Monospace,
                                color = NeuColors.InkPrimary
                            )
                        }
                        if (daemonConnected && !sensorShmOk) {
                            Text(
                                text = stringResource(R.string.settings_sensor_hint, KurokoProtocol.APP_VERSION_NAME),
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = NeuColors.AmberDeep
                            )
                        }
                    }
                }

                // Extensible data-driven Language Picker (auto-wraps via FlowRow for any number of future languages)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.settings_lang_section),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.InkPrimary
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        val systemDefaultLabel = stringResource(R.string.settings_lang_auto)
                        AppLocaleManager.supportedLanguages.forEach { lang ->
                            val selected = appLanguageTag.equals(lang.tag, ignoreCase = true)
                            val label = if (lang.isSystemDefault) systemDefaultLabel else lang.endonym
                            NeuChip(
                                label = label,
                                selected = selected,
                                onClick = {
                                    if (!selected) {
                                        onSelectLanguage(lang.tag)
                                    }
                                }
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    NeuButton(
                        onClick = onReconnectDaemon,
                        modifier = Modifier.weight(1f),
                        tone = NeuTone.NEUTRAL,
                        contentPadding = PaddingValues(vertical = 9.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.settings_btn_reconnect),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    NeuButton(
                        onClick = onResetSteps,
                        modifier = Modifier.weight(1f),
                        tone = NeuTone.NEUTRAL,
                        contentPadding = PaddingValues(vertical = 9.dp)
                    ) {
                        Icon(Icons.Default.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.settings_btn_reset_steps),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                NeuButton(
                    onClick = onResetSpeedPresets,
                    modifier = Modifier.fillMaxWidth(),
                    tone = NeuTone.NEUTRAL,
                    contentPadding = PaddingValues(vertical = 9.dp)
                ) {
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = NeuColors.AmberDeep
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.settings_btn_restore_presets),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                NeuButton(
                    onClick = {
                        onDismiss()
                        onOpenHelp()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    tone = NeuTone.NEUTRAL,
                    contentPadding = PaddingValues(vertical = 9.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = NeuColors.CobaltDeep
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.settings_btn_open_help),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.45f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f, fill = false),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        NeuLedDot(
                            color = NeuColors.LedGreen,
                            lit = selfTestRunning,
                            isPulsing = selfTestRunning,
                            size = 6.dp
                        )
                        Text(
                            text = stringResource(R.string.selftest_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.InkPrimary
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    NeuButton(
                        onClick = {
                            if (selfTestRunning) {
                                stopSelfTest()
                            } else {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACTIVITY_RECOGNITION
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    startSelfTest()
                                } else {
                                    permDeniedHint = false
                                    activityRecognitionLauncher.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                                }
                            }
                        },
                        tone = if (selfTestRunning) NeuTone.CORAL else NeuTone.COBALT,
                        cornerRadius = 10.dp,
                        elevation = 3.dp,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = if (selfTestRunning) Icons.Default.Stop else Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(
                                if (selfTestRunning) R.string.selftest_btn_stop else R.string.selftest_btn_start
                            ),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, elevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        if (!selfTestRunning) {
                            Text(
                                text = stringResource(R.string.selftest_idle_desc),
                                fontSize = 10.5.sp,
                                lineHeight = 14.sp,
                                color = NeuColors.InkSecondary
                            )
                            if (permDeniedHint) {
                                Text(
                                    text = stringResource(R.string.selftest_perm_denied),
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = NeuColors.AmberDeep
                                )
                            }
                        } else {
                            if (tempJogActive) {
                                Text(
                                    text = stringResource(R.string.selftest_temp_jog_running),
                                    fontSize = 10.sp,
                                    lineHeight = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = NeuColors.EmeraldDeep
                                )
                            } else if (!simActive || simCadenceSpm < 1f) {
                                val canJog = versionCompat.canStartSimulation
                                if (!canJog) {
                                    val lockColor = if (versionCompat.state == VersionCompatState.REBOOT_REQUIRED) {
                                        NeuColors.AmberDeep
                                    } else {
                                        NeuColors.Coral
                                    }
                                    Text(
                                        text = stringResource(
                                            R.string.selftest_jog_locked_banner,
                                            versionCompat.headline,
                                            versionCompat.actionHint
                                        ),
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = lockColor
                                    )
                                } else {
                                    Text(
                                        text = stringResource(R.string.selftest_jog_idle_hint),
                                        fontSize = 10.5.sp,
                                        lineHeight = 14.sp,
                                        color = NeuColors.AmberDeep
                                    )
                                }
                                NeuButton(
                                    onClick = {
                                        KurokoRuntime.startSelfTestJogPulseAsync(selfTestHolder.sessionId)
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    tone = if (canJog) NeuTone.EMERALD else NeuTone.NEUTRAL,
                                    enabled = canJog,
                                    cornerRadius = 10.dp,
                                    elevation = 3.dp,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.DirectionsRun,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        text = stringResource(
                                            if (canJog) R.string.selftest_btn_jog_pulse else R.string.selftest_btn_jog_locked
                                        ),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .neuInset(
                                        cornerRadius = 10.dp,
                                        surfaceColor = NeuColors.LcdWellBg,
                                        depth = 2.5.dp
                                    )
                                    .padding(horizontal = 9.dp, vertical = 7.dp)
                            ) {
                                val unsupportedText = stringResource(R.string.selftest_unsupported)
                                val waitingEventsText = stringResource(R.string.selftest_waiting_events)
                                val stepCounterText = when {
                                    !stepCounterSupported -> unsupportedText
                                    stepCounterFrames == 0 -> waitingEventsText
                                    else -> {
                                        val cur = stepCounterLatest ?: 0f
                                        val first = stepCounterFirst ?: cur
                                        val delta = (cur - first).roundToInt()
                                        stringResource(
                                            R.string.selftest_step_counter_fmt,
                                            cur,
                                            delta,
                                            stepCounterFrames
                                        )
                                    }
                                }
                                val stepDetectorText = if (!stepDetectorSupported) {
                                    unsupportedText
                                } else {
                                    stringResource(R.string.selftest_step_detector_fmt, stepDetectorCount)
                                }
                                val accelText = when {
                                    !accelSupported -> unsupportedText
                                    !hasAccel -> waitingEventsText
                                    else -> {
                                        val mag = sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
                                        String.format(
                                            Locale.US,
                                            "(%.2f, %.2f, %.2f) |a|=%.2f",
                                            accelX,
                                            accelY,
                                            accelZ,
                                            mag
                                        )
                                    }
                                }
                                val diagText = if (sensorDiag.isNotBlank()) {
                                    sensorDiag
                                } else {
                                    stringResource(R.string.selftest_waiting_telemetry)
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SelfTestMetricRow(stringResource(R.string.selftest_metric_step_counter), stepCounterText)
                                    SelfTestMetricRow(stringResource(R.string.selftest_metric_step_detector), stepDetectorText)
                                    SelfTestMetricRow(stringResource(R.string.selftest_metric_accel), accelText)
                                    SelfTestMetricRow(stringResource(R.string.selftest_metric_native_diag), diagText)
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.45f))

                Text(
                    text = stringResource(R.string.settings_rf_section),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.InkPrimary
                )

                NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, elevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_rf_hint),
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            color = NeuColors.InkSecondary
                        )
                        NeuButton(
                            onClick = {
                                onDismiss()
                                onOpenRfConfig()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            tone = NeuTone.COBALT,
                            cornerRadius = 11.dp,
                            contentPadding = PaddingValues(vertical = 9.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CellTower,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.settings_rf_btn),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.45f))

                Text(
                    text = stringResource(R.string.settings_diag_section),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.InkPrimary
                )

                NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, elevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 11.dp, vertical = 9.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.settings_diag_hint),
                            fontSize = 10.5.sp,
                            lineHeight = 14.sp,
                            color = NeuColors.InkSecondary
                        )
                        NeuButton(
                            onClick = {
                                onDismiss()
                                onOpenDiagnostics()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            tone = NeuTone.COBALT,
                            cornerRadius = 11.dp,
                            contentPadding = PaddingValues(vertical = 9.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.HealthAndSafety,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.settings_diag_btn),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.45f))

                Text(
                    text = stringResource(R.string.settings_update_section),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.InkPrimary
                )

                NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, elevation = 3.dp) {
                    val channelLabel = when (UpdateChecker.buildChannel()) {
                        "release" -> stringResource(R.string.settings_update_channel_release)
                        "ci" -> stringResource(R.string.settings_update_channel_ci)
                        else -> stringResource(R.string.settings_update_channel_local)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.settings_update_channel_fmt,
                                KurokoProtocol.APP_VERSION_NAME,
                                channelLabel
                            ),
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = NeuColors.InkSecondary
                        )
                        if (UpdateChecker.isUpdateCheckSupported()) {
                            SettingsToggleRow(
                                title = stringResource(R.string.settings_update_auto_title),
                                subtitle = stringResource(R.string.settings_update_auto_sub),
                                checked = autoCheckUpdate,
                                activeColor = NeuColors.Cobalt,
                                onCheckedChange = onToggleAutoCheckUpdate
                            )
                            NeuButton(
                                onClick = {
                                    if (!updateCheckBusy) {
                                        onManualCheckUpdate()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                tone = NeuTone.NEUTRAL,
                                cornerRadius = 11.dp,
                                contentPadding = PaddingValues(vertical = 9.dp)
                            ) {
                                if (updateCheckBusy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = NeuColors.CobaltDeep
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.SystemUpdate,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = stringResource(
                                        if (updateCheckBusy) R.string.settings_update_btn_checking
                                        else R.string.settings_update_btn_check
                                    ),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (!updateStatusText.isNullOrBlank()) {
                                Text(
                                    text = updateStatusText,
                                    fontSize = 10.5.sp,
                                    lineHeight = 14.sp,
                                    color = NeuColors.InkSecondary
                                )
                            }
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .neuInset(cornerRadius = 9.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.dp)
                                    .padding(horizontal = 9.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_update_disabled_hint),
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    color = NeuColors.InkSecondary
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.45f))

                Text(
                    text = stringResource(R.string.settings_hud_section),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.InkPrimary
                )

                NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, elevation = 3.dp) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_hud_enable_title),
                            subtitle = stringResource(R.string.settings_hud_enable_sub),
                            checked = overlayEnabled,
                            activeColor = NeuColors.LedGreen,
                            onCheckedChange = onToggleOverlay
                        )
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_hud_autohide_title),
                            subtitle = stringResource(R.string.settings_hud_autohide_sub),
                            checked = overlayAutoHideInApp,
                            activeColor = NeuColors.Cobalt,
                            onCheckedChange = onToggleOverlayAutoHide
                        )
                        SettingsToggleRow(
                            title = stringResource(R.string.settings_hud_secure_title),
                            subtitle = stringResource(R.string.settings_hud_secure_sub),
                            checked = overlaySecureFlag,
                            activeColor = NeuColors.CobaltDeep,
                            onCheckedChange = onToggleOverlaySecure
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .neuInset(cornerRadius = 9.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.dp)
                                .padding(horizontal = 9.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.settings_hud_battery_tip),
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                color = NeuColors.InkSecondary
                            )
                        }
                    }
                }

                HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.45f))

                Text(
                    text = stringResource(R.string.settings_keys_section),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.InkPrimary
                )
                Text(
                    text = stringResource(R.string.settings_keys_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = NeuColors.InkSecondary
                )

                OutlinedTextField(
                    value = tdt,
                    onValueChange = { tdt = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_tdt_label)) },
                    placeholder = { Text(stringResource(R.string.settings_tdt_placeholder)) }
                )

                OutlinedTextField(
                    value = amap,
                    onValueChange = { amap = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.settings_amap_label)) },
                    placeholder = { Text(stringResource(R.string.settings_amap_placeholder)) }
                )

                Spacer(Modifier.height(2.dp))
            }
        },
        confirmButton = {
            NeuButton(
                onClick = {
                    onSaveKeys(tdt.trim(), amap.trim())
                    onDismiss()
                },
                tone = NeuTone.COBALT,
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_close), color = NeuColors.InkSecondary)
            }
        }
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = NeuColors.InkPrimary
            )
            Text(
                text = subtitle,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                color = NeuColors.InkSecondary
            )
        }
        NeuPillSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            activeColor = activeColor
        )
    }
}

@Composable
private fun SelfTestMetricRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(
            text = label,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = NeuColors.InkSecondary
        )
        Text(
            text = value,
            fontSize = 10.5.sp,
            lineHeight = 13.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = NeuColors.InkPrimary
        )
    }
}
