package com.kuroko.teleport.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuroko.teleport.R
import com.kuroko.teleport.ipc.KurokoProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledAppEntry(
    val packageName: String,
    val label: String,
    val uid: Int,
    val isSystem: Boolean,
    val icon: ImageBitmap?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TargetAppSheet(
    initialTargets: List<SavedTargetApp>,
    onDismiss: () -> Unit,
    onApplyTargets: (List<SavedTargetApp>) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var loading by remember { mutableStateOf(true) }
    var apps by remember { mutableStateOf<List<InstalledAppEntry>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }

    val selected = remember {
        mutableStateMapOf<String, Pair<Int, Int>>().apply {
            for (t in initialTargets) {
                put(t.packageName, t.uid to t.policyFlags)
            }
        }
    }

    LaunchedEffect(Unit) {
        apps = loadInstalledApps(context)
        val byPkg = apps.associateBy { it.packageName }
        for (key in selected.keys.toList()) {
            val fresh = byPkg[key]
            if (fresh != null) {
                val oldPolicy = selected[key]?.second ?: KurokoProtocol.POLICY_ALL_STRICT
                selected[key] = fresh.uid to oldPolicy
            }
        }
        loading = false
    }

    val filtered = remember(apps, query, showSystemApps, selected.size) {
        val q = query.trim().lowercase()
        apps.filter { app ->
            val passSys = showSystemApps || !app.isSystem || selected.containsKey(app.packageName)
            val passQ = q.isEmpty() ||
                app.label.lowercase().contains(q) ||
                app.packageName.lowercase().contains(q) ||
                app.uid.toString().contains(q)
            passSys && passQ
        }.sortedWith(
            compareByDescending<InstalledAppEntry> { selected.containsKey(it.packageName) }
                .thenBy { it.label.lowercase() }
        )
    }

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
                .padding(bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Apps, contentDescription = null, tint = NeuColors.CobaltDeep)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            R.string.target_sheet_title,
                            selected.size,
                            KurokoProtocol.MAX_TARGETS
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.InkPrimary
                    )
                }
                NeuButton(
                    onClick = {
                        val result = selected.entries
                            .take(KurokoProtocol.MAX_TARGETS)
                            .map { (pkg, pair) ->
                                SavedTargetApp(
                                    packageName = pkg,
                                    uid = pair.first,
                                    policyFlags = pair.second
                                )
                            }
                        onApplyTargets(result)
                        onDismiss()
                    },
                    tone = NeuTone.COBALT,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.target_btn_apply),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .neuInset(cornerRadius = 12.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Shield,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = NeuColors.EmeraldDeep
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.target_zero_inject_banner),
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        color = NeuColors.InkSecondary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .neuInset(cornerRadius = 14.dp, surfaceColor = NeuColors.InsetBg, depth = 3.dp)
                ) {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = NeuColors.InkSecondary)
                        },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = stringResource(R.string.target_cd_clear),
                                        tint = NeuColors.InkSecondary
                                    )
                                }
                            }
                        },
                        placeholder = {
                            Text(
                                text = stringResource(R.string.target_search_placeholder),
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
                        )
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.target_toggle_system_apps),
                        fontSize = 10.sp,
                        color = NeuColors.InkSecondary,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(3.dp))
                    NeuPillSwitch(
                        checked = showSystemApps,
                        onCheckedChange = { showSystemApps = it },
                        activeColor = NeuColors.Cobalt
                    )
                }
            }

            if (loading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = NeuColors.CobaltDeep)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filtered, key = { it.packageName }) { app ->
                        val isChecked = selected.containsKey(app.packageName)
                        val curPolicy = selected[app.packageName]?.second ?: KurokoProtocol.POLICY_ALL_STRICT
                        val isStrict = (curPolicy and KurokoProtocol.POLICY_HIDE_CELL) != 0

                        NeuCard(
                            modifier = Modifier.fillMaxWidth(),
                            cornerRadius = 14.dp,
                            elevation = if (isChecked) 5.dp else 3.dp,
                            surfaceColor = if (isChecked) NeuColors.SurfaceHighlight else NeuColors.SurfaceMatte
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (app.icon != null) {
                                        Image(
                                            bitmap = app.icon,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(9.dp))
                                        )
                                    } else {
                                        Icon(
                                            Icons.Default.Apps,
                                            contentDescription = null,
                                            tint = NeuColors.InkSecondary,
                                            modifier = Modifier.size(36.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = app.label,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = NeuColors.InkPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = "${app.packageName} · UID ${app.uid}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = NeuColors.InkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Checkbox(
                                        checked = isChecked,
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = NeuColors.CobaltDeep,
                                            uncheckedColor = NeuColors.InkMuted
                                        ),
                                        onCheckedChange = { checked ->
                                            if (checked) {
                                                if (selected.size < KurokoProtocol.MAX_TARGETS) {
                                                    selected[app.packageName] =
                                                        app.uid to KurokoProtocol.POLICY_ALL_STRICT
                                                }
                                            } else {
                                                selected.remove(app.packageName)
                                            }
                                        }
                                    )
                                }

                                if (isChecked) {
                                    Spacer(Modifier.height(6.dp))
                                    NeuSegmentedControl(
                                        options = listOf(
                                            stringResource(R.string.target_policy_strict),
                                            stringResource(R.string.target_policy_local_outdoor)
                                        ),
                                        selectedIndex = if (isStrict) 0 else 1,
                                        onSelect = { idx ->
                                            selected[app.packageName] = app.uid to if (idx == 0) {
                                                KurokoProtocol.POLICY_ALL_STRICT
                                            } else {
                                                KurokoProtocol.POLICY_LOCAL_OUTDOOR
                                            }
                                        },
                                        height = 34.dp,
                                        fontSize = 11.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun loadInstalledApps(context: Context): List<InstalledAppEntry> =
    withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val selfPkg = context.packageName
        val excluded = setOf(selfPkg, "android", "com.android.phone", "com.android.systemui")
        val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        installed.mapNotNull { info ->
            if (info.packageName in excluded || info.uid < 10000) return@mapNotNull null
            val isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            val label = runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName)
            val iconBmp = runCatching {
                drawableToImageBitmap(pm.getApplicationIcon(info), 72)
            }.getOrNull()
            InstalledAppEntry(
                packageName = info.packageName,
                label = label,
                uid = info.uid,
                isSystem = isSystem,
                icon = iconBmp
            )
        }.sortedBy { it.label.lowercase() }
    }

private fun drawableToImageBitmap(drawable: Drawable, sizePx: Int): ImageBitmap {
    if (drawable is BitmapDrawable && drawable.bitmap != null) {
        val scaled = Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        return scaled.asImageBitmap()
    }
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    drawable.setBounds(0, 0, sizePx, sizePx)
    drawable.draw(canvas)
    return bmp.asImageBitmap()
}
