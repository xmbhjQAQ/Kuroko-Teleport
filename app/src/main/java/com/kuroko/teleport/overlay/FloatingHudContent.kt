package com.kuroko.teleport.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuroko.teleport.R
import com.kuroko.teleport.geo.CoordTransform
import com.kuroko.teleport.ipc.DaemonSnapshot
import com.kuroko.teleport.ipc.MotionControlState
import com.kuroko.teleport.ipc.SimStatus
import com.kuroko.teleport.ipc.VersionCompatInfo
import com.kuroko.teleport.ipc.VersionCompatState
import com.kuroko.teleport.ipc.KurokoProtocol
import com.kuroko.teleport.ipc.evaluateVersionCompat
import com.kuroko.teleport.ui.CustomSpeedPreset
import com.kuroko.teleport.ui.NeuButton
import com.kuroko.teleport.ui.NeuColors
import com.kuroko.teleport.ui.NeuLedDot
import com.kuroko.teleport.ui.NeuSquareIconButton
import com.kuroko.teleport.ui.NeuTone
import com.kuroko.teleport.ui.KurokoLightColors
import com.kuroko.teleport.ui.displayName
import com.kuroko.teleport.ui.neuGlass
import com.kuroko.teleport.ui.neuInset
import com.kuroko.teleport.ui.neuRaised
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Root Compose content for the Floating HUD window (switches cleanly between
 * Morph A Docked Mini Capsule and Morph B Expanded 252dp Mini Console).
 */
@Composable
fun FloatingHudRoot(
    snapshot: DaemonSnapshot,
    versionCompat: VersionCompatInfo,
    motionState: MotionControlState,
    speedPresets: List<CustomSpeedPreset>,
    hasDraftRoute: Boolean,
    isExpanded: Boolean,
    isIdleDimmed: Boolean,
    isDragging: Boolean,
    onCollapse: () -> Unit,
    onCloseOverlay: () -> Unit,
    onOpenMain: () -> Unit,
    onPause: () -> Unit,
    onStartOrResume: () -> Unit,
    onStop: () -> Unit,
    onAdjustSpeed: (Float) -> Unit,
    onSelectPreset: (CustomSpeedPreset) -> Unit,
    onToggleCarry: () -> Unit,
    onResetSteps: () -> Unit
) {
    MaterialTheme(colorScheme = KurokoLightColors) {
        if (!isExpanded) {
            FloatingCapsuleContent(
                snapshot = snapshot,
                isIdleDimmed = isIdleDimmed,
                isDragging = isDragging
            )
        } else {
            FloatingExpandedContent(
                snapshot = snapshot,
                versionCompat = versionCompat,
                motionState = motionState,
                speedPresets = speedPresets,
                hasDraftRoute = hasDraftRoute,
                onCollapse = onCollapse,
                onCloseOverlay = onCloseOverlay,
                onOpenMain = onOpenMain,
                onPause = onPause,
                onStartOrResume = onStartOrResume,
                onStop = onStop,
                onAdjustSpeed = onAdjustSpeed,
                onSelectPreset = onSelectPreset,
                onToggleCarry = onToggleCarry,
                onResetSteps = onResetSteps
            )
        }
    }
}

/**
 * Morph A: Sleek Braun docked mini capsule (6dp hardware LED + single-line tabular core metric).
 * Softly dims to alpha ~0.78 after 4s idle; immediately brightens to 1.0 on touch/drag.
 */
@Composable
private fun FloatingCapsuleContent(
    snapshot: DaemonSnapshot,
    isIdleDimmed: Boolean,
    isDragging: Boolean
) {
    val st = snapshot.status
    val isCompleted = isSimCompleted(st)
    val capsuleAlpha by animateFloatAsState(
        targetValue = if (isIdleDimmed && !isDragging) 0.78f else 1.0f,
        animationSpec = tween(260),
        label = "capsule_idle_alpha"
    )

    val (ledColor, ledLit, ledPulse) = when {
        !snapshot.connected -> Triple(NeuColors.Coral, true, false)
        st.active && st.moving -> Triple(NeuColors.LedGreen, true, true)
        st.active -> Triple(NeuColors.Amber, true, false)
        else -> Triple(NeuColors.InkMuted, false, false)
    }

    val summaryText = when {
        !snapshot.connected -> stringResource(R.string.hud_status_offline)
        st.active && st.moving -> {
            val distStr = CoordTransform.formatDistance(st.totalDistanceM.toDouble()).replace(" ", "")
            val paceStr = CoordTransform.formatPace(st.speedMps).removeSuffix("/km")
            "$distStr · $paceStr"
        }
        isCompleted -> stringResource(R.string.hud_status_done)
        st.active && st.routeLengthM <= 1f -> stringResource(R.string.hud_capsule_holding)
        st.active -> stringResource(R.string.hud_status_paused)
        else -> stringResource(R.string.hud_status_idle)
    }

    Box(
        modifier = Modifier
            .padding(4.dp)
            .graphicsLayer { alpha = capsuleAlpha }
            .neuGlass(cornerRadius = 18.dp, surfaceColor = NeuColors.SurfaceGlassStrong, elevation = 7.dp)
            .padding(horizontal = 11.dp, vertical = 7.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            NeuLedDot(color = ledColor, lit = ledLit, isPulsing = ledPulse, size = 6.dp)
            Text(
                text = summaryText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = NeuColors.InkPrimary,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Morph B: Expanded 252dp Braun mini control console with draggable top bar,
 * recessed cool-sage LCD telemetry matrix, strict state-machine controls, speed stepper,
 * custom speed presets, carry toggle, and two-tap reset-steps confirmation.
 */
@Composable
private fun FloatingExpandedContent(
    snapshot: DaemonSnapshot,
    versionCompat: VersionCompatInfo,
    motionState: MotionControlState,
    speedPresets: List<CustomSpeedPreset>,
    hasDraftRoute: Boolean,
    onCollapse: () -> Unit,
    onCloseOverlay: () -> Unit,
    onOpenMain: () -> Unit,
    onPause: () -> Unit,
    onStartOrResume: () -> Unit,
    onStop: () -> Unit,
    onAdjustSpeed: (Float) -> Unit,
    onSelectPreset: (CustomSpeedPreset) -> Unit,
    onToggleCarry: () -> Unit,
    onResetSteps: () -> Unit
) {
    val context = LocalContext.current
    val st = snapshot.status
    val isCompleted = isSimCompleted(st)
    val isStaticActive = st.active && !st.moving && st.routeLengthM <= 1f
    val isDynamicActive = st.active && (st.moving || st.routeLengthM > 1f)
    val canStartOrResumeRoute = st.routeLengthM > 1f || hasDraftRoute
    val versionLocked = snapshot.connected && !versionCompat.canStartSimulation

    // Two-tap safety confirmation for RESET_STEPS (auto-cancels after 2.5s)
    var confirmResetSteps by remember { mutableStateOf(false) }
    LaunchedEffect(confirmResetSteps) {
        if (confirmResetSteps) {
            delay(2500L)
            confirmResetSteps = false
        }
    }

    val (ledColor, ledLit, ledPulse) = when {
        !snapshot.connected -> Triple(NeuColors.Coral, true, false)
        st.active && st.moving -> Triple(NeuColors.LedGreen, true, true)
        st.active -> Triple(NeuColors.Amber, true, false)
        else -> Triple(NeuColors.InkMuted, false, false)
    }

    val statusTitle = when {
        !snapshot.connected -> stringResource(R.string.hud_status_offline)
        isCompleted -> stringResource(R.string.hud_status_done)
        st.active && st.moving -> stringResource(R.string.hud_status_moving)
        isStaticActive -> stringResource(R.string.hud_status_holding)
        st.active -> stringResource(R.string.hud_status_paused)
        else -> stringResource(R.string.hud_status_idle)
    }

    Box(
        modifier = Modifier
            .padding(5.dp)
            .width(252.dp)
            .neuGlass(cornerRadius = 20.dp, surfaceColor = NeuColors.SurfaceGlassStrong, elevation = 10.dp)
            .padding(horizontal = 10.dp, vertical = 9.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1. Draggable Top Header Bar (LED + Status + L✓ P✓ S✓ + 3 Action Icons)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f, fill = false),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    NeuLedDot(color = ledColor, lit = ledLit, isPulsing = ledPulse, size = 6.dp)
                    Text(
                        text = statusTitle,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.InkPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    Box(
                        modifier = Modifier
                            .neuInset(cornerRadius = 7.dp, surfaceColor = NeuColors.LcdWellBg, depth = 1.8.dp)
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.5.dp)
                        ) {
                            val lOk = snapshot.connected && st.locShm
                            val pOk = snapshot.connected && st.phoneShm
                            val sOk = snapshot.connected && st.sensorShm
                            HudMiniLedBadge("L", lOk)
                            HudMiniLedBadge("P", pOk)
                            HudMiniLedBadge("S", sOk)
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    NeuSquareIconButton(
                        onClick = onOpenMain,
                        size = 26.dp,
                        cornerRadius = 8.dp,
                        contentColor = NeuColors.CobaltDeep
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.hud_cd_open_main),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    NeuSquareIconButton(
                        onClick = onCollapse,
                        size = 26.dp,
                        cornerRadius = 8.dp,
                        contentColor = NeuColors.InkPrimary
                    ) {
                        Icon(
                            Icons.Default.UnfoldLess,
                            contentDescription = stringResource(R.string.hud_cd_collapse),
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    NeuSquareIconButton(
                        onClick = onCloseOverlay,
                        size = 26.dp,
                        cornerRadius = 8.dp,
                        contentColor = NeuColors.Coral
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.hud_cd_close),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }

            // 2. Braun Recessed Cool-Sage LCD Telemetry Well
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .neuInset(cornerRadius = 12.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                    .padding(horizontal = 9.dp, vertical = 7.dp)
            ) {
                when {
                    isDynamicActive -> {
                        val distText = if (st.maxDistanceM > 1f) {
                            "${CoordTransform.formatDistance(st.totalDistanceM.toDouble()).replace(" ", "")}/${String.format(Locale.US, "%.1fkm", st.maxDistanceM / 1000f)}"
                        } else if (st.maxLaps > 0) {
                            stringResource(
                                R.string.hud_dist_laps_fmt,
                                CoordTransform.formatDistance(st.totalDistanceM.toDouble()).replace(" ", ""),
                                st.laps,
                                st.maxLaps
                            )
                        } else {
                            CoordTransform.formatDistance(st.totalDistanceM.toDouble())
                        }
                        val paceText = CoordTransform.formatPace(st.speedMps)
                        val stepText = "${st.virtualSteps} · ${st.cadenceSpm.roundToInt()}spm"
                        val etaOrLapTitle: String
                        val etaOrLapVal: String
                        if (isCompleted) {
                            etaOrLapTitle = stringResource(R.string.hud_cell_status)
                            etaOrLapVal = stringResource(R.string.hud_val_done_hold)
                        } else if (st.maxDistanceM > 1f || st.maxLaps > 0) {
                            val planned = computeHudPlannedDistM(
                                st.routeLengthM.toDouble(),
                                st.maxDistanceM,
                                st.maxLaps
                            )
                            val rem = (planned - st.totalDistanceM).coerceAtLeast(0.0)
                            val effSpd = (if (st.speedMps > 0.3f) st.speedMps else st.targetSpeedMps)
                                .coerceAtLeast(0.3f) * 0.97
                            etaOrLapTitle = stringResource(R.string.hud_cell_eta)
                            etaOrLapVal = formatHudClock(rem / effSpd)
                        } else {
                            etaOrLapTitle = stringResource(R.string.hud_cell_lap)
                            etaOrLapVal = stringResource(R.string.hud_val_lap_num, st.laps + 1)
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(modifier = Modifier.fillMaxWidth()) {
                                HudCell(stringResource(R.string.hud_cell_distance), distText, Modifier.weight(1f))
                                HudCell(stringResource(R.string.hud_cell_pace), paceText, Modifier.weight(1f))
                            }
                            Row(modifier = Modifier.fillMaxWidth()) {
                                HudCell(stringResource(R.string.hud_cell_steps), stepText, Modifier.weight(1f))
                                HudCell(etaOrLapTitle, etaOrLapVal, Modifier.weight(1f))
                            }
                        }
                    }

                    isStaticActive -> {
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = String.format(Locale.US, "WGS84 %.5f, %.5f", st.latWgs84, st.lonWgs84),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.InkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = stringResource(
                                    R.string.hud_static_meta_fmt,
                                    String.format(Locale.US, "%.1f", st.jitterSigmaM),
                                    st.targetCount
                                ),
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = NeuColors.InkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    else -> {
                        val standbyMsg = if (!snapshot.connected) {
                            stringResource(R.string.hud_standby_offline)
                        } else if (hasDraftRoute) {
                            stringResource(R.string.hud_standby_ready_route, st.targetCount)
                        } else {
                            stringResource(R.string.hud_standby_ready, st.targetCount)
                        }
                        Text(
                            text = standbyMsg,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = NeuColors.InkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            if (versionLocked) {
                val localizedCompat = remember(snapshot, versionCompat, context) {
                    evaluateVersionCompat(snapshot.connected, snapshot.status, context)
                }
                val lockBg = if (versionCompat.state == VersionCompatState.REBOOT_REQUIRED) {
                    NeuColors.Amber.copy(alpha = 0.16f)
                } else {
                    NeuColors.Coral.copy(alpha = 0.14f)
                }
                val lockFg = if (versionCompat.state == VersionCompatState.REBOOT_REQUIRED) {
                    NeuColors.AmberDeep
                } else {
                    NeuColors.Coral
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(lockBg)
                        .border(1.dp, lockFg.copy(alpha = 0.45f), RoundedCornerShape(9.dp))
                        .clickable { onOpenMain() }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = lockFg,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = stringResource(R.string.hud_version_lock_banner_fmt, localizedCompat.headline),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = lockFg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 3. Primary Control Buttons (strict state machine: Stop disabled/muted when !active!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                when {
                    st.active && st.moving -> {
                        // Moving -> Pause (amber accent) + Stop (coral)
                        NeuButton(
                            onClick = onPause,
                            modifier = Modifier.weight(1.25f),
                            tone = NeuTone.NEUTRAL,
                            cornerRadius = 11.dp,
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Pause,
                                contentDescription = null,
                                tint = NeuColors.AmberDeep,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(Modifier.width(3.dp))
                            Text(
                                text = stringResource(R.string.hud_btn_pause),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.AmberDeep,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        NeuButton(
                            onClick = onStop,
                            modifier = Modifier.weight(0.85f),
                            tone = NeuTone.CORAL,
                            cornerRadius = 11.dp,
                            enabled = true,
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = stringResource(R.string.hud_btn_stop),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    st.active -> {
                        // Holding / Paused / Static -> Resume (if route & unlocked) or Open App (static) + Stop (coral)
                        if (canStartOrResumeRoute && !isStaticActive) {
                            val lockTint = if (versionCompat.state == VersionCompatState.REBOOT_REQUIRED) {
                                NeuColors.AmberDeep
                            } else {
                                NeuColors.Coral
                            }
                            NeuButton(
                                onClick = onStartOrResume,
                                modifier = Modifier.weight(1.25f),
                                tone = if (versionLocked) NeuTone.NEUTRAL else NeuTone.EMERALD,
                                cornerRadius = 11.dp,
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (versionLocked) Icons.Default.Lock else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (versionLocked) lockTint else Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = stringResource(
                                        when {
                                            versionLocked && versionCompat.state == VersionCompatState.REBOOT_REQUIRED ->
                                                R.string.hud_btn_reboot
                                            versionLocked -> R.string.hud_btn_locked
                                            isCompleted -> R.string.hud_btn_restart
                                            else -> R.string.hud_btn_resume
                                        }
                                    ),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (versionLocked) lockTint else Color.White,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            NeuButton(
                                onClick = onOpenMain,
                                modifier = Modifier.weight(1.25f),
                                tone = NeuTone.COBALT,
                                cornerRadius = 11.dp,
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.hud_btn_open_app),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        NeuButton(
                            onClick = onStop,
                            modifier = Modifier.weight(0.85f),
                            tone = NeuTone.CORAL,
                            cornerRadius = 11.dp,
                            enabled = true,
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = stringResource(R.string.hud_btn_stop),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    else -> {
                        // Standby (!active) -> Start (if draft route) or Open App + Stop (disabled muted gray!)
                        if (hasDraftRoute) {
                            val lockTint = if (versionCompat.state == VersionCompatState.REBOOT_REQUIRED) {
                                NeuColors.AmberDeep
                            } else {
                                NeuColors.Coral
                            }
                            NeuButton(
                                onClick = onStartOrResume,
                                modifier = Modifier.weight(1.25f),
                                tone = if (versionLocked) NeuTone.NEUTRAL else NeuTone.EMERALD,
                                cornerRadius = 11.dp,
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(
                                    imageVector = if (versionLocked) Icons.Default.Lock else Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = if (versionLocked) lockTint else Color.White,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    text = stringResource(
                                        when {
                                            versionLocked && versionCompat.state == VersionCompatState.REBOOT_REQUIRED ->
                                                R.string.hud_btn_reboot
                                            versionLocked -> R.string.hud_btn_locked
                                            else -> R.string.hud_btn_start
                                        }
                                    ),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (versionLocked) lockTint else Color.White,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            NeuButton(
                                onClick = onOpenMain,
                                modifier = Modifier.weight(1.25f),
                                tone = NeuTone.COBALT,
                                cornerRadius = 11.dp,
                                contentPadding = PaddingValues(vertical = 8.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.OpenInNew,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.hud_btn_open_app),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        NeuButton(
                            onClick = {},
                            modifier = Modifier.weight(0.85f),
                            tone = NeuTone.CORAL,
                            cornerRadius = 11.dp,
                            enabled = false,
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = stringResource(R.string.hud_btn_stop),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            // 4. Quick Speed, Custom Presets, Carry Toggle & Two-Tap Reset Steps (hidden in static mode!)
            if (!isStaticActive && (st.active || hasDraftRoute)) {
                // [-0.3] speed / pace [+0.3]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    NeuButton(
                        onClick = { onAdjustSpeed(-0.3f) },
                        tone = NeuTone.NEUTRAL,
                        cornerRadius = 10.dp,
                        elevation = 3.dp,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "-0.3",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .neuInset(cornerRadius = 10.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.dp)
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val targetSpd = motionState.speedMps
                        Text(
                            text = "${String.format(Locale.US, "%.1f", targetSpd)}m/s · ${CoordTransform.formatPace(targetSpd).removeSuffix("/km")}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.InkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    NeuButton(
                        onClick = { onAdjustSpeed(0.3f) },
                        tone = NeuTone.NEUTRAL,
                        cornerRadius = 10.dp,
                        elevation = 3.dp,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "+0.3",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Single row of 4 compact speed preset chips (reads live user-customized presets!)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    speedPresets.take(4).forEach { preset ->
                        val selected = abs(motionState.speedMps - preset.speedMps) < 0.05f &&
                            motionState.motionMode == preset.motionMode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .then(
                                    if (selected) {
                                        Modifier
                                            .neuRaised(
                                                cornerRadius = 9.dp,
                                                surfaceColor = NeuColors.CobaltDeep,
                                                elevation = 2.5.dp,
                                                darkShadowColor = NeuColors.CobaltDeep.copy(alpha = 0.35f)
                                            )
                                            .clip(RoundedCornerShape(9.dp))
                                            .background(NeuColors.CobaltDeep)
                                    } else {
                                        Modifier
                                            .neuRaised(
                                                cornerRadius = 9.dp,
                                                surfaceColor = NeuColors.SurfaceMatte,
                                                elevation = 2.5.dp
                                            )
                                            .clip(RoundedCornerShape(9.dp))
                                    }
                                )
                                .clickable { onSelectPreset(preset) }
                                .padding(horizontal = 2.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = preset.displayName(context),
                                fontSize = 10.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                color = if (selected) Color.White else NeuColors.InkPrimary,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Bottom utility row: Hand/Pocket one-tap toggle + two-tap confirm Reset Steps
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isPocket = motionState.carryMode == KurokoProtocol.CARRY_POCKET
                    NeuButton(
                        onClick = onToggleCarry,
                        modifier = Modifier.weight(1f),
                        tone = NeuTone.NEUTRAL,
                        cornerRadius = 10.dp,
                        elevation = 3.dp,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        NeuLedDot(
                            color = if (isPocket) NeuColors.Cobalt else NeuColors.Emerald,
                            lit = true,
                            size = 5.5.dp
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = stringResource(
                                if (isPocket) R.string.hud_carry_pocket else R.string.hud_carry_hand
                            ),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    NeuButton(
                        onClick = {
                            if (confirmResetSteps) {
                                confirmResetSteps = false
                                onResetSteps()
                            } else {
                                confirmResetSteps = true
                            }
                        },
                        modifier = Modifier.weight(1.1f),
                        tone = if (confirmResetSteps) NeuTone.CORAL else NeuTone.NEUTRAL,
                        cornerRadius = 10.dp,
                        elevation = 3.dp,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            Icons.Default.RestartAlt,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(
                                if (confirmResetSteps) R.string.hud_btn_confirm_reset
                                else R.string.hud_btn_reset_steps
                            ),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lightweight bottom-center dismiss target shown only while dragging the capsule.
 */
@Composable
fun FloatingDismissZoneContent(isHovered: Boolean) {
    val scale by animateFloatAsState(
        targetValue = if (isHovered) 1.16f else 1.0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "dismiss_scale"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isHovered) Color(0xEBDC2626) else Color(0xCC1E293B),
        animationSpec = tween(160),
        label = "dismiss_bg"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isHovered) Color(0xFFFCA5A5) else Color.White.copy(alpha = 0.45f),
        animationSpec = tween(160),
        label = "dismiss_border"
    )

    MaterialTheme(colorScheme = KurokoLightColors) {
        Column(
            modifier = Modifier.padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(CircleShape)
                    .background(bgColor)
                    .border(1.5.dp, borderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = stringResource(R.string.hud_cd_close),
                    tint = Color.White,
                    modifier = Modifier.size(26.dp)
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xB80F172A))
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = stringResource(
                        if (isHovered) R.string.hud_dismiss_release else R.string.hud_dismiss_drag
                    ),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun HudMiniLedBadge(tag: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeuLedDot(
            color = NeuColors.LedGreen,
            lit = ok,
            size = 5.5.dp
        )
        Spacer(Modifier.width(1.5.dp))
        Text(
            text = if (ok) "${tag}✓" else "${tag}·",
            fontSize = 8.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = if (ok) NeuColors.InkPrimary else NeuColors.InkMuted,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun HudCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            fontSize = 9.sp,
            color = NeuColors.InkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = NeuColors.InkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

internal fun isSimCompleted(st: SimStatus): Boolean =
    st.active && !st.moving && (
        (st.maxLaps > 0 && st.laps >= st.maxLaps) ||
            (st.maxDistanceM > 1f && st.totalDistanceM >= st.maxDistanceM - 1f)
        )

private fun computeHudPlannedDistM(
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

private fun formatHudClock(totalSec: Double): String {
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
