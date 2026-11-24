package com.kuroko.teleport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuroko.teleport.R
import com.kuroko.teleport.ipc.KurokoProtocol
import com.kuroko.teleport.update.ReleaseUpdateInfo
import java.util.Locale

@Composable
fun UpdateDialog(
    info: ReleaseUpdateInfo,
    onUpdateNow: (url: String) -> Unit,
    onSkipVersion: (tagName: String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NeuColors.Surface,
        titleContentColor = NeuColors.InkPrimary,
        textContentColor = NeuColors.InkPrimary,
        icon = {
            Icon(
                imageVector = Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = NeuColors.CobaltDeep
            )
        },
        title = {
            Text(
                text = stringResource(R.string.update_dialog_title),
                fontWeight = FontWeight.Bold,
                color = NeuColors.InkPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Current -> Latest version comparison well
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuInset(cornerRadius = 11.dp, surfaceColor = NeuColors.LcdWellBg, depth = 3.dp)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = stringResource(
                                R.string.update_version_diff_fmt,
                                "v${KurokoProtocol.APP_VERSION_NAME}",
                                info.tagName
                            ),
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = NeuColors.CobaltDeep
                        )
                        if (!info.publishedDate.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.update_published_fmt, info.publishedDate),
                                fontSize = 10.5.sp,
                                color = NeuColors.InkSecondary
                            )
                        }
                    }
                }

                // All-in-One KSU flash tip
                Text(
                    text = stringResource(R.string.update_ksu_flash_tip),
                    style = MaterialTheme.typography.bodySmall,
                    lineHeight = 16.sp,
                    color = NeuColors.InkSecondary
                )

                // Optional release changelog preview
                if (info.changelog.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .neuInset(cornerRadius = 10.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.dp)
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = info.changelog,
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                            fontSize = 11.sp,
                            lineHeight = 15.5.sp,
                            color = NeuColors.InkPrimary
                        )
                    }
                }

                // Direct KSU module ZIP download shortcut when available
                if (!info.moduleZipUrl.isNullOrBlank()) {
                    val sizeSuffix = info.moduleZipSizeBytes?.let { bytes ->
                        val mb = bytes / (1024.0 * 1024.0)
                        String.format(Locale.US, " (%.1f MB)", mb)
                    }.orEmpty()
                    NeuButton(
                        onClick = { onUpdateNow(info.moduleZipUrl) },
                        modifier = Modifier.fillMaxWidth(),
                        tone = NeuTone.NEUTRAL,
                        cornerRadius = 10.dp,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.update_btn_download_zip_fmt, sizeSuffix),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                // Subtle skip-this-version option
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { onSkipVersion(info.tagName) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.update_btn_skip_version),
                            fontSize = 11.sp,
                            color = NeuColors.InkMuted
                        )
                    }
                }
            }
        },
        confirmButton = {
            NeuButton(
                onClick = { onUpdateNow(info.releaseUrl) },
                tone = NeuTone.COBALT,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 9.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp)
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.update_btn_go),
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.5.sp
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.common_cancel),
                    color = NeuColors.InkSecondary
                )
            }
        }
    )
}
