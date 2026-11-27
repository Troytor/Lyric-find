package com.example.ui.components

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.LyricLine
import com.example.model.WordTiming
import com.example.ui.theme.InterFontFamily
import com.example.ui.theme.PlusJakartaSansFamily
import com.example.viewmodel.PlaybackClockAnchor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * High-frame-rate universal-script word-by-word synced lyrics display with
 * smooth hardware-clipped karaoke fill (supporting Latin, CJK, Korean, Thai,
 * Indic, Cyrillic, Greek, and RTL Arabic/Hebrew), synchronized dual-line
 * translations, active spotlight accent bar, and beat-synced interludes.
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
    isTranslationEnabled: Boolean = false,
    translatedLines: Map<Int, String> = emptyMap(),
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var livePositionMs by remember {
        mutableLongStateOf((clockAnchor.currentPositionMs() + syncOffsetMs).coerceAtLeast(0L))
    }

    LaunchedEffect(clockAnchor, syncOffsetMs) {
        livePositionMs = (clockAnchor.currentPositionMs() + syncOffsetMs).coerceAtLeast(0L)
        if (!clockAnchor.isRunning) {
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos {
                val now = SystemClock.elapsedRealtimeNanos()
                livePositionMs = (clockAnchor.currentPositionMs(now) + syncOffsetMs).coerceAtLeast(0L)
            }
        }
    }

    var userScrolledAway by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userScrolledAway = true
        }
    }

    val activeLineIndex by remember(lines) {
        derivedStateOf { findActiveLineIndex(lines, livePositionMs) }
    }

    LaunchedEffect(activeLineIndex, userScrolledAway) {
        if (!userScrolledAway && activeLineIndex >= 0 && activeLineIndex < lines.size) {
            listState.animateScrollToItem(
                index = activeLineIndex,
                scrollOffset = -165,
            )
        }
    }

    LaunchedEffect(userScrolledAway, listState.isScrollInProgress) {
        if (userScrolledAway && !listState.isScrollInProgress) {
            delay(5_500L)
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
                verticalArrangement = Arrangement.spacedBy(if (isTranslationEnabled) 20.dp else 18.dp),
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
                        val translatedText = if (isTranslationEnabled) translatedLines[line.index] else null
                        WordSyncedLineItem(
                            line = line,
                            translatedText = translatedText,
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

        // Floating liquid glass "Sync to live" pill when user scrolls manually
        AnimatedVisibility(
            visible = userScrolledAway && activeLineIndex >= 0,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 178.dp),
        ) {
            LiquidGlassPillButton(
                onClick = {
                    userScrolledAway = false
                    scope.launch {
                        if (activeLineIndex in lines.indices) {
                            listState.animateScrollToItem(activeLineIndex, scrollOffset = -165)
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
    translatedText: String?,
    currentMs: Long,
    isActive: Boolean,
    isPast: Boolean,
    distanceFromActive: Int,
    highlightTint: Color,
    onTap: () -> Unit,
) {
    val isRtl = remember(line.rawText) { isRtlText(line.rawText) }
    val lineDirection = if (isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr

    val lineScale by animateFloatAsState(
        targetValue = if (isActive) 1.0f else 0.94f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 260f),
        label = "lineScale",
    )
    val lineAlpha by animateFloatAsState(
        targetValue = when {
            isActive -> 1.0f
            isPast && distanceFromActive == 1 -> 0.42f
            isPast -> 0.28f
            distanceFromActive == 1 -> 0.64f
            distanceFromActive == 2 -> 0.42f
            else -> 0.28f
        },
        animationSpec = spring(dampingRatio = 0.85f, stiffness = 220f),
        label = "lineAlpha",
    )
    val blurRadiusDp = when {
        isActive || distanceFromActive <= 1 || Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> 0.dp
        distanceFromActive == 2 -> 1.1.dp
        else -> 2.4.dp
    }

    val interactionSource = remember { MutableInteractionSource() }
    val blurModifier = if (blurRadiusDp > 0.dp) Modifier.blur(blurRadiusDp) else Modifier
    val activeBg = if (isActive) Color.White.copy(alpha = 0.07f) else Color.Transparent

    CompositionLocalProvider(LocalLayoutDirection provides lineDirection) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(20.dp))
                .background(activeBg)
                .clickable(
                    interactionSource = interactionSource,
                    indication = ripple(color = Color.White.copy(alpha = 0.3f)),
                    onClick = onTap,
                )
                .padding(horizontal = 10.dp, vertical = 10.dp)
                .graphicsLayer {
                    scaleX = lineScale
                    scaleY = lineScale
                    alpha = lineAlpha
                    transformOrigin = TransformOrigin(if (isRtl) 1f else 0f, 0.5f)
                }
                .then(blurModifier),
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .fillMaxHeight()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(50))
                        .background(highlightTint),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                if (!isActive || line.words.isEmpty()) {
                    Text(
                        text = line.rawText,
                        style = TextStyle(
                            fontFamily = PlusJakartaSansFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 27.sp,
                            lineHeight = 36.sp,
                            letterSpacing = (-0.2).sp,
                        ),
                        color = Color.White,
                    )
                } else {
                    // Arrangement.Start + per-token trailing space preserves exact spacing for
                    // both space-delimited scripts (Latin, Korean, Arabic, Hindi, Russian) and
                    // unspaced scripts (Japanese, Chinese, Thai).
                    FlowRow(
                        horizontalArrangement = Arrangement.Start,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        line.words.forEach { word ->
                            WordKaraokeFillToken(
                                word = word,
                                currentMs = currentMs,
                                highlightTint = highlightTint,
                                isRtl = isRtl,
                                modifier = Modifier.padding(
                                    end = if (word.hasTrailingSpace) 8.dp else 0.dp,
                                ),
                            )
                        }
                    }
                }

                // Synchronized translation directly underneath each original line
                AnimatedVisibility(
                    visible = !translatedText.isNullOrBlank(),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    val transIsRtl = remember(translatedText) { isRtlText(translatedText.orEmpty()) }
                    CompositionLocalProvider(
                        LocalLayoutDirection provides if (transIsRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    ) {
                        Text(
                            text = translatedText.orEmpty(),
                            style = TextStyle(
                                fontFamily = InterFontFamily,
                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium,
                                fontSize = if (isActive) 17.sp else 15.sp,
                                lineHeight = if (isActive) 23.sp else 20.sp,
                            ),
                            color = if (isActive) {
                                highlightTint.copy(alpha = 0.96f)
                            } else {
                                Color.White.copy(alpha = 0.72f)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Hardware-clipped two-layer karaoke token that renders smooth, exact left-to-right (or
 * right-to-left for Arabic/Hebrew) word fill across every Unicode script and system fallback font.
 */
@Composable
private fun WordKaraokeFillToken(
    word: WordTiming,
    currentMs: Long,
    highlightTint: Color,
    isRtl: Boolean,
    modifier: Modifier = Modifier,
) {
    val rawProgress = when {
        currentMs <= word.startMs -> 0f
        currentMs >= word.endMs -> 1f
        else -> (currentMs - word.startMs).toFloat() / word.durationMs.toFloat()
    }.coerceIn(0f, 1f)

    // Smooth ease-out-in progression for natural vocal phrasing
    val fillProgress = if (rawProgress in 0.001f..0.999f) {
        1f - ((1f - rawProgress) * (1f - rawProgress) * 0.25f + (1f - rawProgress) * 0.75f)
    } else {
        rawProgress
    }

    val isCurrentlySpoken = rawProgress in 0.01f..0.99f
    val floatLiftDp = when {
        isCurrentlySpoken -> -2.2f * kotlin.math.sin(rawProgress * Math.PI).toFloat()
        rawProgress >= 0.99f -> -0.4f
        else -> 0f
    }

    val baseStyle = TextStyle(
        fontFamily = PlusJakartaSansFamily,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 28.sp,
        lineHeight = 37.sp,
        letterSpacing = (-0.2).sp,
    )

    val activeShadow = if (isCurrentlySpoken) {
        Shadow(
            color = highlightTint.copy(alpha = 0.85f),
            offset = Offset(0f, 0f),
            blurRadius = 18f,
        )
    } else if (fillProgress >= 0.99f) {
        Shadow(
            color = Color.White.copy(alpha = 0.28f),
            offset = Offset(0f, 0f),
            blurRadius = 8f,
        )
    } else {
        null
    }

    Box(
        modifier = modifier.graphicsLayer {
            translationY = floatLiftDp.dp.toPx()
        },
    ) {
        // Base layer: crisp dim unsung word
        Text(
            text = word.text,
            style = baseStyle,
            color = Color.White.copy(alpha = 0.34f),
        )

        // Overlay layer: pure bright sung fill swept smoothly with sub-pixel clipRect
        if (fillProgress > 0.002f) {
            Text(
                text = word.text,
                style = baseStyle.copy(shadow = activeShadow),
                color = if (isCurrentlySpoken) Color.White else Color.White.copy(alpha = 0.96f),
                modifier = Modifier.drawWithContent {
                    if (fillProgress >= 0.998f) {
                        drawContent()
                    } else if (isRtl) {
                        val clipLeft = size.width * (1f - fillProgress)
                        clipRect(
                            left = clipLeft,
                            top = -12f,
                            right = size.width + 12f,
                            bottom = size.height + 12f,
                        ) {
                            this@drawWithContent.drawContent()
                        }
                    } else {
                        val clipRight = size.width * fillProgress
                        clipRect(
                            left = -12f,
                            top = -12f,
                            right = clipRight,
                            bottom = size.height + 12f,
                        ) {
                            this@drawWithContent.drawContent()
                        }
                    }
                },
            )
        }
    }
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
        targetValue = if (isActive) 1f else 0.28f,
        animationSpec = spring(stiffness = 220f),
        label = "interludeAlpha",
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 14.dp)
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
            color = Color.White.copy(alpha = if (isActive) 0.78f else 0.36f),
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

private fun isRtlText(text: String): Boolean {
    for (ch in text) {
        val dir = Character.getDirectionality(ch)
        if (dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
            dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
        ) {
            return true
        }
        if (dir == Character.DIRECTIONALITY_LEFT_TO_RIGHT) {
            return false
        }
    }
    return false
}
