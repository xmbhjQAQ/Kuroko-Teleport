package com.kuroko.teleport.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kuroko.teleport.R
import com.kuroko.teleport.diag.DiagnosticCollector
import com.kuroko.teleport.diag.DiagnosticSnapshot
import com.kuroko.teleport.diag.HealthCheckItem
import com.kuroko.teleport.diag.HealthStatus
import com.kuroko.teleport.ipc.DaemonSnapshot
import com.kuroko.teleport.ipc.MotionControlState
import com.kuroko.teleport.ipc.OverlayPrefsState
import com.kuroko.teleport.ipc.VersionCompatInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class DiagTab {
    SUMMARY,
    DAEMON,
    LSPOSED,
    SYSTEM,
    APP
}

private val TERMINAL_ERR_REGEX = Regex(
    """(\[(?:E|ERROR)\]|(?:\s[EF]\s)|\b(?:ERR|ERROR|FAIL|FAILED|EXCEPTION|AVCDENIED|ABORT|TRIPPED|MISMATCH)\b|\bavc:\s*denied\b)""",
    RegexOption.IGNORE_CASE
)
private val TERMINAL_WARN_REGEX = Regex(
    """(\[(?:W|WARN)\]|(?:\sW\s)|\b(?:WARN|WARNING|TIMEOUT|BLOCKED)\b)""",
    RegexOption.IGNORE_CASE
)

@Composable
fun DiagnosticsDialog(
    daemonSnap: DaemonSnapshot,
    versionCompat: VersionCompatInfo,
    motionState: MotionControlState,
    overlayPrefs: OverlayPrefsState,
    savedTargets: List<SavedTargetApp>,
    appLangTag: String,
    tiandituToken: String,
    amapWebKey: String,
    onDismiss: () -> Unit,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf<DiagnosticSnapshot?>(null) }
    var collecting by remember { mutableStateOf(true) }
    var exporting by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(DiagTab.SUMMARY) }
    var errorsOnly by remember { mutableStateOf(false) }
    var anonymizePackages by remember { mutableStateOf(true) }
    var refreshNonce by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshNonce) {
        collecting = true
        val collected = DiagnosticCollector.collect(
            context = context,
            snap = daemonSnap,
            compat = versionCompat,
            motion = motionState,
            overlayPrefs = overlayPrefs,
            savedTargets = savedTargets,
            appLangTag = appLangTag,
            apiKeysToScrub = listOf(tiandituToken, amapWebKey)
        )
        snapshot = collected
        collecting = false
    }

    val saveZipLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val snap = snapshot ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            exporting = true
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = DiagnosticCollector.buildDiagnosticZipBytes(snap, context, anonymizePackages)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(bytes)
                        out.flush()
                    } ?: error("Cannot open output stream")
                    bytes.size
                }
            }
            exporting = false
            result.onSuccess { sizeBytes ->
                onNotify(
                    context.getString(
                        R.string.diag_saved_ok_fmt,
                        DiagnosticCollector.formatByteSize(sizeBytes)
                    )
                )
            }.onFailure { err ->
                onNotify(
                    context.getString(
                        R.string.diag_export_failed_fmt,
                        err.message ?: err.javaClass.simpleName
                    )
                )
            }
        }
    }

    val healthChecks = remember(snapshot, appLangTag) {
        snapshot?.let { DiagnosticCollector.evaluateHealth(it, context) } ?: emptyList()
    }

    val activeTabText = remember(snapshot, selectedTab, errorsOnly, anonymizePackages, appLangTag) {
        val snap = snapshot ?: return@remember ""
        val raw = when (selectedTab) {
            DiagTab.SUMMARY -> DiagnosticCollector.buildClipboardSummary(snap, context, anonymizePackages)
            DiagTab.DAEMON -> DiagnosticCollector.applyPackageAnonymization(
                snap.daemonLog,
                snap.savedTargets,
                anonymizePackages
            )
            DiagTab.LSPOSED -> DiagnosticCollector.applyPackageAnonymization(
                snap.lsposedLog,
                snap.savedTargets,
                anonymizePackages
            )
            DiagTab.SYSTEM -> DiagnosticCollector.buildSystemTabText(snap, anonymizePackages)
            DiagTab.APP -> DiagnosticCollector.applyPackageAnonymization(
                snap.appEventsLog,
                snap.savedTargets,
                anonymizePackages
            )
        }
        if (errorsOnly && selectedTab != DiagTab.SUMMARY) {
            DiagnosticCollector.filterWarnErrorLines(raw, isLogcat = selectedTab == DiagTab.SYSTEM)
        } else {
            raw
        }
    }

    val renderedTerminal = remember(activeTabText) {
        if (activeTabText.isBlank()) {
            null
        } else {
            // Keep UI rendering crisp by displaying the most recent 500 lines (full logs always go to ZIP)
            val lines = activeTabText.lines()
            val tail = if (lines.size > 500) lines.takeLast(500) else lines
            buildAnnotatedString {
                tail.forEachIndexed { idx, line ->
                    if (idx > 0) append('\n')
                    val style = when {
                        line.startsWith("#") -> SpanStyle(
                            color = NeuColors.CobaltDeep,
                            fontWeight = FontWeight.Bold
                        )
                        TERMINAL_ERR_REGEX.containsMatchIn(line) -> SpanStyle(
                            color = NeuColors.CoralDeep,
                            fontWeight = FontWeight.SemiBold
                        )
                        TERMINAL_WARN_REGEX.containsMatchIn(line) -> SpanStyle(
                            color = NeuColors.AmberDeep,
                            fontWeight = FontWeight.Medium
                        )
                        else -> SpanStyle(color = NeuColors.InkPrimary)
                    }
                    withStyle(style) {
                        append(line)
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(22.dp))
                .background(NeuColors.Surface)
                .border(1.dp, NeuColors.GlassRimTop, RoundedCornerShape(22.dp))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.HealthAndSafety,
                            contentDescription = null,
                            tint = NeuColors.CobaltDeep,
                            modifier = Modifier.size(22.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.diag_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.InkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            val snap = snapshot
                            val subtitle = when {
                                collecting && snap == null -> stringResource(R.string.diag_collecting)
                                snap != null -> {
                                    val rootTag = stringResource(
                                        if (snap.rootAvailable) R.string.diag_root_ok else R.string.diag_root_no
                                    )
                                    stringResource(R.string.diag_collected_at_fmt, snap.collectedAtClock, rootTag)
                                }
                                else -> stringResource(R.string.diag_collecting)
                            }
                            Text(
                                text = subtitle,
                                fontSize = 10.5.sp,
                                color = NeuColors.InkSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        NeuSquareIconButton(
                            onClick = { if (!collecting) refreshNonce += 1 },
                            size = 32.dp,
                            cornerRadius = 10.dp,
                            contentColor = NeuColors.CobaltDeep
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.diag_btn_refresh),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        NeuSquareIconButton(
                            onClick = onDismiss,
                            size = 32.dp,
                            cornerRadius = 10.dp,
                            contentColor = NeuColors.InkSecondary
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.diag_btn_close),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }

                // Scrollable Main Body (Auto Health Check + Log Tabs + Braun LCD Terminal)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    val curSnap = snapshot
                    if (curSnap != null && !curSnap.rootAvailable) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(NeuColors.AmberSoft)
                                .border(1.dp, NeuColors.Amber.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = NeuColors.AmberDeep,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = stringResource(R.string.diag_root_unavailable),
                                fontSize = 11.sp,
                                lineHeight = 14.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NeuColors.AmberDeep
                            )
                        }
                    }

                    // Top Card: Auto Health Check
                    NeuCard(
                        modifier = Modifier.fillMaxWidth(),
                        cornerRadius = 14.dp,
                        elevation = 3.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 11.dp, vertical = 9.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.diag_health_title),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.InkPrimary
                            )
                            if (healthChecks.isEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.padding(vertical = 6.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(15.dp),
                                        strokeWidth = 2.dp,
                                        color = NeuColors.CobaltDeep
                                    )
                                    Text(
                                        text = stringResource(R.string.diag_collecting),
                                        fontSize = 11.sp,
                                        color = NeuColors.InkSecondary
                                    )
                                }
                            } else {
                                healthChecks.forEachIndexed { idx, item ->
                                    if (idx > 0) {
                                        HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.3f))
                                    }
                                    HealthCheckRow(item = item)
                                }
                            }
                        }
                    }

                    // Middle: 5 Filter Tabs
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        val tabLabels = listOf(
                            DiagTab.SUMMARY to R.string.diag_tab_summary,
                            DiagTab.DAEMON to R.string.diag_tab_daemon,
                            DiagTab.LSPOSED to R.string.diag_tab_lsposed,
                            DiagTab.SYSTEM to R.string.diag_tab_system,
                            DiagTab.APP to R.string.diag_tab_app
                        )
                        for ((tab, resId) in tabLabels) {
                            NeuChip(
                                label = stringResource(resId),
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab }
                            )
                        }
                    }

                    // Warn/Err Only Filter Bar
                    if (selectedTab != DiagTab.SUMMARY) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.diag_filter_errors_only),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = NeuColors.InkSecondary
                            )
                            NeuPillSwitch(
                                checked = errorsOnly,
                                onCheckedChange = { errorsOnly = it },
                                activeColor = NeuColors.AmberDeep
                            )
                        }
                    }

                    // Pale-Sage Braun Recessed LCD Monospace Terminal Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .neuInset(
                                cornerRadius = 12.dp,
                                surfaceColor = NeuColors.LcdWellBg,
                                depth = 3.dp
                            )
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        when {
                            collecting && snapshot == null -> {
                                Column(
                                    modifier = Modifier.align(Alignment.Center),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        strokeWidth = 2.4.dp,
                                        color = NeuColors.CobaltDeep
                                    )
                                    Text(
                                        text = stringResource(R.string.diag_collecting),
                                        fontSize = 11.sp,
                                        color = NeuColors.InkSecondary
                                    )
                                }
                            }
                            renderedTerminal == null -> {
                                Text(
                                    text = stringResource(R.string.diag_empty_log),
                                    fontSize = 11.sp,
                                    fontStyle = FontStyle.Italic,
                                    color = NeuColors.InkMuted,
                                    modifier = Modifier.align(Alignment.Center)
                                )
                            }
                            else -> {
                                val vScroll = rememberScrollState()
                                val hScroll = rememberScrollState()
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalScroll(vScroll)
                                        .horizontalScroll(hScroll)
                                ) {
                                    SelectionContainer {
                                        Text(
                                            text = renderedTerminal,
                                            fontSize = 10.sp,
                                            lineHeight = 14.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.45f))

                // Bottom Privacy Toggle + Note + 3 Export Buttons
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stringResource(R.string.diag_redact_packages),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = NeuColors.InkPrimary,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        NeuPillSwitch(
                            checked = anonymizePackages,
                            onCheckedChange = { anonymizePackages = it },
                            activeColor = NeuColors.LedGreen
                        )
                    }

                    Text(
                        text = stringResource(R.string.diag_privacy_note),
                        fontSize = 10.sp,
                        lineHeight = 13.5.sp,
                        color = NeuColors.InkSecondary
                    )

                    val actionsEnabled = snapshot != null && !collecting && !exporting
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        NeuButton(
                            onClick = {
                                val snap = snapshot ?: return@NeuButton
                                val summary = DiagnosticCollector.buildClipboardSummary(snap, context, anonymizePackages)
                                val ok = runCatching {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(
                                        ClipData.newPlainText(
                                            context.getString(R.string.diag_clipboard_label),
                                            summary
                                        )
                                    )
                                    cm != null
                                }.getOrDefault(false)
                                if (ok) {
                                    onNotify(context.getString(R.string.diag_copied_ok))
                                } else {
                                    onNotify(context.getString(R.string.diag_export_failed_fmt, "clipboard"))
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = actionsEnabled,
                            tone = NeuTone.NEUTRAL,
                            cornerRadius = 11.dp,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 9.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.diag_btn_copy),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        NeuButton(
                            onClick = {
                                val snap = snapshot ?: return@NeuButton
                                scope.launch {
                                    exporting = true
                                    val result = withContext(Dispatchers.IO) {
                                        runCatching {
                                            val bytes = DiagnosticCollector.buildDiagnosticZipBytes(
                                                snap,
                                                context,
                                                anonymizePackages
                                            )
                                            DiagnosticCollector.createShareableZipUri(
                                                context,
                                                bytes,
                                                snap.collectedAtMs
                                            )
                                        }
                                    }
                                    exporting = false
                                    result.onSuccess { uri ->
                                        runCatching {
                                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                                type = "application/zip"
                                                putExtra(Intent.EXTRA_STREAM, uri)
                                                putExtra(
                                                    Intent.EXTRA_SUBJECT,
                                                    context.getString(R.string.diag_clipboard_label)
                                                )
                                                clipData = ClipData.newRawUri(
                                                    context.getString(R.string.diag_clipboard_label),
                                                    uri
                                                )
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            val chooser = Intent.createChooser(
                                                sendIntent,
                                                context.getString(R.string.diag_share_chooser)
                                            ).apply {
                                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            }
                                            context.startActivity(chooser)
                                        }.onFailure { err ->
                                            onNotify(
                                                context.getString(
                                                    R.string.diag_export_failed_fmt,
                                                    err.message ?: err.javaClass.simpleName
                                                )
                                            )
                                        }
                                    }.onFailure { err ->
                                        onNotify(
                                            context.getString(
                                                R.string.diag_export_failed_fmt,
                                                err.message ?: err.javaClass.simpleName
                                            )
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = actionsEnabled,
                            tone = NeuTone.COBALT,
                            cornerRadius = 11.dp,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 9.dp)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.diag_btn_share),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        NeuButton(
                            onClick = {
                                val snap = snapshot ?: return@NeuButton
                                runCatching {
                                    saveZipLauncher.launch(DiagnosticCollector.suggestedZipFileName(snap.collectedAtMs))
                                }.onFailure { err ->
                                    onNotify(
                                        context.getString(
                                            R.string.diag_export_failed_fmt,
                                            err.message ?: err.javaClass.simpleName
                                        )
                                    )
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = actionsEnabled,
                            tone = NeuTone.EMERALD,
                            cornerRadius = 11.dp,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 9.dp)
                        ) {
                            Icon(Icons.Default.SaveAlt, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.diag_btn_save),
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
        }
    }
}

@Composable
private fun HealthCheckRow(item: HealthCheckItem) {
    val (pillBg, pillInk, pillTextRes) = when (item.status) {
        HealthStatus.PASS -> Triple(NeuColors.EmeraldSoft, NeuColors.EmeraldDeep, R.string.diag_status_pass)
        HealthStatus.WARN -> Triple(NeuColors.AmberSoft, NeuColors.AmberDeep, R.string.diag_status_warn)
        HealthStatus.FAIL -> Triple(NeuColors.CoralSoft, NeuColors.CoralDeep, R.string.diag_status_fail)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(pillBg)
                .border(1.dp, pillInk.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(pillTextRes),
                fontSize = 9.5.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = pillInk,
                maxLines = 1,
                softWrap = false
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = item.title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = NeuColors.InkPrimary
            )
            Text(
                text = item.detail,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                color = if (item.status == HealthStatus.PASS) NeuColors.InkSecondary else pillInk
            )
        }
    }
}
