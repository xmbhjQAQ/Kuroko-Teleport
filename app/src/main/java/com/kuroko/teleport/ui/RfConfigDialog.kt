package com.kuroko.teleport.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kuroko.teleport.R
import com.kuroko.teleport.geo.GeoPoint
import com.kuroko.teleport.ipc.DaemonSnapshot
import com.kuroko.teleport.ipc.KurokoProtocol
import com.kuroko.teleport.rf.GmsToggleFeedback
import com.kuroko.teleport.rf.LiveScanOutcome
import com.kuroko.teleport.rf.PinnedBleBeacon
import com.kuroko.teleport.rf.PinnedCell
import com.kuroko.teleport.rf.PinnedWifiAp
import com.kuroko.teleport.rf.RfConfig
import com.kuroko.teleport.rf.RfConfigStore
import com.kuroko.teleport.rf.RfFormatting
import com.kuroko.teleport.rf.RfLiveImporter
import com.kuroko.teleport.rf.executeGmsToggle
import kotlinx.coroutines.launch
import java.util.Locale

private enum class RfTab {
    GENERAL,
    CELL,
    WIFI,
    BLE
}

private enum class PendingScanKind {
    NONE,
    CELL,
    WIFI,
    BLE
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RfConfigDialog(
    initialConfig: RfConfig,
    daemonSnap: DaemonSnapshot,
    mapCenterWgs84: GeoPoint,
    onDismiss: () -> Unit,
    onSaveAndSync: (RfConfig) -> Unit,
    onSetGmsEnabled: suspend (Boolean) -> Boolean,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rfStore = remember(context) { RfConfigStore(context) }

    var draft by remember(initialConfig) { mutableStateOf(initialConfig) }
    var activeTab by remember { mutableStateOf(RfTab.GENERAL) }

    var scanning by remember { mutableStateOf(false) }
    var fetchingOpenCellId by remember { mutableStateOf(false) }
    var pendingScan by remember { mutableStateOf(PendingScanKind.NONE) }

    var editingCell by remember { mutableStateOf<PinnedCell?>(null) }
    var editingCellIsNew by remember { mutableStateOf(false) }

    var editingWifi by remember { mutableStateOf<PinnedWifiAp?>(null) }
    var editingWifiIsNew by remember { mutableStateOf(false) }

    var editingBle by remember { mutableStateOf<PinnedBleBeacon?>(null) }
    var editingBleIsNew by remember { mutableStateOf(false) }

    var showImportJsonDialog by remember { mutableStateOf(false) }

    fun runCellScan() {
        scope.launch {
            scanning = true
            val res = RfLiveImporter.scanNearbyCells(
                context = context,
                pinLat = mapCenterWgs84.lat,
                pinLon = mapCenterWgs84.lon,
                followVirtual = true
            )
            scanning = false
            when (res) {
                is LiveScanOutcome.Success -> {
                    if (res.items.isEmpty()) {
                        onNotify(context.getString(R.string.rf_scan_empty))
                    } else {
                        val merged = (res.items + draft.pinnedCells)
                            .distinctBy { "${it.rat}:${it.mcc}:${it.mnc}:${it.ci}" }
                            .take(KurokoProtocol.MAX_PINNED_CELLS)
                        draft = draft.copy(pinnedCells = merged)
                        onNotify(context.getString(R.string.rf_scan_imported_fmt, res.items.size))
                    }
                }
                LiveScanOutcome.PermissionRequired -> onNotify(context.getString(R.string.rf_scan_perm_denied))
                LiveScanOutcome.BluetoothDisabled -> onNotify(context.getString(R.string.rf_scan_bt_off))
                is LiveScanOutcome.Failed -> onNotify(res.detail)
            }
        }
    }

    fun runWifiScan() {
        scope.launch {
            scanning = true
            val res = RfLiveImporter.scanNearbyWifis(
                context = context,
                pinLat = mapCenterWgs84.lat,
                pinLon = mapCenterWgs84.lon,
                followVirtual = true
            )
            scanning = false
            when (res) {
                is LiveScanOutcome.Success -> {
                    if (res.items.isEmpty()) {
                        onNotify(context.getString(R.string.rf_scan_empty))
                    } else {
                        val merged = (res.items + draft.pinnedWifis)
                            .distinctBy { it.bssid.uppercase(Locale.US) }
                            .take(KurokoProtocol.MAX_PINNED_WIFIS)
                        draft = draft.copy(pinnedWifis = merged)
                        onNotify(context.getString(R.string.rf_scan_imported_fmt, res.items.size))
                    }
                }
                LiveScanOutcome.PermissionRequired -> onNotify(context.getString(R.string.rf_scan_perm_denied))
                LiveScanOutcome.BluetoothDisabled -> onNotify(context.getString(R.string.rf_scan_bt_off))
                is LiveScanOutcome.Failed -> onNotify(res.detail)
            }
        }
    }

    fun runBleScan() {
        scope.launch {
            scanning = true
            val res = RfLiveImporter.scanNearbyBleBeacons(
                context = context,
                pinLat = mapCenterWgs84.lat,
                pinLon = mapCenterWgs84.lon,
                followVirtual = true,
                durationMs = 4000L
            )
            scanning = false
            when (res) {
                is LiveScanOutcome.Success -> {
                    if (res.items.isEmpty()) {
                        onNotify(context.getString(R.string.rf_scan_empty))
                    } else {
                        val merged = (res.items + draft.pinnedBeacons)
                            .distinctBy { it.mac.uppercase(Locale.US) }
                            .take(KurokoProtocol.MAX_PINNED_BEACONS)
                        draft = draft.copy(pinnedBeacons = merged)
                        onNotify(context.getString(R.string.rf_scan_imported_fmt, res.items.size))
                    }
                }
                LiveScanOutcome.PermissionRequired -> onNotify(context.getString(R.string.rf_scan_perm_denied))
                LiveScanOutcome.BluetoothDisabled -> onNotify(context.getString(R.string.rf_scan_bt_off))
                is LiveScanOutcome.Failed -> onNotify(res.detail)
            }
        }
    }

    val fineLocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        val next = pendingScan
        pendingScan = PendingScanKind.NONE
        if (!granted) {
            onNotify(context.getString(R.string.rf_scan_perm_denied))
            return@rememberLauncherForActivityResult
        }
        when (next) {
            PendingScanKind.CELL -> runCellScan()
            PendingScanKind.WIFI -> runWifiScan()
            else -> Unit
        }
    }

    val btPermsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        pendingScan = PendingScanKind.NONE
        val scanGranted = result[Manifest.permission.BLUETOOTH_SCAN] == true
        val locGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (!scanGranted || !locGranted) {
            onNotify(context.getString(R.string.rf_scan_perm_denied))
            return@rememberLauncherForActivityResult
        }
        runBleScan()
    }

    val st = daemonSnap.status
    var gmsEnabledState by remember { mutableStateOf(st.gmsEnabled) }
    var gmsUpdating by remember { mutableStateOf(false) }

    LaunchedEffect(st.gmsEnabled, gmsUpdating) {
        if (!gmsUpdating) {
            gmsEnabledState = st.gmsEnabled
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.90f)
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
                // Header
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
                            imageVector = Icons.Default.CellTower,
                            contentDescription = null,
                            tint = NeuColors.CobaltDeep,
                            modifier = Modifier.size(22.dp)
                        )
                        Text(
                            text = stringResource(R.string.rf_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.InkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
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
                            contentDescription = stringResource(R.string.common_close),
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                // Live RF Telemetry Well (Braun Cool-Sage Recessed LCD)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuInset(cornerRadius = 12.dp, surfaceColor = NeuColors.LcdWellBg, depth = 3.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            val countryDisp = st.rfCountry.uppercase(Locale.US).ifBlank { "--" }
                            Text(
                                text = stringResource(
                                    R.string.rf_telemetry_fmt,
                                    countryDisp,
                                    st.rfMcc,
                                    st.rfMnc,
                                    st.rfCells,
                                    st.rfWifis,
                                    st.rfBles
                                ),
                                fontSize = 10.5.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = NeuColors.InkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                val btOk = daemonSnap.connected && st.btShm
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (btOk) NeuColors.EmeraldSoft else NeuColors.InsetBg)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = stringResource(if (btOk) R.string.rf_bt_ready else R.string.rf_bt_optional),
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (btOk) NeuColors.EmeraldDeep else NeuColors.InkSecondary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                val gmsOk = daemonSnap.connected && st.gmsShm
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (gmsOk) NeuColors.EmeraldSoft else NeuColors.InsetBg)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = stringResource(if (gmsOk) R.string.rf_gms_badge_ok else R.string.rf_gms_badge_off),
                                        fontSize = 9.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (gmsOk) NeuColors.EmeraldDeep else NeuColors.InkSecondary,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                        Text(
                            text = if (st.rfServing.isNotBlank()) {
                                stringResource(R.string.rf_serving_fmt, st.rfServing)
                            } else {
                                stringResource(R.string.rf_serving_none)
                            },
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = NeuColors.InkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 4 Sub-Tabs: General / Cell / Wi-Fi / BLE
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    NeuChip(
                        label = stringResource(R.string.rf_tab_general),
                        selected = activeTab == RfTab.GENERAL,
                        onClick = { activeTab = RfTab.GENERAL }
                    )
                    NeuChip(
                        label = stringResource(R.string.rf_tab_cell, draft.pinnedCells.size),
                        selected = activeTab == RfTab.CELL,
                        onClick = { activeTab = RfTab.CELL }
                    )
                    NeuChip(
                        label = stringResource(R.string.rf_tab_wifi, draft.pinnedWifis.size),
                        selected = activeTab == RfTab.WIFI,
                        onClick = { activeTab = RfTab.WIFI }
                    )
                    NeuChip(
                        label = stringResource(R.string.rf_tab_ble, draft.pinnedBeacons.size),
                        selected = activeTab == RfTab.BLE,
                        onClick = { activeTab = RfTab.BLE }
                    )
                }

                // Main Scrollable Tab Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (activeTab) {
                        RfTab.GENERAL -> {
                            // 6 Global RF Synthesis Toggles
                            NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, elevation = 3.dp) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    RfToggleRow(
                                        title = stringResource(R.string.rf_toggle_cell_auto_title),
                                        subtitle = stringResource(R.string.rf_toggle_cell_auto_sub),
                                        checked = draft.cellAuto,
                                        activeColor = NeuColors.LedGreen,
                                        onCheckedChange = { draft = draft.copy(cellAuto = it) }
                                    )
                                    RfToggleRow(
                                        title = stringResource(R.string.rf_toggle_prefer_5g_title),
                                        subtitle = stringResource(R.string.rf_toggle_prefer_5g_sub),
                                        checked = draft.cellPrefer5g,
                                        activeColor = NeuColors.Cobalt,
                                        onCheckedChange = { draft = draft.copy(cellPrefer5g = it) }
                                    )
                                    RfToggleRow(
                                        title = stringResource(R.string.rf_toggle_wifi_auto_title),
                                        subtitle = stringResource(R.string.rf_toggle_wifi_auto_sub),
                                        checked = draft.wifiAuto,
                                        activeColor = NeuColors.LedGreen,
                                        onCheckedChange = { draft = draft.copy(wifiAuto = it) }
                                    )
                                    RfToggleRow(
                                        title = stringResource(R.string.rf_toggle_wifi_conn_title),
                                        subtitle = stringResource(R.string.rf_toggle_wifi_conn_sub),
                                        checked = draft.wifiSpoofConnected,
                                        activeColor = NeuColors.Cobalt,
                                        onCheckedChange = { draft = draft.copy(wifiSpoofConnected = it) }
                                    )
                                    RfToggleRow(
                                        title = stringResource(R.string.rf_toggle_ble_ambient_title),
                                        subtitle = stringResource(R.string.rf_toggle_ble_ambient_sub),
                                        checked = draft.bleAmbient,
                                        activeColor = NeuColors.CobaltDeep,
                                        onCheckedChange = { draft = draft.copy(bleAmbient = it) }
                                    )
                                    RfToggleRow(
                                        title = stringResource(R.string.rf_toggle_sim_country_title),
                                        subtitle = stringResource(R.string.rf_toggle_sim_country_sub),
                                        checked = draft.spoofSimCountry,
                                        activeColor = NeuColors.AmberDeep,
                                        onCheckedChange = { draft = draft.copy(spoofSimCountry = it) }
                                    )
                                    HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.35f))
                                    RfToggleRow(
                                        title = stringResource(R.string.rf_toggle_gms_flp_title),
                                        subtitle = stringResource(R.string.rf_toggle_gms_flp_sub),
                                        checked = gmsEnabledState,
                                        activeColor = NeuColors.EmeraldDeep,
                                        onCheckedChange = { next ->
                                            if (gmsUpdating) return@RfToggleRow
                                            val prevConfirmed = st.gmsEnabled
                                            gmsUpdating = true
                                            gmsEnabledState = next
                                            scope.launch {
                                                try {
                                                    val outcome = executeGmsToggle(
                                                        prevConfirmed = prevConfirmed,
                                                        requestedEnabled = next,
                                                        onSetGmsEnabled = onSetGmsEnabled
                                                    )
                                                    gmsEnabledState = outcome.confirmedState
                                                    val msgRes = when (outcome.feedback) {
                                                        GmsToggleFeedback.ENABLED -> R.string.rf_gms_toggled_enabled
                                                        GmsToggleFeedback.DISABLED -> R.string.rf_gms_toggled_disabled
                                                        GmsToggleFeedback.FAILED -> R.string.rf_gms_toggle_failed
                                                    }
                                                    onNotify(context.getString(msgRes))
                                                } finally {
                                                    gmsUpdating = false
                                                }
                                            }
                                        }
                                    )
                                }
                            }

                            // Carrier & Country Selector
                            NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, elevation = 3.dp) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.rf_carrier_section),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeuColors.InkPrimary
                                    )
                                    val matchedPresetId = if (draft.carrierAuto) {
                                        null
                                    } else {
                                        RfFormatting.CARRIER_PRESETS.firstOrNull {
                                            it.mcc == draft.carrierMcc &&
                                                it.mnc == draft.carrierMnc &&
                                                it.countryIso.equals(draft.carrierCountryIso, ignoreCase = true)
                                        }?.id
                                    }
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        NeuChip(
                                            label = stringResource(R.string.rf_carrier_auto),
                                            selected = draft.carrierAuto,
                                            onClick = { draft = draft.copy(carrierAuto = true) }
                                        )
                                        for (preset in RfFormatting.CARRIER_PRESETS) {
                                            NeuChip(
                                                label = preset.label,
                                                selected = !draft.carrierAuto && matchedPresetId == preset.id,
                                                onClick = {
                                                    draft = draft.copy(
                                                        carrierAuto = false,
                                                        carrierMcc = preset.mcc,
                                                        carrierMnc = preset.mnc,
                                                        carrierMncDigits = preset.mncDigits,
                                                        carrierCountryIso = preset.countryIso,
                                                        carrierAlpha = preset.alpha
                                                    )
                                                }
                                            )
                                        }
                                        NeuChip(
                                            label = stringResource(R.string.rf_carrier_custom),
                                            selected = !draft.carrierAuto && matchedPresetId == null,
                                            onClick = {
                                                draft = draft.copy(
                                                    carrierAuto = false,
                                                    carrierMcc = if (draft.carrierMcc <= 0) 460 else draft.carrierMcc,
                                                    carrierCountryIso = draft.carrierCountryIso.ifBlank { "cn" }
                                                )
                                            }
                                        )
                                    }

                                    if (!draft.carrierAuto) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = if (draft.carrierMcc > 0) draft.carrierMcc.toString() else "",
                                                onValueChange = { v ->
                                                    draft = draft.copy(carrierMcc = v.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0)
                                                },
                                                modifier = Modifier.weight(1f),
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                label = { Text(stringResource(R.string.rf_field_mcc), fontSize = 11.sp) }
                                            )
                                            OutlinedTextField(
                                                value = draft.carrierMnc.toString(),
                                                onValueChange = { v ->
                                                    draft = draft.copy(carrierMnc = v.filter { it.isDigit() }.take(3).toIntOrNull() ?: 0)
                                                },
                                                modifier = Modifier.weight(1f),
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                label = { Text(stringResource(R.string.rf_field_mnc), fontSize = 11.sp) }
                                            )
                                            OutlinedTextField(
                                                value = draft.carrierMncDigits.toString(),
                                                onValueChange = { v ->
                                                    val d = v.filter { it.isDigit() }.take(1).toIntOrNull()
                                                    draft = draft.copy(carrierMncDigits = if (d == 3) 3 else 2)
                                                },
                                                modifier = Modifier.weight(0.9f),
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                label = { Text(stringResource(R.string.rf_field_mnc_digits), fontSize = 10.sp) }
                                            )
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = draft.carrierCountryIso,
                                                onValueChange = { v ->
                                                    draft = draft.copy(
                                                        carrierCountryIso = v.lowercase(Locale.US).filter { it in 'a'..'z' }.take(2)
                                                    )
                                                },
                                                modifier = Modifier.weight(0.9f),
                                                singleLine = true,
                                                label = { Text(stringResource(R.string.rf_field_country_iso), fontSize = 10.5.sp) }
                                            )
                                            OutlinedTextField(
                                                value = draft.carrierAlpha,
                                                onValueChange = { v -> draft = draft.copy(carrierAlpha = v.take(15)) },
                                                modifier = Modifier.weight(1.3f),
                                                singleLine = true,
                                                label = { Text(stringResource(R.string.rf_field_carrier_alpha), fontSize = 10.5.sp) }
                                            )
                                        }
                                    }
                                }
                            }

                            // Optional OpenCelliD Real Tower Lookup (explicit tap only!)
                            NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 14.dp, elevation = 3.dp) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.rf_opencellid_section),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = NeuColors.InkPrimary
                                    )
                                    Text(
                                        text = stringResource(R.string.rf_opencellid_hint),
                                        fontSize = 10.sp,
                                        lineHeight = 13.5.sp,
                                        color = NeuColors.InkSecondary
                                    )
                                    OutlinedTextField(
                                        value = draft.openCellIdToken,
                                        onValueChange = { draft = draft.copy(openCellIdToken = it) },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text(stringResource(R.string.rf_opencellid_token_label), fontSize = 11.sp) }
                                    )
                                    NeuButton(
                                        onClick = {
                                            val tk = draft.openCellIdToken.trim()
                                            if (tk.isEmpty()) {
                                                onNotify(context.getString(R.string.rf_opencellid_need_token))
                                                return@NeuButton
                                            }
                                            scope.launch {
                                                fetchingOpenCellId = true
                                                val res = RfLiveImporter.fetchOpenCellIdTowers(
                                                    apiKey = tk,
                                                    latWgs84 = mapCenterWgs84.lat,
                                                    lonWgs84 = mapCenterWgs84.lon
                                                )
                                                fetchingOpenCellId = false
                                                res.onSuccess { towers ->
                                                    if (towers.isEmpty()) {
                                                        onNotify(context.getString(R.string.rf_opencellid_empty))
                                                    } else {
                                                        val merged = (towers + draft.pinnedCells)
                                                            .distinctBy { "${it.rat}:${it.mcc}:${it.mnc}:${it.ci}" }
                                                            .take(KurokoProtocol.MAX_PINNED_CELLS)
                                                        draft = draft.copy(pinnedCells = merged)
                                                        activeTab = RfTab.CELL
                                                        onNotify(context.getString(R.string.rf_opencellid_ok_fmt, towers.size))
                                                    }
                                                }.onFailure { err ->
                                                    onNotify(
                                                        context.getString(
                                                            R.string.rf_opencellid_err_fmt,
                                                            err.message ?: err.javaClass.simpleName
                                                        )
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        enabled = !fetchingOpenCellId,
                                        tone = NeuTone.NEUTRAL,
                                        cornerRadius = 11.dp,
                                        contentPadding = PaddingValues(vertical = 9.dp)
                                    ) {
                                        if (fetchingOpenCellId) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = NeuColors.CobaltDeep
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.rf_opencellid_fetching),
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        } else {
                                            Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(15.dp), tint = NeuColors.CobaltDeep)
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                text = stringResource(R.string.rf_opencellid_btn_fetch),
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = NeuColors.CobaltDeep
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        RfTab.CELL -> {
                            RfTabToolbar(
                                scanning = scanning,
                                canClear = draft.pinnedCells.isNotEmpty(),
                                onAdd = {
                                    if (draft.pinnedCells.size >= KurokoProtocol.MAX_PINNED_CELLS) {
                                        onNotify(context.getString(R.string.rf_limit_reached_fmt, KurokoProtocol.MAX_PINNED_CELLS))
                                    } else {
                                        editingCellIsNew = true
                                        editingCell = PinnedCell(
                                            pinLat = mapCenterWgs84.lat,
                                            pinLon = mapCenterWgs84.lon
                                        )
                                    }
                                },
                                onImportLive = {
                                    pendingScan = PendingScanKind.CELL
                                    fineLocLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                },
                                onClear = { draft = draft.copy(pinnedCells = emptyList()) }
                            )

                            if (draft.pinnedCells.isEmpty()) {
                                EmptyRfCard(text = stringResource(R.string.rf_empty_cells))
                            } else {
                                draft.pinnedCells.forEach { cell ->
                                    PinnedItemCard(
                                        title = "${if (cell.rat == 2) "5G NR" else "4G LTE"} · ${cell.mcc}-${RfFormatting.formatMnc(cell.mnc, cell.mncDigits)} · B${cell.band}",
                                        subtitle = "TAC:${cell.tac} CI:${cell.ci} PCI:${cell.pci} ARFCN:${cell.arfcn} ${cell.rsrpDbm}dBm",
                                        badgePrimary = stringResource(if (cell.followVirtual) R.string.rf_pin_badge_follow else R.string.rf_pin_badge_fixed),
                                        badgePrimaryHighlight = !cell.followVirtual,
                                        badgeSecondary = null,
                                        onEdit = {
                                            editingCellIsNew = false
                                            editingCell = cell
                                        },
                                        onDelete = {
                                            draft = draft.copy(pinnedCells = draft.pinnedCells.filterNot { it.id == cell.id })
                                        }
                                    )
                                }
                            }
                        }

                        RfTab.WIFI -> {
                            RfTabToolbar(
                                scanning = scanning,
                                canClear = draft.pinnedWifis.isNotEmpty(),
                                onAdd = {
                                    if (draft.pinnedWifis.size >= KurokoProtocol.MAX_PINNED_WIFIS) {
                                        onNotify(context.getString(R.string.rf_limit_reached_fmt, KurokoProtocol.MAX_PINNED_WIFIS))
                                    } else {
                                        editingWifiIsNew = true
                                        editingWifi = PinnedWifiAp(
                                            pinLat = mapCenterWgs84.lat,
                                            pinLon = mapCenterWgs84.lon
                                        )
                                    }
                                },
                                onImportLive = {
                                    pendingScan = PendingScanKind.WIFI
                                    fineLocLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                },
                                onClear = { draft = draft.copy(pinnedWifis = emptyList()) }
                            )

                            if (draft.pinnedWifis.isEmpty()) {
                                EmptyRfCard(text = stringResource(R.string.rf_empty_wifis))
                            } else {
                                draft.pinnedWifis.forEach { ap ->
                                    PinnedItemCard(
                                        title = ap.ssid,
                                        subtitle = "${ap.bssid} · ${ap.frequencyMhz}MHz · ${ap.rssiDbm}dBm",
                                        badgePrimary = stringResource(if (ap.followVirtual) R.string.rf_pin_badge_follow else R.string.rf_pin_badge_fixed),
                                        badgePrimaryHighlight = !ap.followVirtual,
                                        badgeSecondary = if (ap.isConnectedAp) stringResource(R.string.rf_badge_connected_ap) else null,
                                        onEdit = {
                                            editingWifiIsNew = false
                                            editingWifi = ap
                                        },
                                        onDelete = {
                                            draft = draft.copy(pinnedWifis = draft.pinnedWifis.filterNot { it.id == ap.id })
                                        }
                                    )
                                }
                            }
                        }

                        RfTab.BLE -> {
                            RfTabToolbar(
                                scanning = scanning,
                                canClear = draft.pinnedBeacons.isNotEmpty(),
                                onAdd = {
                                    if (draft.pinnedBeacons.size >= KurokoProtocol.MAX_PINNED_BEACONS) {
                                        onNotify(context.getString(R.string.rf_limit_reached_fmt, KurokoProtocol.MAX_PINNED_BEACONS))
                                    } else {
                                        editingBleIsNew = true
                                        editingBle = PinnedBleBeacon(
                                            pinLat = mapCenterWgs84.lat,
                                            pinLon = mapCenterWgs84.lon
                                        )
                                    }
                                },
                                onImportLive = {
                                    pendingScan = PendingScanKind.BLE
                                    btPermsLauncher.launch(
                                        arrayOf(
                                            Manifest.permission.BLUETOOTH_SCAN,
                                            Manifest.permission.BLUETOOTH_CONNECT,
                                            Manifest.permission.ACCESS_FINE_LOCATION
                                        )
                                    )
                                },
                                onClear = { draft = draft.copy(pinnedBeacons = emptyList()) }
                            )

                            if (draft.pinnedBeacons.isEmpty()) {
                                EmptyRfCard(text = stringResource(R.string.rf_empty_bles))
                            } else {
                                draft.pinnedBeacons.forEach { beacon ->
                                    val isIBeacon = beacon.deviceType == 1
                                    val detail = if (isIBeacon) {
                                        "${beacon.mac} · Maj:${beacon.major} Min:${beacon.minor} Tx:${beacon.txPowerDbm}\n${RfFormatting.formatUuidDashed(beacon.uuidHex32)}"
                                    } else {
                                        "${beacon.mac} · Tx:${beacon.txPowerDbm}dBm"
                                    }
                                    PinnedItemCard(
                                        title = beacon.name.ifBlank { beacon.mac },
                                        subtitle = detail,
                                        badgePrimary = stringResource(if (beacon.followVirtual) R.string.rf_pin_badge_follow else R.string.rf_pin_badge_fixed),
                                        badgePrimaryHighlight = !beacon.followVirtual,
                                        badgeSecondary = stringResource(
                                            if (isIBeacon) R.string.rf_ble_type_ibeacon else R.string.rf_ble_type_generic
                                        ),
                                        onEdit = {
                                            editingBleIsNew = false
                                            editingBle = beacon
                                        },
                                        onDelete = {
                                            draft = draft.copy(pinnedBeacons = draft.pinnedBeacons.filterNot { it.id == beacon.id })
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = NeuColors.InsetDark.copy(alpha = 0.45f))

                // Bottom Actions: Export JSON / Import JSON / Save & Sync
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    NeuButton(
                        onClick = {
                            val json = rfStore.exportConfigJson(draft)
                            val ok = runCatching {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                cm?.setPrimaryClip(
                                    ClipData.newPlainText(
                                        context.getString(R.string.rf_clipboard_label),
                                        json
                                    )
                                )
                                cm != null
                            }.getOrDefault(false)
                            if (ok) {
                                onNotify(context.getString(R.string.rf_json_copied))
                            }
                        },
                        modifier = Modifier.weight(0.95f),
                        tone = NeuTone.NEUTRAL,
                        cornerRadius = 11.dp,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 9.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.rf_btn_export_json),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    NeuButton(
                        onClick = { showImportJsonDialog = true },
                        modifier = Modifier.weight(0.95f),
                        tone = NeuTone.NEUTRAL,
                        cornerRadius = 11.dp,
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 9.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.rf_btn_import_json),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    NeuButton(
                        onClick = {
                            onSaveAndSync(draft)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1.2f),
                        tone = NeuTone.COBALT,
                        cornerRadius = 11.dp,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 9.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.rf_btn_apply_sync),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }

    editingCell?.let { cell ->
        EditCellDialog(
            initial = cell,
            mapCenterWgs84 = mapCenterWgs84,
            onDismiss = { editingCell = null },
            onSave = { updated ->
                val list = if (editingCellIsNew) {
                    (draft.pinnedCells + updated).take(KurokoProtocol.MAX_PINNED_CELLS)
                } else {
                    draft.pinnedCells.map { if (it.id == updated.id) updated else it }
                }
                draft = draft.copy(pinnedCells = list)
                editingCell = null
            }
        )
    }

    editingWifi?.let { ap ->
        EditWifiDialog(
            initial = ap,
            mapCenterWgs84 = mapCenterWgs84,
            onDismiss = { editingWifi = null },
            onSave = { updated ->
                val baseList = if (updated.isConnectedAp) {
                    draft.pinnedWifis.map { it.copy(isConnectedAp = false) }
                } else {
                    draft.pinnedWifis
                }
                val list = if (editingWifiIsNew) {
                    (baseList + updated).take(KurokoProtocol.MAX_PINNED_WIFIS)
                } else {
                    baseList.map { if (it.id == updated.id) updated else it }
                }
                draft = draft.copy(pinnedWifis = list)
                editingWifi = null
            }
        )
    }

    editingBle?.let { beacon ->
        EditBleDialog(
            initial = beacon,
            mapCenterWgs84 = mapCenterWgs84,
            onDismiss = { editingBle = null },
            onSave = { updated ->
                val list = if (editingBleIsNew) {
                    (draft.pinnedBeacons + updated).take(KurokoProtocol.MAX_PINNED_BEACONS)
                } else {
                    draft.pinnedBeacons.map { if (it.id == updated.id) updated else it }
                }
                draft = draft.copy(pinnedBeacons = list)
                editingBle = null
            }
        )
    }

    if (showImportJsonDialog) {
        var jsonInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportJsonDialog = false },
            containerColor = NeuColors.Surface,
            title = {
                Text(stringResource(R.string.rf_json_import_title), fontWeight = FontWeight.Bold, color = NeuColors.InkPrimary)
            },
            text = {
                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = { jsonInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    placeholder = { Text(stringResource(R.string.rf_json_import_hint), fontSize = 11.5.sp) }
                )
            },
            confirmButton = {
                NeuButton(
                    onClick = {
                        rfStore.importConfigJson(jsonInput, preserveToken = draft.openCellIdToken)
                            .onSuccess { imported ->
                                draft = imported
                                showImportJsonDialog = false
                                onSaveAndSync(imported)
                                onNotify(context.getString(R.string.rf_json_import_ok))
                            }
                            .onFailure { err ->
                                onNotify(
                                    context.getString(
                                        R.string.rf_json_import_err_fmt,
                                        err.message ?: err.javaClass.simpleName
                                    )
                                )
                            }
                    },
                    tone = NeuTone.COBALT,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.rf_btn_import_json), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportJsonDialog = false }) {
                    Text(stringResource(R.string.common_cancel), color = NeuColors.InkSecondary)
                }
            }
        )
    }
}

@Composable
private fun RfToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    activeColor: Color,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = NeuColors.InkPrimary)
            Text(text = subtitle, fontSize = 10.sp, lineHeight = 13.5.sp, color = NeuColors.InkSecondary)
        }
        NeuPillSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            activeColor = activeColor
        )
    }
}

@Composable
private fun RfTabToolbar(
    scanning: Boolean,
    canClear: Boolean,
    onAdd: () -> Unit,
    onImportLive: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        NeuButton(
            onClick = onAdd,
            tone = NeuTone.COBALT,
            cornerRadius = 10.dp,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(3.dp))
            Text(stringResource(R.string.rf_btn_add), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        NeuButton(
            onClick = onImportLive,
            modifier = Modifier.weight(1f),
            enabled = !scanning,
            tone = NeuTone.NEUTRAL,
            cornerRadius = 10.dp,
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
        ) {
            if (scanning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(13.dp),
                    strokeWidth = 2.dp,
                    color = NeuColors.CobaltDeep
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.rf_btn_scanning),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else {
                Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(14.dp), tint = NeuColors.CobaltDeep)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.rf_btn_import_live),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NeuColors.CobaltDeep,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (canClear) {
            NeuSquareIconButton(
                onClick = onClear,
                size = 34.dp,
                cornerRadius = 10.dp,
                contentColor = NeuColors.CoralDeep
            ) {
                Icon(
                    Icons.Default.DeleteSweep,
                    contentDescription = stringResource(R.string.rf_btn_clear_tab),
                    modifier = Modifier.size(17.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyRfCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .neuInset(cornerRadius = 12.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
            .padding(horizontal = 12.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 11.sp,
            lineHeight = 15.5.sp,
            color = NeuColors.InkSecondary
        )
    }
}

@Composable
private fun PinnedItemCard(
    title: String,
    subtitle: String,
    badgePrimary: String,
    badgePrimaryHighlight: Boolean,
    badgeSecondary: String?,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 12.dp, elevation = 3.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.InkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    SmallTagBadge(
                        text = badgePrimary,
                        bg = if (badgePrimaryHighlight) NeuColors.CobaltSoft else NeuColors.InsetBg,
                        fg = if (badgePrimaryHighlight) NeuColors.CobaltDeep else NeuColors.InkSecondary
                    )
                    if (!badgeSecondary.isNullOrBlank()) {
                        SmallTagBadge(
                            text = badgeSecondary,
                            bg = NeuColors.EmeraldSoft,
                            fg = NeuColors.EmeraldDeep
                        )
                    }
                }
                Text(
                    text = subtitle,
                    fontSize = 10.sp,
                    lineHeight = 13.5.sp,
                    fontFamily = FontFamily.Monospace,
                    color = NeuColors.InkSecondary
                )
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Edit,
                    contentDescription = stringResource(R.string.common_edit),
                    tint = NeuColors.CobaltDeep,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint = NeuColors.CoralDeep,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun SmallTagBadge(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(bg)
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(
            text = text,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = fg,
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
private fun EditCellDialog(
    initial: PinnedCell,
    mapCenterWgs84: GeoPoint,
    onDismiss: () -> Unit,
    onSave: (PinnedCell) -> Unit
) {
    var rat by remember(initial) { mutableStateOf(initial.rat) }
    var mcc by remember(initial) { mutableStateOf(initial.mcc.toString()) }
    var mnc by remember(initial) { mutableStateOf(initial.mnc.toString()) }
    var mncDigits by remember(initial) { mutableStateOf(initial.mncDigits.toString()) }
    var tac by remember(initial) { mutableStateOf(initial.tac.toString()) }
    var ci by remember(initial) { mutableStateOf(initial.ci.toString()) }
    var pci by remember(initial) { mutableStateOf(initial.pci.toString()) }
    var arfcn by remember(initial) { mutableStateOf(initial.arfcn.toString()) }
    var band by remember(initial) { mutableStateOf(initial.band.toString()) }
    var rsrp by remember(initial) { mutableStateOf(initial.rsrpDbm.toString()) }
    var followVirtual by remember(initial) { mutableStateOf(initial.followVirtual) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeuColors.Surface,
        title = { Text(stringResource(R.string.rf_edit_cell_title), fontWeight = FontWeight.Bold, color = NeuColors.InkPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NeuSegmentedControl(
                    options = listOf(stringResource(R.string.rf_cell_rat_lte), stringResource(R.string.rf_cell_rat_nr)),
                    selectedIndex = if (rat == 2) 1 else 0,
                    onSelect = { rat = if (it == 1) 2 else 1 },
                    height = 32.dp,
                    fontSize = 11.sp
                )
                NeuSegmentedControl(
                    options = listOf(stringResource(R.string.rf_pin_mode_follow), stringResource(R.string.rf_pin_mode_map)),
                    selectedIndex = if (followVirtual) 0 else 1,
                    onSelect = { followVirtual = (it == 0) },
                    height = 32.dp,
                    fontSize = 10.5.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = mcc,
                        onValueChange = { mcc = it.filter { ch -> ch.isDigit() }.take(3) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.rf_field_mcc), fontSize = 10.5.sp) }
                    )
                    OutlinedTextField(
                        value = mnc,
                        onValueChange = { mnc = it.filter { ch -> ch.isDigit() }.take(3) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.rf_field_mnc), fontSize = 10.5.sp) }
                    )
                    OutlinedTextField(
                        value = mncDigits,
                        onValueChange = { mncDigits = it.filter { ch -> ch.isDigit() }.take(1) },
                        modifier = Modifier.weight(0.8f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.rf_field_mnc_digits), fontSize = 9.5.sp) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = tac,
                        onValueChange = { tac = it.filter { ch -> ch.isDigit() }.take(8) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.rf_field_tac), fontSize = 10.5.sp) }
                    )
                    OutlinedTextField(
                        value = ci,
                        onValueChange = { ci = it.filter { ch -> ch.isDigit() }.take(12) },
                        modifier = Modifier.weight(1.3f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.rf_field_ci), fontSize = 10.5.sp) }
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = pci,
                        onValueChange = { pci = it.filter { ch -> ch.isDigit() }.take(4) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.rf_field_pci), fontSize = 10.5.sp) }
                    )
                    OutlinedTextField(
                        value = band,
                        onValueChange = { band = it.filter { ch -> ch.isDigit() }.take(3) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.rf_field_band), fontSize = 10.5.sp) }
                    )
                    OutlinedTextField(
                        value = rsrp,
                        onValueChange = { rsrp = it.filter { ch -> ch.isDigit() || ch == '-' }.take(4) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.rf_field_rsrp), fontSize = 10.sp) }
                    )
                }
                OutlinedTextField(
                    value = arfcn,
                    onValueChange = { arfcn = it.filter { ch -> ch.isDigit() }.take(8) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.rf_field_arfcn), fontSize = 10.5.sp) }
                )
            }
        },
        confirmButton = {
            NeuButton(
                onClick = {
                    onSave(
                        initial.copy(
                            rat = if (rat == 2) 2 else 1,
                            mcc = (mcc.toIntOrNull() ?: initial.mcc).coerceIn(1, 999),
                            mnc = (mnc.toIntOrNull() ?: initial.mnc).coerceIn(0, 999),
                            mncDigits = if (mncDigits.toIntOrNull() == 3) 3 else 2,
                            tac = (tac.toLongOrNull() ?: initial.tac).coerceIn(0L, 16_777_215L),
                            ci = (ci.toLongOrNull() ?: initial.ci).coerceAtLeast(0L),
                            pci = (pci.toIntOrNull() ?: initial.pci).coerceIn(0, 1007),
                            arfcn = (arfcn.toIntOrNull() ?: initial.arfcn).coerceAtLeast(0),
                            band = (band.toIntOrNull() ?: initial.band).coerceIn(0, 512),
                            rsrpDbm = (rsrp.toIntOrNull() ?: initial.rsrpDbm).coerceIn(-140, -44),
                            followVirtual = followVirtual,
                            pinLat = if (followVirtual) 0.0 else mapCenterWgs84.lat,
                            pinLon = if (followVirtual) 0.0 else mapCenterWgs84.lon
                        )
                    )
                },
                tone = NeuTone.COBALT,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
private fun EditWifiDialog(
    initial: PinnedWifiAp,
    mapCenterWgs84: GeoPoint,
    onDismiss: () -> Unit,
    onSave: (PinnedWifiAp) -> Unit
) {
    var ssid by remember(initial) { mutableStateOf(initial.ssid) }
    var bssid by remember(initial) { mutableStateOf(initial.bssid) }
    var freq by remember(initial) { mutableStateOf(initial.frequencyMhz.toString()) }
    var rssi by remember(initial) { mutableStateOf(initial.rssiDbm.toString()) }
    var isConnected by remember(initial) { mutableStateOf(initial.isConnectedAp) }
    var followVirtual by remember(initial) { mutableStateOf(initial.followVirtual) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeuColors.Surface,
        title = { Text(stringResource(R.string.rf_edit_wifi_title), fontWeight = FontWeight.Bold, color = NeuColors.InkPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NeuSegmentedControl(
                    options = listOf(stringResource(R.string.rf_pin_mode_follow), stringResource(R.string.rf_pin_mode_map)),
                    selectedIndex = if (followVirtual) 0 else 1,
                    onSelect = { followVirtual = (it == 0) },
                    height = 32.dp,
                    fontSize = 10.5.sp
                )
                OutlinedTextField(
                    value = ssid,
                    onValueChange = { ssid = it.take(32) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.rf_field_ssid), fontSize = 11.sp) }
                )
                OutlinedTextField(
                    value = bssid,
                    onValueChange = { bssid = it.take(17) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.rf_field_bssid), fontSize = 11.sp) }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = freq,
                        onValueChange = { freq = it.filter { ch -> ch.isDigit() }.take(4) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.rf_field_freq_mhz), fontSize = 10.5.sp) }
                    )
                    OutlinedTextField(
                        value = rssi,
                        onValueChange = { rssi = it.filter { ch -> ch.isDigit() || ch == '-' }.take(4) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.rf_field_rssi), fontSize = 10.5.sp) }
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.rf_field_wifi_connected),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = NeuColors.InkPrimary,
                        modifier = Modifier.weight(1f)
                    )
                    NeuPillSwitch(
                        checked = isConnected,
                        onCheckedChange = { isConnected = it },
                        activeColor = NeuColors.LedGreen
                    )
                }
            }
        },
        confirmButton = {
            NeuButton(
                onClick = {
                    onSave(
                        initial.copy(
                            ssid = ssid.trim().take(32).ifEmpty { "WiFi-AP" },
                            bssid = RfFormatting.normalizeMac(bssid),
                            frequencyMhz = (freq.toIntOrNull() ?: initial.frequencyMhz).coerceIn(2412, 7125),
                            rssiDbm = (rssi.toIntOrNull() ?: initial.rssiDbm).coerceIn(-100, -25),
                            isConnectedAp = isConnected,
                            followVirtual = followVirtual,
                            pinLat = if (followVirtual) 0.0 else mapCenterWgs84.lat,
                            pinLon = if (followVirtual) 0.0 else mapCenterWgs84.lon
                        )
                    )
                },
                tone = NeuTone.COBALT,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
private fun EditBleDialog(
    initial: PinnedBleBeacon,
    mapCenterWgs84: GeoPoint,
    onDismiss: () -> Unit,
    onSave: (PinnedBleBeacon) -> Unit
) {
    var deviceType by remember(initial) { mutableStateOf(initial.deviceType) }
    var name by remember(initial) { mutableStateOf(initial.name) }
    var mac by remember(initial) { mutableStateOf(initial.mac) }
    var uuid by remember(initial) { mutableStateOf(initial.uuidHex32) }
    var major by remember(initial) { mutableStateOf(initial.major.toString()) }
    var minor by remember(initial) { mutableStateOf(initial.minor.toString()) }
    var txPwr by remember(initial) { mutableStateOf(initial.txPowerDbm.toString()) }
    var followVirtual by remember(initial) { mutableStateOf(initial.followVirtual) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeuColors.Surface,
        title = { Text(stringResource(R.string.rf_edit_ble_title), fontWeight = FontWeight.Bold, color = NeuColors.InkPrimary) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NeuSegmentedControl(
                    options = listOf(stringResource(R.string.rf_ble_type_ibeacon), stringResource(R.string.rf_ble_type_generic)),
                    selectedIndex = if (deviceType == 1) 0 else 1,
                    onSelect = { deviceType = if (it == 0) 1 else 0 },
                    height = 32.dp,
                    fontSize = 11.sp
                )
                NeuSegmentedControl(
                    options = listOf(stringResource(R.string.rf_pin_mode_follow), stringResource(R.string.rf_pin_mode_map)),
                    selectedIndex = if (followVirtual) 0 else 1,
                    onSelect = { followVirtual = (it == 0) },
                    height = 32.dp,
                    fontSize = 10.5.sp
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(23) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.rf_field_ble_name), fontSize = 11.sp) }
                )
                OutlinedTextField(
                    value = mac,
                    onValueChange = { mac = it.take(17) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.rf_field_ble_mac), fontSize = 11.sp) }
                )
                if (deviceType == 1) {
                    OutlinedTextField(
                        value = uuid,
                        onValueChange = { uuid = it.take(36) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.rf_field_ble_uuid), fontSize = 10.5.sp) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = major,
                            onValueChange = { major = it.filter { ch -> ch.isDigit() }.take(5) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text(stringResource(R.string.rf_field_ble_major), fontSize = 10.sp) }
                        )
                        OutlinedTextField(
                            value = minor,
                            onValueChange = { minor = it.filter { ch -> ch.isDigit() }.take(5) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            label = { Text(stringResource(R.string.rf_field_ble_minor), fontSize = 10.sp) }
                        )
                    }
                }
                OutlinedTextField(
                    value = txPwr,
                    onValueChange = { txPwr = it.filter { ch -> ch.isDigit() || ch == '-' }.take(4) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.rf_field_ble_tx_pwr), fontSize = 10.5.sp) }
                )
            }
        },
        confirmButton = {
            NeuButton(
                onClick = {
                    onSave(
                        initial.copy(
                            deviceType = if (deviceType == 1) 1 else 0,
                            name = name.trim().take(23),
                            mac = RfFormatting.normalizeMac(mac),
                            uuidHex32 = RfFormatting.normalizeUuidHex32(uuid),
                            major = (major.toIntOrNull() ?: initial.major).coerceIn(0, 65535),
                            minor = (minor.toIntOrNull() ?: initial.minor).coerceIn(0, 65535),
                            txPowerDbm = (txPwr.toIntOrNull() ?: initial.txPowerDbm).coerceIn(-100, 10),
                            followVirtual = followVirtual,
                            pinLat = if (followVirtual) 0.0 else mapCenterWgs84.lat,
                            pinLon = if (followVirtual) 0.0 else mapCenterWgs84.lon
                        )
                    )
                },
                tone = NeuTone.COBALT,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(stringResource(R.string.common_save), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = NeuColors.InkSecondary)
            }
        }
    )
}
