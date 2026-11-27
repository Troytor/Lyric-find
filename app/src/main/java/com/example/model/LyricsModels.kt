package com.example.model

import android.os.SystemClock
import androidx.compose.runtime.Immutable

@Immutable
data class WordTiming(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val stressWeight: Float = 1f,
    val isBeatAligned: Boolean = false,
    val hasTrailingSpace: Boolean = true,
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
    PROSODIC_BEAT_ENGINE,
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
data class DevicePlayingTrack(
    val title: String,
    val artist: String,
    val album: String = "",
    val isPlaying: Boolean = true,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val sourceApp: String = "Phone Player",
    val receivedAtElapsedMs: Long = SystemClock.elapsedRealtime(),
) {
    fun estimatedCurrentPositionMs(nowElapsedMs: Long = SystemClock.elapsedRealtime()): Long {
        if (!isPlaying) return positionMs.coerceAtLeast(0L)
        val delta = (nowElapsedMs - receivedAtElapsedMs).coerceAtLeast(0L)
        val est = positionMs + delta
        return if (durationMs > 0L) est.coerceIn(0L, durationMs) else est.coerceAtLeast(0L)
    }
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
    val estimatedDriftMs: Long = 0L,
    val driftConfidence: Float = 0f,
    val isMusicPausedInRoom: Boolean = false,
)

enum class AppStage {
    LISTEN,
    LYRICS,
}

enum class MicMode {
    IDLE,
    RECORDING_FINGERPRINT,
    IDENTIFYING,
    MATCHING_ONLINE,
    RELOCKING_LINE,
    BEAT_SYNCING,
}

@Immutable
data class TranslationLanguage(
    val code: String,
    val displayName: String,
    val nativeName: String,
)

object SupportedTranslationLanguages {
    val all: List<TranslationLanguage> = listOf(
        TranslationLanguage("en", "English", "English"),
        TranslationLanguage("es", "Spanish", "Español"),
        TranslationLanguage("fr", "French", "Français"),
        TranslationLanguage("pt", "Portuguese", "Português"),
        TranslationLanguage("de", "German", "Deutsch"),
        TranslationLanguage("it", "Italian", "Italiano"),
        TranslationLanguage("ja", "Japanese", "日本語"),
        TranslationLanguage("ko", "Korean", "한국어"),
        TranslationLanguage("zh-CN", "Chinese", "中文"),
        TranslationLanguage("ar", "Arabic", "العربية"),
        TranslationLanguage("hi", "Hindi", "हिन्दी"),
        TranslationLanguage("ru", "Russian", "Русский"),
        TranslationLanguage("tr", "Turkish", "Türkçe"),
        TranslationLanguage("vi", "Vietnamese", "Tiếng Việt"),
        TranslationLanguage("tl", "Tagalog", "Tagalog"),
    )

    fun byCode(code: String?): TranslationLanguage =
        all.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: all.first()
}

@Immutable
data class SearchLanguageOption(
    val code: String,
    val displayName: String,
    val nativeName: String,
    val itunesCountry: String? = null,
    val itunesLang: String? = null,
    val requiresNonLatinScript: Boolean = false,
) {
    val isUniversal: Boolean get() = code == "all"
}

object SupportedSearchLanguages {
    val all: List<SearchLanguageOption> = listOf(
        SearchLanguageOption("all", "All Languages", "Universal"),
        SearchLanguageOption("en", "English", "English", itunesCountry = "US", itunesLang = "en_us"),
        SearchLanguageOption("es", "Spanish", "Español", itunesCountry = "MX", itunesLang = "en_us"),
        SearchLanguageOption("ja", "Japanese", "日本語", itunesCountry = "JP", itunesLang = "ja_jp", requiresNonLatinScript = true),
        SearchLanguageOption("ko", "Korean", "한국어", itunesCountry = "KR", itunesLang = "en_us", requiresNonLatinScript = true),
        SearchLanguageOption("zh-CN", "Chinese", "中文", itunesCountry = "TW", itunesLang = "en_us", requiresNonLatinScript = true),
        SearchLanguageOption("fr", "French", "Français", itunesCountry = "FR", itunesLang = "en_us"),
        SearchLanguageOption("pt", "Portuguese", "Português", itunesCountry = "BR", itunesLang = "en_us"),
        SearchLanguageOption("de", "German", "Deutsch", itunesCountry = "DE", itunesLang = "en_us"),
        SearchLanguageOption("it", "Italian", "Italiano", itunesCountry = "IT", itunesLang = "en_us"),
        SearchLanguageOption("hi", "Hindi", "हिन्दी", itunesCountry = "IN", itunesLang = "en_us", requiresNonLatinScript = true),
        SearchLanguageOption("ar", "Arabic", "العربية", itunesCountry = "AE", itunesLang = "en_us", requiresNonLatinScript = true),
        SearchLanguageOption("ru", "Russian", "Русский", itunesCountry = "RU", itunesLang = "en_us", requiresNonLatinScript = true),
        SearchLanguageOption("tr", "Turkish", "Türkçe", itunesCountry = "TR", itunesLang = "en_us"),
        SearchLanguageOption("vi", "Vietnamese", "Tiếng Việt", itunesCountry = "VN", itunesLang = "en_us"),
        SearchLanguageOption("th", "Thai", "ไทย", itunesCountry = "TH", itunesLang = "en_us", requiresNonLatinScript = true),
        SearchLanguageOption("id", "Indonesian", "Bahasa Indonesia", itunesCountry = "ID", itunesLang = "en_us"),
        SearchLanguageOption("tl", "Tagalog", "Tagalog", itunesCountry = "PH", itunesLang = "en_us"),
    )

    fun byCode(code: String?): SearchLanguageOption =
        all.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: all.first()
}
