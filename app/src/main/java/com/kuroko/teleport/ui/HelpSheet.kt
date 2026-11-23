package com.kuroko.teleport.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kuroko.teleport.R
import com.kuroko.teleport.ipc.KurokoProtocol

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NeuColors.Surface,
        contentColor = NeuColors.InkPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = NeuColors.CobaltDeep,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        text = stringResource(R.string.help_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = NeuColors.InkPrimary
                    )
                    Text(
                        text = stringResource(R.string.help_subtitle),
                        fontSize = 11.sp,
                        color = NeuColors.InkSecondary
                    )
                }
            }

            HelpSectionCard(
                title = stringResource(R.string.help_sec1_title),
                initiallyExpanded = true
            ) {
                HelpBullet(
                    stringResource(R.string.help_sec1_b1_head),
                    stringResource(R.string.help_sec1_b1_body)
                )
                HelpBullet(
                    stringResource(R.string.help_sec1_b2_head),
                    stringResource(R.string.help_sec1_b2_body)
                )
                HelpBullet(
                    stringResource(R.string.help_sec1_b3_head),
                    stringResource(R.string.help_sec1_b3_body, 85)
                )
            }

            HelpSectionCard(
                title = stringResource(R.string.help_sec2_title),
                initiallyExpanded = true
            ) {
                HelpBullet(
                    stringResource(R.string.help_sec2_b1_head),
                    stringResource(R.string.help_sec2_b1_body)
                )
                HelpBullet(
                    stringResource(R.string.help_sec2_b2_head),
                    stringResource(R.string.help_sec2_b2_body)
                )
                HelpBullet(
                    stringResource(R.string.help_sec2_b3_head),
                    stringResource(R.string.help_sec2_b3_body)
                )
            }

            HelpSectionCard(
                title = stringResource(R.string.help_sec3_title),
                initiallyExpanded = true
            ) {
                HelpBullet(
                    stringResource(R.string.help_sec3_b1_head),
                    stringResource(R.string.help_sec3_b1_body)
                )
                HelpBullet(
                    stringResource(R.string.help_sec3_b2_head, 6, 10),
                    stringResource(R.string.help_sec3_b2_body, 8)
                )
                HelpBullet(
                    stringResource(R.string.help_sec3_b3_head),
                    stringResource(R.string.help_sec3_b3_body)
                )
                HelpBullet(
                    stringResource(R.string.help_sec3_b4_head),
                    stringResource(R.string.help_sec3_b4_body)
                )
            }

            HelpSectionCard(
                title = stringResource(R.string.help_sec4_title),
                initiallyExpanded = true
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .neuInset(cornerRadius = 12.dp, surfaceColor = NeuColors.LcdWellBg, depth = 2.5.dp)
                        .padding(10.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LedLegendRow(
                            NeuColors.LedGreen,
                            stringResource(R.string.help_led_engine_tag),
                            stringResource(R.string.help_led_engine_desc)
                        )
                        LedLegendRow(
                            NeuColors.LedGreen,
                            "L✓",
                            stringResource(R.string.help_led_loc_desc)
                        )
                        LedLegendRow(
                            NeuColors.LedGreen,
                            "P✓",
                            stringResource(R.string.help_led_phone_desc)
                        )
                        LedLegendRow(
                            NeuColors.LedGreen,
                            "S✓",
                            stringResource(R.string.help_led_sensor_desc, KurokoProtocol.APP_VERSION_NAME)
                        )
                    }
                }
            }

            HelpSectionCard(
                title = stringResource(R.string.help_sec5_title),
                initiallyExpanded = true
            ) {
                HelpBullet(
                    stringResource(R.string.help_sec5_b1_head),
                    stringResource(R.string.help_sec5_b1_body)
                )
                HelpBullet(
                    stringResource(R.string.help_sec5_b2_head),
                    stringResource(R.string.help_sec5_b2_body)
                )
            }

            HelpSectionCard(
                title = stringResource(R.string.help_sec6_title),
                initiallyExpanded = true
            ) {
                HelpBullet(
                    stringResource(R.string.help_sec6_b1_head),
                    stringResource(R.string.help_sec6_b1_body)
                )
                HelpBullet(
                    stringResource(R.string.help_sec6_b2_head),
                    stringResource(R.string.help_sec6_b2_body)
                )
                HelpBullet(
                    stringResource(R.string.help_sec6_b3_head),
                    stringResource(R.string.help_sec6_b3_body)
                )
                HelpBullet(
                    stringResource(R.string.help_sec6_b4_head),
                    stringResource(R.string.help_sec6_b4_body)
                )
            }
        }
    }
}

@Composable
private fun HelpSectionCard(
    title: String,
    initiallyExpanded: Boolean = true,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    NeuCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 16.dp, elevation = 4.dp) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = NeuColors.InkPrimary
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = NeuColors.InkSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun HelpBullet(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = "• $heading",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = NeuColors.CobaltDeep
        )
        Text(
            text = body,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            color = NeuColors.InkSecondary
        )
    }
}

@Composable
private fun LedLegendRow(color: androidx.compose.ui.graphics.Color, tag: String, desc: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        NeuLedDot(color = color, lit = true, size = 7.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = tag,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            color = NeuColors.InkPrimary,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = desc,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = NeuColors.InkSecondary,
            modifier = Modifier.weight(1f)
        )
    }
}
