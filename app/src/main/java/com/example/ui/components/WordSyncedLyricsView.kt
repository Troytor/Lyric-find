package com.example.ui.components

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.LyricLine
import com.example.model.WordTiming
import com.example.ui.theme.PlusJakartaSansFamily
import com.example.viewmodel.PlaybackClockAnchor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * High-frame-rate word-by-word synced lyrics display with smooth horizontal gradient
 * word fill, vertical float micro-motion, progressive depth blur, and beat-synced interludes.
 */
@Composable
fun WordSyncedLyricsView(
    lines: List<LyricLine>,
    clockAnchor: PlaybackClockAnchor,
    syncOffsetMs: Long,
    beatIntensity: Float,
    highlightTint: Color,
    topContentPadding: PaddingValues,
    onLineTap: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Smooth choreographer-locked playback clock (60-120fps) without recomposing ViewModel
    var livePositionMs by remember { mutableLongStateOf(clockAnchor.currentPositionMs() + syncOffsetMs) }

    LaunchedEffect(clockAnchor, syncOffsetMs) {
        if (!clockAnchor.isRunning) {
            livePositionMs = (clockAnchor.basePositionMs + syncOffsetMs).coerceAtLeast(0L)
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos {
                val now = SystemClock.elapsedRealtimeNanos()
                livePositionMs = (clockAnchor.currentPositionMs(now) + syncOffsetMs).coerceAtLeast(0L)
            }
        }
    }

    // Track whether the user manually scrolled away from the live focal line
    var userScrolledAway by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userScrolledAway = true
        }
    }

    val activeLineIndex by remember(lines) {
        derivedStateOf { findActiveLineIndex(lines, livePositionMs) }
    }

    // Smooth auto-scroll keeping the active lyric positioned comfortably in the upper focal third
    LaunchedEffect(activeLineIndex, userScrolledAway) {
        if (!userScrolledAway && activeLineIndex >= 0 && activeLineIndex < lines.size) {
            listState.animateScrollToItem(
                index = activeLineIndex,
                scrollOffset = -170,
            )
        }
    }

    // Auto-recenter after 6 seconds if user stopped scrolling manually
    LaunchedEffect(userScrolledAway, listState.isScrollInProgress) {
        if (userScrolledAway && !listState.isScrollInProgress) {
            delay(6_000L)
            userScrolledAway = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (lines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.lyrics_no_lyrics_available),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.72f),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                contentPadding = topContentPadding,
                verticalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("lyrics_scroll_list"),
            ) {
                itemsIndexed(
                    items = lines,
                    key = { idx, line -> "${idx}_${line.startMs}" },
                ) { index, line ->
                    val distance = if (activeLineIndex >= 0) abs(index - activeLineIndex) else 2
                    val isActive = index == activeLineIndex
                    val isPast = activeLineIndex >= 0 && index < activeLineIndex

                    if (line.isInstrumental) {
                        InstrumentalInterludeRow(
                            line = line,
                            currentMs = livePositionMs,
                            isActive = isActive,
                            beatIntensity = beatIntensity,
                            highlightTint = highlightTint,
                            onTap = {
                                userScrolledAway = false
                                onLineTap(line.startMs)
                            },
                        )
                    } else {
                        WordSyncedLineItem(
                            line = line,
                            currentMs = livePositionMs,
                            isActive = isActive,
                            isPast = isPast,
                            distanceFromActive = distance,
                            highlightTint = highlightTint,
                            onTap = {
                                userScrolledAway = false
                                onLineTap(line.startMs)
                            },
                        )
                    }
                }
            }
        }

        // Floating liquid glass "Return to live" button when user scrolls away
        AnimatedVisibility(
            visible = userScrolledAway && activeLineIndex >= 0,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 128.dp),
        ) {
            LiquidGlassPillButton(
                onClick = {
                    userScrolledAway = false
                    scope.launch {
                        if (activeLineIndex in lines.indices) {
                            listState.animateScrollToItem(activeLineIndex, scrollOffset = -170)
                        }
                    }
                },
                isActive = true,
                accentColor = highlightTint,
                modifier = Modifier.testTag("return_to_live_button"),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MyLocation,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.lyrics_return_to_live),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WordSyncedLineItem(
    line: LyricLine,
    currentMs: Long,
    isActive: Boolean,
    isPast: Boolean,
    distanceFromActive: Int,
    highlightTint: Color,
    onTap: () -> Unit,
) {
    val lineScale by animateFloatAsState(
        targetValue = if (isActive) 1.0f else 0.92f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 260f),
        label = "lineScale",
    )
    val lineAlpha by animateFloatAsState(
        targetValue = when {
            isActive -> 1.0f
            isPast -> 0.28f
            distanceFromActive == 1 -> 0.48f
            else -> 0.30f
        },
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 220f),
        label = "lineAlpha",
    )
    val blurRadiusDp = when {
        isActive || Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> 0.dp
        distanceFromActive == 1 -> 1.2.dp
        distanceFromActive == 2 -> 2.4.dp
        else -> 3.6.dp
    }

    val interactionSource = remember { MutableInteractionSource() }
    val blurModifier = if (blurRadiusDp > 0.dp) Modifier.blur(blurRadiusDp) else Modifier

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = Color.White.copy(alpha = 0.3f)),
                onClick = onTap,
            )
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .graphicsLayer {
                scaleX = lineScale
                scaleY = lineScale
                alpha = lineAlpha
                transformOrigin = TransformOrigin(0f, 0.5f)
            }
            .then(blurModifier),
    ) {
        if (!isActive || line.words.isEmpty()) {
            Text(
                text = line.rawText,
                style = TextStyle(
                    fontFamily = PlusJakartaSansFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 30.sp,
                    lineHeight = 39.sp,
                    letterSpacing = (-0.5).sp,
                ),
                color = Color.White,
            )
        } else {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(9.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                line.words.forEach { word ->
                    WordKaraokeFillToken(
                        word = word,
                        currentMs = currentMs,
                        highlightTint = highlightTint,
                    )
                }
            }
        }
    }
}

@Composable
private fun WordKaraokeFillToken(
    word: WordTiming,
    currentMs: Long,
    highlightTint: Color,
) {
    val rawProgress = when {
        currentMs <= word.startMs -> 0f
        currentMs >= word.endMs -> 1f
        else -> (currentMs - word.startMs).toFloat() / word.durationMs.toFloat()
    }.coerceIn(0f, 1f)

    // Smooth ease-out curve for natural vocal fill motion
    val fillProgress = if (rawProgress in 0.001f..0.999f) {
        1f - ((1f - rawProgress) * (1f - rawProgress) * 0.35f + (1f - rawProgress) * 0.65f)
    } else {
        rawProgress
    }

    val isCurrentlySpoken = rawProgress in 0.01f..0.98f
    val floatLiftDp = when {
        isCurrentlySpoken -> -2.8f * kotlin.math.sin(rawProgress * Math.PI).toFloat()
        rawProgress >= 0.98f -> -0.6f
        else -> 0f
    }

    val wordBrush = when {
        fillProgress <= 0.005f -> Brush.horizontalGradient(
            listOf(Color.White.copy(alpha = 0.38f), Color.White.copy(alpha = 0.38f)),
        )
        fillProgress >= 0.995f -> Brush.horizontalGradient(
            listOf(Color.White, Color.White),
        )
        else -> {
            val lead = fillProgress.coerceIn(0.02f, 0.96f)
            val softEdge = (lead + 0.14f).coerceAtMost(1f)
            Brush.horizontalGradient(
                0f to Color.White,
                lead to Color.White,
                ((lead + softEdge) * 0.5f) to highlightTint.copy(alpha = 0.92f),
                softEdge to Color.White.copy(alpha = 0.38f),
                1f to Color.White.copy(alpha = 0.38f),
            )
        }
    }

    val glowShadow = if (isCurrentlySpoken) {
        Shadow(
            color = highlightTint.copy(alpha = 0.65f),
            offset = Offset(0f, 0f),
            blurRadius = 18f,
        )
    } else if (fillProgress >= 0.995f) {
        Shadow(
            color = Color.White.copy(alpha = 0.25f),
            offset = Offset(0f, 0f),
            blurRadius = 8f,
        )
    } else {
        null
    }

    Text(
        text = word.text,
        style = TextStyle(
            fontFamily = PlusJakartaSansFamily,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 31.sp,
            lineHeight = 40.sp,
            letterSpacing = (-0.5).sp,
            brush = wordBrush,
            shadow = glowShadow,
        ),
        modifier = Modifier.graphicsLayer {
            translationY = floatLiftDp.dp.toPx()
        },
    )
}

@Composable
private fun InstrumentalInterludeRow(
    line: LyricLine,
    currentMs: Long,
    isActive: Boolean,
    beatIntensity: Float,
    highlightTint: Color,
    onTap: () -> Unit,
) {
    val span = (line.endMs - line.startMs).coerceAtLeast(1L)
    val progress = ((currentMs - line.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    val alpha by animateFloatAsState(
        targetValue = if (isActive) 1f else 0.25f,
        animationSpec = spring(stiffness = 220f),
        label = "interludeAlpha",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 10.dp, vertical = 14.dp)
            .graphicsLayer { this.alpha = alpha },
    ) {
        Canvas(modifier = Modifier.width(64.dp).height(18.dp)) {
            val dotRadius = 5.dp.toPx()
            val spacing = 22.dp.toPx()
            val cy = size.height / 2f
            for (i in 0 until 3) {
                val threshold = i / 3f
                val dotFill = ((progress - threshold) * 3f).coerceIn(0f, 1f)
                val beatBump = if (isActive && dotFill in 0.05f..0.95f) beatIntensity * 2.5f else 0f
                val radius = dotRadius * (0.78f + dotFill * 0.34f) + beatBump
                val color = if (dotFill > 0.1f) {
                    highlightTint.copy(alpha = (0.45f + dotFill * 0.55f).coerceIn(0f, 1f))
                } else {
                    Color.White.copy(alpha = 0.26f)
                }
                drawCircle(
                    color = color,
                    radius = radius,
                    center = Offset(x = dotRadius + (i * spacing) + 4f, y = cy),
                )
            }
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.lyrics_instrumental_label),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White.copy(alpha = if (isActive) 0.76f else 0.35f),
        )
    }
}

private fun findActiveLineIndex(lines: List<LyricLine>, currentMs: Long): Int {
    if (lines.isEmpty()) return -1
    var active = 0
    for (i in lines.indices) {
        if (currentMs >= lines[i].startMs) {
            active = i
        } else {
            break
        }
    }
    return active
}
