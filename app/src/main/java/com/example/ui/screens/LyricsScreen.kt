package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.model.MicMode
import com.example.model.SyncedTrack
import com.example.ui.components.LiquidGlassIconButton
import com.example.ui.components.LiquidGlassPillButton
import com.example.ui.components.LiquidGlassSurface
import com.example.ui.components.WordSyncedLyricsView
import com.example.viewmodel.CadenceUiState
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun LyricsScreen(
    uiState: CadenceUiState,
    track: SyncedTrack,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleBeatSync: () -> Unit,
    onRelockLine: () -> Unit,
    onOpenTranslation: () -> Unit,
    onQuickNudgeOffset: (Long) -> Unit,
    onOpenCalibration: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onArtworkBitmapLoaded: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        val isExpandedLayout = maxWidth >= 700.dp

        if (isExpandedLayout) {
            ExpandedTwoPaneLyricsLayout(
                uiState = uiState,
                track = track,
                hasMicPermission = hasMicPermission,
                onRequestMicPermission = onRequestMicPermission,
                onBack = onBack,
                onOpenSearch = onOpenSearch,
                onTogglePlayPause = onTogglePlayPause,
                onToggleBeatSync = onToggleBeatSync,
                onRelockLine = onRelockLine,
                onOpenTranslation = onOpenTranslation,
                onQuickNudgeOffset = onQuickNudgeOffset,
                onOpenCalibration = onOpenCalibration,
                onSeekToMs = onSeekToMs,
                onArtworkBitmapLoaded = onArtworkBitmapLoaded,
            )
        } else {
            CompactPhoneLyricsLayout(
                uiState = uiState,
                track = track,
                hasMicPermission = hasMicPermission,
                onRequestMicPermission = onRequestMicPermission,
                onBack = onBack,
                onOpenSearch = onOpenSearch,
                onTogglePlayPause = onTogglePlayPause,
                onToggleBeatSync = onToggleBeatSync,
                onRelockLine = onRelockLine,
                onOpenTranslation = onOpenTranslation,
                onQuickNudgeOffset = onQuickNudgeOffset,
                onOpenCalibration = onOpenCalibration,
                onSeekToMs = onSeekToMs,
                onArtworkBitmapLoaded = onArtworkBitmapLoaded,
            )
        }
    }
}

@Composable
private fun CompactPhoneLyricsLayout(
    uiState: CadenceUiState,
    track: SyncedTrack,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleBeatSync: () -> Unit,
    onRelockLine: () -> Unit,
    onOpenTranslation: () -> Unit,
    onQuickNudgeOffset: (Long) -> Unit,
    onOpenCalibration: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onArtworkBitmapLoaded: (Bitmap) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Full-bleed Musixmatch-style word-by-word synced lyrics + dual-line translations
        WordSyncedLyricsView(
            lines = track.lines,
            clockAnchor = uiState.clockAnchor,
            syncOffsetMs = uiState.syncOffsetMs,
            beatIntensity = uiState.telemetry.beatIntensity,
            highlightTint = uiState.ambientPalette.highlightTint,
            topContentPadding = PaddingValues(
                top = 112.dp,
                bottom = 220.dp,
                start = 18.dp,
                end = 18.dp,
            ),
            onLineTap = onSeekToMs,
            isTranslationEnabled = uiState.isTranslationEnabled,
            translatedLines = uiState.translatedLines,
        )

        // Floating top liquid glass Now Playing bar
        TopTrackGlassBar(
            track = track,
            liveBpm = uiState.telemetry.detectedBpm,
            beatIntensity = uiState.telemetry.beatIntensity,
            isTrackSynced = uiState.isTrackSynced,
            isMicSyncEnabled = uiState.isMicSyncEnabled,
            isDeviceBroadcastSynced = uiState.isDeviceBroadcastSynced,
            highlightTint = uiState.ambientPalette.highlightTint,
            onBack = onBack,
            onOpenSearch = onOpenSearch,
            onArtworkBitmapLoaded = onArtworkBitmapLoaded,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        )

        // Live re-syncing pill or room-silence indicator above dock
        val showStatusPill = uiState.micMode == MicMode.RELOCKING_LINE || uiState.autoPausedBySilence
        AnimatedVisibility(
            visible = showStatusPill,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 168.dp, start = 24.dp, end = 24.dp),
        ) {
            LiquidGlassSurface(
                shape = RoundedCornerShape(50),
                tintColor = uiState.ambientPalette.primaryGlow,
                fillAlpha = 0.56f,
                highlightAlpha = 0.40f,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GraphicEq,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val pillText = if (uiState.micMode == MicMode.RELOCKING_LINE) {
                        uiState.liveTranscript.ifBlank { stringResource(R.string.lyrics_relocking) }
                    } else {
                        stringResource(R.string.lyrics_waiting_for_music)
                    }
                    Text(
                        text = pillText,
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        // Unified floating Musixmatch-style live timeline + transport + translate console
        FloatingLyricsControlDock(
            uiState = uiState,
            track = track,
            hasMicPermission = hasMicPermission,
            onRequestMicPermission = onRequestMicPermission,
            onTogglePlayPause = onTogglePlayPause,
            onToggleBeatSync = onToggleBeatSync,
            onRelockLine = onRelockLine,
            onOpenTranslation = onOpenTranslation,
            onQuickNudgeOffset = onQuickNudgeOffset,
            onOpenCalibration = onOpenCalibration,
            onSeekToMs = onSeekToMs,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun ExpandedTwoPaneLyricsLayout(
    uiState: CadenceUiState,
    track: SyncedTrack,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleBeatSync: () -> Unit,
    onRelockLine: () -> Unit,
    onOpenTranslation: () -> Unit,
    onQuickNudgeOffset: (Long) -> Unit,
    onOpenCalibration: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    onArtworkBitmapLoaded: (Bitmap) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(horizontal = 28.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(32.dp),
    ) {
        // Left canonical pane: artwork, metadata, live scrubber, controls
        Column(
            verticalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
        ) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LiquidGlassIconButton(onClick = onBack, modifier = Modifier.testTag("lyrics_back_button")) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.lyrics_back_cd),
                        tint = Color.White,
                    )
                }
                LiquidGlassIconButton(onClick = onOpenSearch, modifier = Modifier.testTag("lyrics_search_button")) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.lyrics_search_cd),
                        tint = Color.White,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LiquidGlassSurface(
                    shape = RoundedCornerShape(30.dp),
                    fillAlpha = 0.28f,
                    highlightAlpha = 0.34f,
                    modifier = Modifier
                        .fillMaxWidth(0.76f)
                        .aspectRatio(1f),
                ) {
                    TrackArtworkImage(
                        artworkUrl = track.metadata.artworkUrl,
                        highlightTint = uiState.ambientPalette.highlightTint,
                        onBitmapReady = onArtworkBitmapLoaded,
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Text(
                    text = track.metadata.title,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = track.metadata.artist,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            FloatingLyricsControlDock(
                uiState = uiState,
                track = track,
                hasMicPermission = hasMicPermission,
                onRequestMicPermission = onRequestMicPermission,
                onTogglePlayPause = onTogglePlayPause,
                onToggleBeatSync = onToggleBeatSync,
                onRelockLine = onRelockLine,
                onOpenTranslation = onOpenTranslation,
                onQuickNudgeOffset = onQuickNudgeOffset,
                onOpenCalibration = onOpenCalibration,
                onSeekToMs = onSeekToMs,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Right canonical pane: full-height synced lyrics + translations
        WordSyncedLyricsView(
            lines = track.lines,
            clockAnchor = uiState.clockAnchor,
            syncOffsetMs = uiState.syncOffsetMs,
            beatIntensity = uiState.telemetry.beatIntensity,
            highlightTint = uiState.ambientPalette.highlightTint,
            topContentPadding = PaddingValues(top = 64.dp, bottom = 120.dp, start = 12.dp, end = 12.dp),
            onLineTap = onSeekToMs,
            isTranslationEnabled = uiState.isTranslationEnabled,
            translatedLines = uiState.translatedLines,
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight(),
        )
    }
}

@Composable
private fun TopTrackGlassBar(
    track: SyncedTrack,
    liveBpm: Float?,
    beatIntensity: Float,
    isTrackSynced: Boolean,
    isMicSyncEnabled: Boolean,
    isDeviceBroadcastSynced: Boolean,
    highlightTint: Color,
    onBack: () -> Unit,
    onOpenSearch: () -> Unit,
    onArtworkBitmapLoaded: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    val beatDotScale = animateFloatAsState(
        targetValue = 1f + (beatIntensity * 0.45f),
        animationSpec = spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "beatDot",
    ).value

    LiquidGlassSurface(
        shape = RoundedCornerShape(28.dp),
        fillAlpha = 0.48f,
        highlightAlpha = 0.28f,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            LiquidGlassIconButton(
                onClick = onBack,
                modifier = Modifier.testTag("lyrics_back_button"),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.lyrics_back_cd),
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.12f)),
            ) {
                TrackArtworkImage(
                    artworkUrl = track.metadata.artworkUrl,
                    highlightTint = highlightTint,
                    onBitmapReady = onArtworkBitmapLoaded,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.metadata.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = track.metadata.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    val displayBpm = liveBpm ?: track.metadata.bpm
                    if (displayBpm != null || isTrackSynced || isMicSyncEnabled || isDeviceBroadcastSynced) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .graphicsLayer {
                                    scaleX = beatDotScale
                                    scaleY = beatDotScale
                                }
                                .clip(CircleShape)
                                .background(
                                    if (isTrackSynced || isMicSyncEnabled || isDeviceBroadcastSynced) {
                                        highlightTint
                                    } else {
                                        Color.White.copy(alpha = 0.4f)
                                    },
                                ),
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        val badgeLabel = when {
                            isDeviceBroadcastSynced -> "PHONE SYNC"
                            isTrackSynced -> "SYNCED"
                            displayBpm != null -> "${displayBpm.roundToInt()} BPM"
                            else -> "LIVE"
                        }
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = highlightTint,
                            maxLines = 1,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            LiquidGlassIconButton(
                onClick = onOpenSearch,
                modifier = Modifier.testTag("lyrics_search_button"),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = stringResource(R.string.lyrics_search_cd),
                    tint = Color.White,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

@Composable
private fun FloatingLyricsControlDock(
    uiState: CadenceUiState,
    track: SyncedTrack,
    hasMicPermission: Boolean,
    onRequestMicPermission: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onToggleBeatSync: () -> Unit,
    onRelockLine: () -> Unit,
    onOpenTranslation: () -> Unit,
    onQuickNudgeOffset: (Long) -> Unit,
    onOpenCalibration: () -> Unit,
    onSeekToMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = uiState.ambientPalette.highlightTint
    val isRelocking = uiState.micMode == MicMode.RELOCKING_LINE
    val durationMs = track.metadata.durationMs.coerceAtLeast(1_000L)

    // Smooth real-time position for Musixmatch-style live timeline scrubber
    var livePosMs by remember(uiState.clockAnchor, uiState.syncOffsetMs) {
        mutableLongStateOf((uiState.clockAnchor.currentPositionMs() + uiState.syncOffsetMs).coerceIn(0L, durationMs))
    }
    var isDraggingSlider by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(uiState.clockAnchor, uiState.syncOffsetMs, durationMs) {
        if (!uiState.clockAnchor.isRunning) {
            livePosMs = (uiState.clockAnchor.basePositionMs + uiState.syncOffsetMs).coerceIn(0L, durationMs)
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos {
                val now = SystemClock.elapsedRealtimeNanos()
                livePosMs = (uiState.clockAnchor.currentPositionMs(now) + uiState.syncOffsetMs)
                    .coerceIn(0L, durationMs)
            }
        }
    }

    val sliderValue = if (isDraggingSlider) {
        dragFraction
    } else {
        (livePosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
    val displayedMs = if (isDraggingSlider) (dragFraction * durationMs).roundToLong() else livePosMs

    LiquidGlassSurface(
        shape = RoundedCornerShape(30.dp),
        fillAlpha = 0.54f,
        highlightAlpha = 0.30f,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            // Top timeline row: Live timestamp + Musixmatch quick -0.2s / +0.2s sync nudge chips
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${formatDurationMs(displayedMs)} / ${formatDurationMs(durationMs)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.80f),
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    QuickNudgeChip(
                        label = stringResource(R.string.lyrics_nudge_earlier),
                        onClick = { onQuickNudgeOffset(-200L) },
                        modifier = Modifier.testTag("quick_nudge_minus"),
                    )
                    if (uiState.syncOffsetMs != 0L) {
                        val sec = uiState.syncOffsetMs / 1000f
                        val sign = if (sec > 0f) "+" else ""
                        Text(
                            text = String.format("%s%.1fs", sign, sec),
                            style = MaterialTheme.typography.labelSmall,
                            color = accent,
                        )
                    }
                    QuickNudgeChip(
                        label = stringResource(R.string.lyrics_nudge_later),
                        onClick = { onQuickNudgeOffset(200L) },
                        modifier = Modifier.testTag("quick_nudge_plus"),
                    )
                }
            }

            // Interactive live progress scrubber bar
            Slider(
                value = sliderValue,
                onValueChange = { frac ->
                    isDraggingSlider = true
                    dragFraction = frac
                },
                onValueChangeFinished = {
                    val targetMs = (dragFraction * durationMs).roundToLong()
                    isDraggingSlider = false
                    onSeekToMs(targetMs)
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = accent,
                    inactiveTrackColor = Color.White.copy(alpha = 0.22f),
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .testTag("lyrics_timeline_slider"),
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Primary action row: Play/Pause, Mic Beat-Sync, Re-sync Audio, Translate, Calibration
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Play / Pause live lyrics clock
                LiquidGlassIconButton(
                    onClick = onTogglePlayPause,
                    isActive = uiState.clockAnchor.isRunning,
                    accentColor = accent,
                    modifier = Modifier.testTag("lyrics_play_pause_button"),
                ) {
                    Icon(
                        imageVector = if (uiState.clockAnchor.isRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (uiState.clockAnchor.isRunning) {
                            stringResource(R.string.lyrics_pause_cd)
                        } else {
                            stringResource(R.string.lyrics_play_cd)
                        },
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Live Microphone Beat-Sync toggle
                LiquidGlassIconButton(
                    onClick = {
                        if (hasMicPermission) onToggleBeatSync() else onRequestMicPermission()
                    },
                    isActive = uiState.isMicSyncEnabled && hasMicPermission,
                    accentColor = accent,
                    modifier = Modifier.testTag("lyrics_mic_sync_toggle"),
                ) {
                    Icon(
                        imageVector = if (uiState.isMicSyncEnabled && hasMicPermission) {
                            Icons.Rounded.Mic
                        } else {
                            Icons.Rounded.MicOff
                        },
                        contentDescription = if (uiState.isMicSyncEnabled && hasMicPermission) {
                            stringResource(R.string.lyrics_mic_sync_on)
                        } else {
                            stringResource(R.string.lyrics_mic_sync_off)
                        },
                        tint = Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                }

                // One-shot acoustic sync pill: shows "Synced" with checkmark once locked so it never re-syncs unnecessarily
                val isSyncedLocked = uiState.isTrackSynced && !isRelocking
                LiquidGlassPillButton(
                    onClick = {
                        if (isSyncedLocked) {
                            // Already synced — no need to re-listen
                        } else if (hasMicPermission) {
                            onRelockLine()
                        } else {
                            onRequestMicPermission()
                        }
                    },
                    isActive = isRelocking || isSyncedLocked,
                    accentColor = accent,
                    modifier = Modifier.testTag("lyrics_relock_button"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Icon(
                            imageVector = if (isSyncedLocked) Icons.Rounded.CheckCircle else Icons.Rounded.Hearing,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSyncedLocked) {
                                stringResource(R.string.lyrics_synced_locked)
                            } else {
                                stringResource(R.string.lyrics_sync_once)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }

                // Musixmatch-style Translation toggle & language picker button
                LiquidGlassPillButton(
                    onClick = onOpenTranslation,
                    isActive = uiState.isTranslationEnabled,
                    accentColor = accent,
                    modifier = Modifier.testTag("lyrics_translate_button"),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        if (uiState.isTranslating) {
                            CircularProgressIndicator(
                                color = Color.White,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(16.dp),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Rounded.Translate,
                                contentDescription = stringResource(R.string.lyrics_translate_cd),
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (uiState.isTranslationEnabled) {
                                uiState.selectedTranslationLang.code.substringBefore("-").uppercase()
                            } else {
                                stringResource(R.string.lyrics_translate_label)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                            maxLines = 1,
                        )
                    }
                }

                // Acoustic Sync Calibration & Spectrum Sheet button
                LiquidGlassIconButton(
                    onClick = onOpenCalibration,
                    modifier = Modifier.testTag("lyrics_calibration_button"),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Tune,
                        contentDescription = stringResource(R.string.lyrics_calibration_cd),
                        tint = Color.White,
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickNudgeChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.90f),
        )
    }
}

private fun formatDurationMs(ms: Long): String {
    val totalSec = (ms / 1_000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format("%d:%02d", mins, secs)
}

@Composable
private fun TrackArtworkImage(
    artworkUrl: String?,
    highlightTint: Color,
    onBitmapReady: (Bitmap) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (artworkUrl.isNullOrBlank()) {
        Box(
            modifier = modifier.background(Color.White.copy(alpha = 0.08f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.MusicNote,
                contentDescription = null,
                tint = highlightTint,
                modifier = Modifier.size(26.dp),
            )
        }
        return
    }

    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(artworkUrl)
            .allowHardware(false)
            .crossfade(true)
            .build(),
        contentDescription = stringResource(R.string.lyrics_album_art_cd),
        contentScale = ContentScale.Crop,
        onSuccess = { success ->
            val bitmap = (success.result.drawable as? BitmapDrawable)?.bitmap
            if (bitmap != null) {
                onBitmapReady(bitmap)
            }
        },
        modifier = modifier,
    )
}
