package com.example.model

import androidx.compose.runtime.Immutable

@Immutable
data class WordTiming(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val stressWeight: Float = 1f,
    val isBeatAligned: Boolean = false,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(1L)
}

@Immutable
data class LyricLine(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val rawText: String,
    val words: List<WordTiming>,
    val isInstrumental: Boolean = false,
)

enum class TimingSource {
    ENHANCED_LRC,
    ML_PROSODIC_BEAT_ENGINE,
    PLAIN_ESTIMATED,
}

@Immutable
data class TrackMetadata(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artworkUrl: String? = null,
    val bpm: Float? = null,
    val previewUrl: String? = null,
    val sourceLabel: String = "LRCLIB",
)

@Immutable
data class SyncedTrack(
    val metadata: TrackMetadata,
    val lines: List<LyricLine>,
    val timingSource: TimingSource,
    val engineConfidence: Float,
    val isSyncedAvailable: Boolean,
)

@Immutable
data class SongCandidate(
    val metadata: TrackMetadata,
    val syncedLyricsRaw: String?,
    val plainLyricsRaw: String?,
    val matchedLineStartMs: Long? = null,
    val matchScore: Float = 0f,
) {
    val hasSyncedLyrics: Boolean get() = !syncedLyricsRaw.isNullOrBlank()
}

@Immutable
data class AudioTelemetry(
    val amplitude: Float = 0f,
    val vocalEnergy: Float = 0f,
    val bassEnergy: Float = 0f,
    val spectrum: List<Float> = List(16) { 0f },
    val isBeatPulse: Boolean = false,
    val beatIntensity: Float = 0f,
    val detectedBpm: Float? = null,
    val tempoLockRatio: Float = 1f,
    val beatCount: Int = 0,
    val isVocalActive: Boolean = false,
)

enum class AppStage {
    LISTEN,
    LYRICS,
}

enum class MicMode {
    IDLE,
    IDENTIFYING,
    MATCHING_ONLINE,
    RELOCKING_LINE,
    BEAT_SYNCING,
}
