package com.kuroko.teleport.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuroko.teleport.R
import com.kuroko.teleport.geo.GeoPoint
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Layer 2 Scenario Profile for Static Mode (point + static jitter config).
 * Note: Target Apps remain purely global/independent (no boundTargets per #47).
 */
data class SavedPoint(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val latWgs84: Double,
    val lonWgs84: Double,
    val altM: Double = 45.0,
    val jitterM: Float = 1.5f,
    val updatedAtMs: Long = System.currentTimeMillis()
)

class PointStore(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val prefs = appContext.getSharedPreferences("kuroko_store_v1", Context.MODE_PRIVATE)

    private fun localizedCtx(): Context = AppLocaleManager.localizedContext(appContext)

    fun loadPoints(): List<SavedPoint> {
        val raw = prefs.getString(KEY_POINTS, null) ?: return emptyList()
        return runCatching { parsePointsFromArray(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    fun savePoints(points: List<SavedPoint>) {
        prefs.edit().putString(KEY_POINTS, encodePointsJsonArray(points).toString()).apply()
    }

    fun exportPointsJson(points: List<SavedPoint>): String {
        return JSONObject().apply {
            put("type", "kuroko_points")
            put("version", 1)
            put("points", encodePointsJsonArray(points))
        }.toString(2)
    }

    fun importPointsJson(jsonText: String): Result<List<SavedPoint>> = runCatching {
        val uiCtx = localizedCtx()
        val trimmed = jsonText.trim()
        require(trimmed.isNotEmpty()) { uiCtx.getString(R.string.point_import_err_empty) }
        val arr = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val root = JSONObject(trimmed)
            root.optJSONArray("points")
                ?: throw IllegalArgumentException(uiCtx.getString(R.string.point_import_err_no_points))
        }
        val parsed = parsePointsFromArray(arr)
        require(parsed.isNotEmpty()) { uiCtx.getString(R.string.point_import_err_invalid) }
        parsed
    }

    private fun encodePointsJsonArray(points: List<SavedPoint>): JSONArray {
        val arr = JSONArray()
        for (p in points) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("lat", p.latWgs84)
                    put("lon", p.lonWgs84)
                    put("alt", p.altM)
                    put("jitterM", p.jitterM.toDouble())
                    put("updatedAt", p.updatedAtMs)
                }
            )
        }
        return arr
    }

    private fun parsePointsFromArray(arr: JSONArray): List<SavedPoint> {
        val fallbackName = localizedCtx().getString(R.string.point_unnamed)
        val list = mutableListOf<SavedPoint>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val lat = o.optDouble("lat", Double.NaN)
            val lon = o.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) continue
            val ts = when {
                o.has("updatedAt") -> o.optLong("updatedAt", System.currentTimeMillis())
                else -> o.optLong("createdAt", System.currentTimeMillis())
            }
            list.add(
                SavedPoint(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    name = o.optString("name", fallbackName).ifBlank { fallbackName },
                    latWgs84 = lat,
                    lonWgs84 = lon,
                    altM = o.optDouble("alt", 45.0),
                    jitterM = o.optDouble("jitterM", 1.5).toFloat().coerceIn(0f, 8f),
                    updatedAtMs = ts
                )
            )
        }
        return list.distinctBy { it.id }
    }

    companion object {
        private const val KEY_POINTS = "saved_points_json"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedPointsSheet(
    points: List<SavedPoint>,
    activeProfileId: String?,
    currentPointWgs84: GeoPoint,
    currentStaticJitterM: Float,
    onDismiss: () -> Unit,
    onSaveAsNew: (name: String, point: GeoPoint) -> Unit,
    onUpdateExisting: (SavedPoint, String, GeoPoint) -> Unit,
    onSelectPoint: (SavedPoint) -> Unit,
    onDeletePoint: (SavedPoint) -> Unit,
    onExportJson: () -> String,
    onImportJson: (String) -> Result<Int>,
    onNotify: (String) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val activePoint = remember(points, activeProfileId) {
        points.firstOrNull { it.id == activeProfileId }
    }
    var pointName by remember(activePoint?.id) { mutableStateOf(activePoint?.name ?: "") }
    var showImportDialog by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    val dateFmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NeuColors.Surface,
        contentColor = NeuColors.InkPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Place, contentDescription = null, tint = NeuColors.Coral)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.point_sheet_title, points.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.InkPrimary
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    NeuButton(
                        onClick = {
                            if (points.isEmpty()) {
                                onNotify(context.getString(R.string.point_export_empty))
                            } else {
                                val json = onExportJson()
                                val copied = runCatching {
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                    cm?.setPrimaryClip(
                                        ClipData.newPlainText(
                                            context.getString(R.string.point_clipboard_label),
                                            json
                                        )
                                    )
                                    cm != null
                                }.getOrDefault(false)
                                onNotify(
                                    if (copied) context.getString(R.string.point_export_copied, points.size)
                                    else context.getString(R.string.point_clipboard_write_failed)
                                )
                            }
                        },
                        tone = NeuTone.NEUTRAL,
                        cornerRadius = 10.dp,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.point_action_export),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                    NeuButton(
                        onClick = {
                            val clip = runCatching {
                                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val pc = cm?.primaryClip
                                if (pc != null && pc.itemCount > 0) {
                                    pc.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                                } else {
                                    ""
                                }
                            }.getOrDefault("")
                            importText = if (clip.trim().startsWith("{") || clip.trim().startsWith("[")) clip else ""
                            showImportDialog = true
                        },
                        tone = NeuTone.NEUTRAL,
                        cornerRadius = 10.dp,
                        contentPadding = PaddingValues(horizontal = 9.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(
                            text = stringResource(R.string.point_action_import),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1
                        )
                    }
                }
            }

            NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, elevation = 4.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .neuInset(cornerRadius = 10.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = stringResource(
                                R.string.point_current_coord_fmt,
                                String.format(Locale.US, "%.6f", currentPointWgs84.lat),
                                String.format(Locale.US, "%.6f", currentPointWgs84.lon),
                                String.format(Locale.US, "%.1f", currentStaticJitterM)
                            ),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = NeuColors.InkPrimary
                        )
                    }
                    OutlinedTextField(
                        value = pointName,
                        onValueChange = { pointName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.point_name_label)) },
                        placeholder = { Text(stringResource(R.string.point_name_placeholder)) }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (activePoint != null) {
                            NeuButton(
                                onClick = {
                                    val name = pointName.trim().ifEmpty { activePoint.name }
                                    onUpdateExisting(activePoint, name, currentPointWgs84)
                                },
                                modifier = Modifier.weight(1f),
                                tone = NeuTone.EMERALD,
                                contentPadding = PaddingValues(vertical = 10.dp)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.point_btn_update),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        NeuButton(
                            onClick = {
                                val name = pointName.trim().ifEmpty {
                                    context.getString(R.string.point_default_name_fmt, dateFmt.format(Date()))
                                }
                                onSaveAsNew(name, currentPointWgs84)
                                pointName = ""
                            },
                            modifier = Modifier.weight(1f),
                            tone = NeuTone.COBALT,
                            contentPadding = PaddingValues(vertical = 10.dp)
                        ) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                text = stringResource(
                                    if (activePoint != null) R.string.point_btn_save_as_new
                                    else R.string.point_btn_save_new
                                ),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (points.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuInset(cornerRadius = 14.dp, surfaceColor = NeuColors.InsetBg, depth = 3.dp)
                        .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(R.string.point_empty_list),
                        style = MaterialTheme.typography.bodySmall,
                        color = NeuColors.InkSecondary
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(points, key = { it.id }) { pt ->
                        val isActive = pt.id == activeProfileId
                        NeuCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 14.dp,
                            elevation = if (isActive) 5.dp else 3.5.dp,
                            surfaceColor = if (isActive) NeuColors.SurfaceHighlight else NeuColors.SurfaceMatte,
                            onClick = {
                                onSelectPoint(pt)
                                onDismiss()
                            }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = NeuColors.Coral,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isActive) {
                                            NeuLedDot(color = NeuColors.LedGreen, lit = true, size = 6.dp)
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(
                                            text = pt.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = NeuColors.InkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = String.format(
                                            Locale.US,
                                            "%.6f, %.6f · ±%.1fm · %s",
                                            pt.latWgs84,
                                            pt.lonWgs84,
                                            pt.jitterM,
                                            dateFmt.format(Date(pt.updatedAtMs))
                                        ),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = NeuColors.InkSecondary
                                    )
                                }
                                IconButton(onClick = { onDeletePoint(pt) }) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = stringResource(R.string.point_cd_delete),
                                        tint = NeuColors.Coral
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            containerColor = NeuColors.Surface,
            titleContentColor = NeuColors.InkPrimary,
            textContentColor = NeuColors.InkPrimary,
            title = { Text(stringResource(R.string.point_import_dialog_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.point_import_dialog_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = NeuColors.InkSecondary
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp),
                        label = { Text(stringResource(R.string.point_import_json_label)) }
                    )
                }
            },
            confirmButton = {
                NeuButton(
                    onClick = {
                        onImportJson(importText)
                            .onSuccess { count ->
                                showImportDialog = false
                                onNotify(context.getString(R.string.point_import_success, count))
                            }
                            .onFailure { err ->
                                onNotify(context.getString(R.string.point_import_failed, err.message.orEmpty()))
                            }
                    },
                    tone = NeuTone.COBALT,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(stringResource(R.string.point_action_import), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) {
                    Text(stringResource(R.string.point_action_cancel), color = NeuColors.InkSecondary)
                }
            }
        )
    }
}
