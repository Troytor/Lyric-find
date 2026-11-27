package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.AudioTelemetry
import com.example.model.SyncedTrack
import com.example.model.TimingSource
import com.example.ui.theme.AmbientPalette
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncEngineBottomSheet(
    track: SyncedTrack,
    telemetry: AudioTelemetry,
    syncOffsetMs: Long,
    isMicSyncEnabled: Boolean,
    engineNotice: String,
    customAudDToken: String,
    palette: AmbientPalette,
    onAdjustOffset: (Long) -> Unit,
    onResetOffset: () -> Unit,
    onSaveAudDToken: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var tokenInput by remember(customAudDToken) { mutableStateOf(customAudDToken) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xF20B1222),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        scrimColor = Color.Black.copy(alpha = 0.52f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = stringResource(R.string.engine_sheet_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
            )

            Spacer(modifier = Modifier.height(18.dp))

            // Real-time 16-band microphone spectrum visualizer
            LiquidGlassSurface(
                shape = RoundedCornerShape(22.dp),
                fillAlpha = 0.45f,
                highlightAlpha = 0.22f,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.engine_live_spectrum),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.62f),
                        )
                        Text(
                            text = if (isMicSyncEnabled) "ACTIVE · ${telemetry.beatCount} BEATS" else "PAUSED",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isMicSyncEnabled) palette.highlightTint else Color.White.copy(alpha = 0.45f),
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(68.dp),
                    ) {
                        val bands = telemetry.spectrum
                        val count = bands.size.coerceAtLeast(1)
                        val gap = 6.dp.toPx()
                        val barWidth = ((size.width - gap * (count - 1)) / count).coerceAtLeast(4f)

                        for (i in 0 until count) {
                            val value = if (isMicSyncEnabled) bands[i].coerceIn(0.06f, 1f) else 0.08f
                            val barHeight = size.height * value
                            val left = i * (barWidth + gap)
                            val top = size.height - barHeight
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        palette.highlightTint,
                                        palette.primaryGlow.copy(alpha = 0.85f),
                                    ),
                                    startY = top,
                                    endY = size.height,
                                ),
                                topLeft = Offset(left, top),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Acoustic metrics row (Live BPM, Track BPM, PLL lock)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                MetricGlassTile(
                    label = stringResource(R.string.engine_detected_bpm),
                    value = telemetry.detectedBpm?.roundToInt()?.toString() ?: "—",
                    accent = palette.highlightTint,
                    modifier = Modifier.weight(1f),
                )
                MetricGlassTile(
                    label = stringResource(R.string.engine_track_bpm),
                    value = track.metadata.bpm?.roundToInt()?.toString() ?: "—",
                    accent = Color.White,
                    modifier = Modifier.weight(1f),
                )
                MetricGlassTile(
                    label = stringResource(R.string.engine_tempo_lock),
                    value = String.format("%.2fx", telemetry.tempoLockRatio),
                    accent = palette.highlightTint,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Real-time DSP signal telemetry row
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                MetricGlassTile(
                    label = stringResource(R.string.engine_vocal_band),
                    value = if (isMicSyncEnabled) "${(telemetry.vocalEnergy * 100f).roundToInt()}%" else "—",
                    accent = palette.highlightTint,
                    modifier = Modifier.weight(1f),
                )
                MetricGlassTile(
                    label = stringResource(R.string.engine_bass_energy),
                    value = if (isMicSyncEnabled) "${(telemetry.bassEnergy * 100f).roundToInt()}%" else "—",
                    accent = Color.White,
                    modifier = Modifier.weight(1f),
                )
                val driftText = if (isMicSyncEnabled && telemetry.driftConfidence >= 0.35f) {
                    val sign = if (telemetry.estimatedDriftMs >= 0) "+" else ""
                    "${sign}${telemetry.estimatedDriftMs}ms"
                } else {
                    "Locked"
                }
                MetricGlassTile(
                    label = stringResource(R.string.engine_acoustic_drift),
                    value = if (isMicSyncEnabled) driftText else "—",
                    accent = palette.highlightTint,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Millisecond timing offset controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.engine_offset_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                )
                val formattedOffset = if (syncOffsetMs >= 0) "+${syncOffsetMs} ms" else "${syncOffsetMs} ms"
                Text(
                    text = formattedOffset,
                    style = MaterialTheme.typography.labelLarge,
                    color = palette.highlightTint,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OffsetStepButton("-250ms", { onAdjustOffset(-250L) }, Modifier.weight(1f).testTag("offset_minus_250"))
                OffsetStepButton("-50ms", { onAdjustOffset(-50L) }, Modifier.weight(1f).testTag("offset_minus_50"))
                OffsetStepButton(
                    stringResource(R.string.engine_offset_reset),
                    onResetOffset,
                    Modifier.weight(1f).testTag("offset_reset"),
                )
                OffsetStepButton("+50ms", { onAdjustOffset(50L) }, Modifier.weight(1f).testTag("offset_plus_50"))
                OffsetStepButton("+250ms", { onAdjustOffset(250L) }, Modifier.weight(1f).testTag("offset_plus_250"))
            }

            Spacer(modifier = Modifier.height(18.dp))

            // Word pacing & DSP source info
            val sourceLabel = when (track.timingSource) {
                TimingSource.ENHANCED_LRC -> stringResource(R.string.engine_source_enhanced)
                TimingSource.PROSODIC_BEAT_ENGINE -> stringResource(R.string.engine_source_prosodic)
                TimingSource.PLAIN_ESTIMATED -> stringResource(R.string.engine_source_plain)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(14.dp),
            ) {
                Column {
                    Text(
                        text = "${stringResource(R.string.engine_timing_source)} · ${track.metadata.sourceLabel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.highlightTint,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = sourceLabel,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = engineNotice.ifBlank { stringResource(R.string.engine_dsp_notice) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.62f),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Optional personal AudD API token for unlimited audio fingerprinting
            Text(
                text = stringResource(R.string.engine_audd_token_title),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White.copy(alpha = 0.84f),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it },
                    singleLine = true,
                    placeholder = {
                        Text(
                            text = stringResource(R.string.engine_audd_token_placeholder),
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.45f),
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = palette.highlightTint,
                        unfocusedBorderColor = Color.White.copy(alpha = 0.24f),
                        cursorColor = palette.highlightTint,
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("audd_token_input"),
                )
                Spacer(modifier = Modifier.width(10.dp))
                LiquidGlassPillButton(
                    onClick = { onSaveAudDToken(tokenInput) },
                    isActive = true,
                    accentColor = palette.highlightTint,
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("save_audd_token_button"),
                ) {
                    Text(
                        text = stringResource(R.string.engine_audd_token_save),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricGlassTile(
    label: String,
    value: String,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    LiquidGlassSurface(
        shape = RoundedCornerShape(18.dp),
        fillAlpha = 0.36f,
        highlightAlpha = 0.16f,
        modifier = modifier,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.56f),
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = accent,
            )
        }
    }
}

@Composable
private fun OffsetStepButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LiquidGlassPillButton(
        onClick = onClick,
        modifier = modifier.height(48.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}
