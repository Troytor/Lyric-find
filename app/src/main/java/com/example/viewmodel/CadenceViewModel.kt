package com.example.viewmodel

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.remote.AudioRecognitionOutcome
import com.example.data.remote.MusicRepository
import com.example.data.remote.ShazamRecognitionOutcome
import com.example.engine.AcousticBeatEngine
import com.example.engine.ArtworkPaletteExtractor
import com.example.engine.AudioClipRecorder
import com.example.engine.DeviceNowPlayingMonitor
import com.example.engine.VocalCaptureController
import com.example.engine.VocalCaptureEvent
import com.example.engine.WordPacingMlEngine
import com.example.model.AppStage
import com.example.model.AudioTelemetry
import com.example.model.DevicePlayingTrack
import com.example.model.MicMode
import com.example.model.SongCandidate
import com.example.model.SupportedTranslationLanguages
import com.example.model.SyncedTrack
import com.example.model.TimingSource
import com.example.model.TranslationLanguage
import com.example.ui.theme.AmbientPalette
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToLong

private const val TAG = "CadenceViewModel"
private const val PREFS_NAME = "cadence_prefs"
private const val KEY_AUDD_TOKEN = "audd_api_token"
private const val KEY_TRANSLATION_LANG = "translation_target_lang"

@Immutable
data class PlaybackClockAnchor(
    val basePositionMs: Long = 0L,
    val baseElapsedNanos: Long = 0L,
    val isRunning: Boolean = false,
    val rate: Float = 1f,
) {
    fun currentPositionMs(nowNanos: Long = SystemClock.elapsedRealtimeNanos()): Long {
        if (!isRunning) return basePositionMs.coerceAtLeast(0L)
        val deltaMs = ((nowNanos - baseElapsedNanos) / 1_000_000.0) * rate
        return (basePositionMs + deltaMs.roundToLong()).coerceAtLeast(0L)
    }
}

@Immutable
data class CadenceUiState(
    val stage: AppStage = AppStage.LISTEN,
    val micMode: MicMode = MicMode.IDLE,
    val recordingProgress: Float = 0f,
    val liveTranscript: String = "",
    val statusDetail: String? = null,
    val activeTrack: SyncedTrack? = null,
    val clockAnchor: PlaybackClockAnchor = PlaybackClockAnchor(),
    val syncOffsetMs: Long = 0L,
    val isMicSyncEnabled: Boolean = true,
    val isDeviceBroadcastSynced: Boolean = false,
    val telemetry: AudioTelemetry = AudioTelemetry(),
    val ambientPalette: AmbientPalette = AmbientPalette.DefaultIdle,
    val devicePlayingTrack: DevicePlayingTrack? = null,
    val isDeviceMusicActive: Boolean = false,
    val customAudDToken: String = "",
    val recognitionEngineNote: String = "Free Acoustic Fingerprint DB · LRCLIB · iTunes · Deezer",
    val isTranslationEnabled: Boolean = false,
    val isTranslating: Boolean = false,
    val isTranslationSheetOpen: Boolean = false,
    val selectedTranslationLang: TranslationLanguage = SupportedTranslationLanguages.all.first(),
    val detectedSourceLang: String? = null,
    val translatedLines: Map<Int, String> = emptyMap(),
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SongCandidate> = emptyList(),
    val isSearching: Boolean = false,
    val isLoadingTrack: Boolean = false,
    val isCalibrationOpen: Boolean = false,
    val errorBanner: String? = null,
    val autoPausedBySilence: Boolean = false,
    val lastDriftCorrectionMs: Long = 0L,
)

class CadenceViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val pacingEngine = WordPacingMlEngine()
    private val repository = MusicRepository(pacingEngine = pacingEngine)
    private val beatEngine = AcousticBeatEngine()
    private val audioClipRecorder = AudioClipRecorder()
    private val vocalCapture = VocalCaptureController(application.applicationContext)
    private val deviceMonitor = DeviceNowPlayingMonitor(application.applicationContext)

    private val defaultLangCode = prefs.getString(KEY_TRANSLATION_LANG, null)
        ?: Locale.getDefault().language.takeIf { code ->
            SupportedTranslationLanguages.all.any { it.code.equals(code, ignoreCase = true) }
        }
        ?: "en"

    private val _uiState = MutableStateFlow(
        CadenceUiState(
            customAudDToken = prefs.getString(KEY_AUDD_TOKEN, "").orEmpty(),
            selectedTranslationLang = SupportedTranslationLanguages.byCode(defaultLangCode),
        ),
    )
    val uiState: StateFlow<CadenceUiState> = _uiState.asStateFlow()

    private var beatJob: Job? = null
    private var searchJob: Job? = null
    private var identifyJob: Job? = null
    private var translateJob: Job? = null
    private var lastAppliedLiveBpm: Float? = null
    private var lastQueriedPhrase = ""
    private var lastDriftCorrectionAtMs = 0L
    private var userManuallyPaused = false

    init {
        deviceMonitor.start()

        viewModelScope.launch {
            deviceMonitor.currentDeviceTrack.collect { deviceTrack ->
                _uiState.update { it.copy(devicePlayingTrack = deviceTrack) }
                if (deviceTrack != null) {
                    syncActiveTrackWithDeviceBroadcast(deviceTrack)
                }
            }
        }

        viewModelScope.launch {
            deviceMonitor.isDeviceMusicActive.collect { active ->
                _uiState.update { it.copy(isDeviceMusicActive = active) }
            }
        }
    }

    fun refreshDeviceAudioState() {
        deviceMonitor.refreshAudioActiveState()
    }

    fun saveCustomAudDToken(token: String) {
        val cleaned = token.trim()
        prefs.edit().putString(KEY_AUDD_TOKEN, cleaned).apply()
        _uiState.update { it.copy(customAudDToken = cleaned) }
    }

    fun openTranslationSheet() {
        _uiState.update { it.copy(isTranslationSheetOpen = true) }
    }

    fun closeTranslationSheet() {
        _uiState.update { it.copy(isTranslationSheetOpen = false) }
    }

    fun selectTranslationLanguage(language: TranslationLanguage) {
        prefs.edit().putString(KEY_TRANSLATION_LANG, language.code).apply()
        _uiState.update {
            it.copy(
                selectedTranslationLang = language,
                isTranslationEnabled = true,
                isTranslationSheetOpen = false,
            )
        }
        val track = _uiState.value.activeTrack ?: return
        runTranslationForTrack(track, language)
    }

    fun disableTranslation() {
        translateJob?.cancel()
        _uiState.update {
            it.copy(
                isTranslationEnabled = false,
                isTranslating = false,
                isTranslationSheetOpen = false,
            )
        }
    }

    private fun runTranslationForTrack(
        track: SyncedTrack,
        language: TranslationLanguage,
    ) {
        translateJob?.cancel()
        if (track.lines.isEmpty()) return

        translateJob = viewModelScope.launch {
            _uiState.update { it.copy(isTranslating = true) }
            try {
                val result = repository.translateTrackLines(
                    trackId = track.metadata.id,
                    lines = track.lines,
                    targetLanguage = language,
                )
                if (result != null && result.lineTranslations.isNotEmpty()) {
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            detectedSourceLang = result.sourceLangCode,
                            translatedLines = result.lineTranslations,
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isTranslating = false,
                            errorBanner = "Couldn't translate lyrics right now. Check your internet connection.",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Translation failed", e)
                _uiState.update {
                    it.copy(
                        isTranslating = false,
                        errorBanner = "Translation unavailable: ${e.localizedMessage ?: "network error"}",
                    )
                }
            }
        }
    }

    fun toggleListeningForSong() {
        val mode = _uiState.value.micMode
        if (mode == MicMode.RECORDING_FINGERPRINT ||
            mode == MicMode.IDENTIFYING ||
            mode == MicMode.MATCHING_ONLINE
        ) {
            stopAllMicTasks()
            _uiState.update {
                it.copy(
                    micMode = MicMode.IDLE,
                    recordingProgress = 0f,
                    liveTranscript = "",
                    statusDetail = null,
                    telemetry = AudioTelemetry(),
                )
            }
        } else {
            startSongIdentification()
        }
    }

    /**
     * Free, precision progressive acoustic recognition pipeline:
     * 1. Pass 1: Captures 4.8s of 16 kHz mono PCM, encodes a spectral constellation signature,
     *    and queries the free Shazam discovery v5 database (no API key or paid quota required).
     * 2. Pass 2: If no match yet, seamlessly captures 4.5s more (9.3s continuous audio) and retries.
     * 3. Fallback: Checks optional AudD token (if configured) and then live vocal lyric matching.
     * Aligns playback directly to (sampleStartOffsetMs + clipDurationMs + networkLatencyMs).
     */
    fun startSongIdentification() {
        stopAllMicTasks()
        lastQueriedPhrase = ""

        _uiState.update {
            it.copy(
                stage = AppStage.LISTEN,
                micMode = MicMode.RECORDING_FINGERPRINT,
                recordingProgress = 0f,
                liveTranscript = "",
                statusDetail = "Listening to music…",
                errorBanner = null,
            )
        }

        identifyJob = viewModelScope.launch {
            val tagUuid = UUID.randomUUID().toString().uppercase()
            val sessionTimestampMs = System.currentTimeMillis()

            // Pass 1: Fast 4.8-second acoustic fingerprint capture
            val clip1 = audioClipRecorder.recordWavClip(
                durationMs = 4_800L,
                onLiveFrame = { amp, spectrum, progress ->
                    _uiState.update { state ->
                        if (state.micMode != MicMode.RECORDING_FINGERPRINT) {
                            state
                        } else {
                            state.copy(
                                recordingProgress = progress * 0.55f,
                                telemetry = state.telemetry.copy(
                                    amplitude = amp,
                                    spectrum = spectrum,
                                ),
                            )
                        }
                    }
                },
            )

            if (clip1 == null) {
                startVocalFallbackIdentification(
                    bannerNotice = null,
                    initialStatus = "Listening for sung vocals…",
                )
                return@launch
            }

            if (clip1.peakRms < 0.0018f) {
                _uiState.update {
                    it.copy(
                        micMode = MicMode.IDLE,
                        recordingProgress = 0f,
                        statusDetail = null,
                        telemetry = AudioTelemetry(),
                        errorBanner = "Microphone heard silence. Turn up the music or hold closer to the speaker.",
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    micMode = MicMode.MATCHING_ONLINE,
                    recordingProgress = 0.58f,
                    statusDetail = "Matching acoustic fingerprint…",
                )
            }

            when (
                val pass1 = repository.identifySongFromShazamSignature(
                    pcmSamples = clip1.pcmSamples,
                    tagUuid = tagUuid,
                    sessionTimestampMs = sessionTimestampMs,
                )
            ) {
                is ShazamRecognitionOutcome.Matched -> {
                    val elapsedSinceClipEnd = (SystemClock.elapsedRealtime() - clip1.recordedEndElapsedMs)
                        .coerceIn(0L, 12_000L)
                    val liveStartMs = pass1.sampleStartOffsetMs + clip1.durationMs + elapsedSinceClipEnd
                    openCandidateTrack(
                        candidate = pass1.candidate,
                        initialPositionMs = liveStartMs,
                        autoStartMicSync = true,
                        autoRelockAfterLoad = false,
                        startRunning = true,
                        isDeviceBroadcast = false,
                    )
                    return@launch
                }

                is ShazamRecognitionOutcome.RetryNeeded,
                ShazamRecognitionOutcome.NoMatch,
                is ShazamRecognitionOutcome.NetworkError -> {
                    // Proceed to Pass 2 for deeper 9.3-second sample accuracy
                }
            }

            // Pass 2: Extend sample by 4.5s more (9.3s total continuous PCM)
            _uiState.update {
                it.copy(
                    micMode = MicMode.RECORDING_FINGERPRINT,
                    statusDetail = "Refining audio match…",
                )
            }

            val clip2 = audioClipRecorder.recordWavClip(
                durationMs = 4_500L,
                prefixSamples = clip1.pcmSamples,
                onLiveFrame = { amp, spectrum, progress ->
                    _uiState.update { state ->
                        if (state.micMode != MicMode.RECORDING_FINGERPRINT) {
                            state
                        } else {
                            state.copy(
                                recordingProgress = 0.55f + (progress * 0.45f),
                                telemetry = state.telemetry.copy(
                                    amplitude = amp,
                                    spectrum = spectrum,
                                ),
                            )
                        }
                    }
                },
            )

            val bestClip = clip2 ?: clip1
            _uiState.update {
                it.copy(
                    micMode = MicMode.MATCHING_ONLINE,
                    recordingProgress = 1f,
                    statusDetail = "Checking acoustic database…",
                )
            }

            when (
                val pass2 = repository.identifySongFromShazamSignature(
                    pcmSamples = bestClip.pcmSamples,
                    tagUuid = tagUuid,
                    sessionTimestampMs = sessionTimestampMs,
                )
            ) {
                is ShazamRecognitionOutcome.Matched -> {
                    val elapsedSinceClipEnd = (SystemClock.elapsedRealtime() - bestClip.recordedEndElapsedMs)
                        .coerceIn(0L, 12_000L)
                    val liveStartMs = pass2.sampleStartOffsetMs + bestClip.durationMs + elapsedSinceClipEnd
                    openCandidateTrack(
                        candidate = pass2.candidate,
                        initialPositionMs = liveStartMs,
                        autoStartMicSync = true,
                        autoRelockAfterLoad = false,
                        startRunning = true,
                        isDeviceBroadcast = false,
                    )
                    return@launch
                }

                else -> Unit
            }

            // Secondary fallback: Optional user-provided AudD token if saved
            val customToken = _uiState.value.customAudDToken
            if (customToken.isNotBlank()) {
                val audDOutcome = repository.identifySongFromAudioWav(
                    wavBytes = bestClip.wavBytes,
                    apiToken = customToken,
                )
                if (audDOutcome is AudioRecognitionOutcome.Matched) {
                    val elapsed = (SystemClock.elapsedRealtime() - bestClip.recordedEndElapsedMs)
                        .coerceIn(0L, 12_000L)
                    openCandidateTrack(
                        candidate = audDOutcome.candidate,
                        initialPositionMs = if (audDOutcome.timecodeMs > 0L) audDOutcome.timecodeMs + elapsed else 0L,
                        autoStartMicSync = true,
                        autoRelockAfterLoad = false,
                        startRunning = true,
                        isDeviceBroadcast = false,
                    )
                    return@launch
                }
            }

            // Final fallback: Live vocal lyric phrase recognition (helpful for live/a cappella singing)
            startVocalFallbackIdentification(
                bannerNotice = null,
                initialStatus = "Checking sung lyrics…",
            )
        }
    }

    private fun startVocalFallbackIdentification(
        bannerNotice: String?,
        initialStatus: String,
    ) {
        lastQueriedPhrase = ""
        _uiState.update {
            it.copy(
                stage = AppStage.LISTEN,
                micMode = MicMode.IDENTIFYING,
                recordingProgress = 0f,
                liveTranscript = "",
                statusDetail = initialStatus,
                errorBanner = bannerNotice,
            )
        }

        vocalCapture.startContinuous(maxSessionMs = 12_000L) { event ->
            when (event) {
                is VocalCaptureEvent.Ready -> {
                    _uiState.update {
                        if (it.micMode == MicMode.IDENTIFYING) {
                            it.copy(statusDetail = "Listening for sung lyrics…")
                        } else {
                            it
                        }
                    }
                }

                is VocalCaptureEvent.Level -> {
                    _uiState.update { state ->
                        val pulseBands = List(16) { idx ->
                            val phase = ((idx * 0.45f) + event.normalized * 4.5f)
                            val wave = (kotlin.math.sin(phase.toDouble()).toFloat() * 0.25f) + 0.75f
                            (event.normalized * wave).coerceIn(0.06f, 1f)
                        }
                        state.copy(
                            telemetry = state.telemetry.copy(
                                amplitude = event.normalized,
                                vocalEnergy = event.normalized,
                                spectrum = pulseBands,
                            ),
                        )
                    }
                }

                is VocalCaptureEvent.RollingPhrase -> {
                    val displayPhrase = event.accumulatedBuffer.ifBlank { event.latestSegment }
                    _uiState.update {
                        it.copy(
                            liveTranscript = displayPhrase,
                            statusDetail = "Matching lyrics online…",
                        )
                    }
                    val wordCount = displayPhrase.split(Regex("\\s+")).count { it.length >= 2 }
                    val enoughNewWords = displayPhrase != lastQueriedPhrase &&
                        ((event.isSegmentFinal && wordCount >= 3) || wordCount >= 4)

                    if (enoughNewWords) {
                        lastQueriedPhrase = displayPhrase
                        attemptIdentifyFromPhrase(
                            phrase = displayPhrase,
                            observedAtElapsedMs = event.observedAtElapsedMs,
                            allowLowThreshold = event.isSegmentFinal && wordCount >= 5,
                        )
                    }
                }

                is VocalCaptureEvent.Retrying -> {
                    _uiState.update {
                        if (it.micMode == MicMode.IDENTIFYING) {
                            it.copy(statusDetail = "Listening for next vocal phrase…")
                        } else {
                            it
                        }
                    }
                }

                is VocalCaptureEvent.Stopped -> {
                    if (_uiState.value.micMode == MicMode.IDENTIFYING || _uiState.value.micMode == MicMode.MATCHING_ONLINE) {
                        val transcript = _uiState.value.liveTranscript
                        if (transcript.split(Regex("\\s+")).count { it.length >= 2 } >= 3 && identifyJob?.isActive != true) {
                            attemptIdentifyFromPhrase(
                                phrase = transcript,
                                observedAtElapsedMs = SystemClock.elapsedRealtime(),
                                allowLowThreshold = true,
                                stopOnMiss = true,
                            )
                        } else if (identifyJob?.isActive != true) {
                            _uiState.update {
                                it.copy(
                                    micMode = MicMode.IDLE,
                                    recordingProgress = 0f,
                                    statusDetail = null,
                                    errorBanner = it.errorBanner
                                        ?: "No song recognized yet. Hold closer to the speaker, share from your music app, or search directly.",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun attemptIdentifyFromPhrase(
        phrase: String,
        observedAtElapsedMs: Long,
        allowLowThreshold: Boolean,
        stopOnMiss: Boolean = false,
    ) {
        if (identifyJob?.isActive == true && !stopOnMiss) return
        identifyJob?.cancel()

        identifyJob = viewModelScope.launch {
            _uiState.update { it.copy(statusDetail = "Matching full-text lyrics online…") }
            try {
                val match = repository.identifySongFromVocalTranscript(phrase)
                val requiredScore = if (allowLowThreshold) 0.44f else 0.56f
                if (match != null && match.matchScore >= requiredScore) {
                    vocalCapture.stop()
                    val elapsedSinceObserved = (SystemClock.elapsedRealtime() - observedAtElapsedMs)
                        .coerceIn(350L, 6_500L)
                    val syncedStartMs = if (match.matchedLineStartMs != null) {
                        (match.matchedLineStartMs + elapsedSinceObserved + 650L).coerceAtLeast(0L)
                    } else {
                        0L
                    }
                    openCandidateTrack(
                        candidate = match,
                        initialPositionMs = syncedStartMs,
                        autoStartMicSync = true,
                        autoRelockAfterLoad = match.matchedLineStartMs == null,
                        startRunning = true,
                        isDeviceBroadcast = false,
                    )
                } else if (stopOnMiss) {
                    _uiState.update {
                        it.copy(
                            micMode = MicMode.IDLE,
                            recordingProgress = 0f,
                            statusDetail = null,
                            errorBanner = "Couldn't match \"$phrase\". Hold closer to the speaker or search by title.",
                        )
                    }
                } else {
                    _uiState.update {
                        if (it.micMode == MicMode.IDENTIFYING) {
                            it.copy(statusDetail = "Listening for a few more words…")
                        } else {
                            it
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Vocal identification failed", e)
                if (stopOnMiss) {
                    _uiState.update {
                        it.copy(
                            micMode = MicMode.IDLE,
                            recordingProgress = 0f,
                            statusDetail = null,
                            errorBanner = "Network lookup failed: ${e.localizedMessage ?: "check internet connection"}",
                        )
                    }
                }
            }
        }
    }

    fun syncFromDeviceTrack() {
        val deviceTrack = _uiState.value.devicePlayingTrack ?: return
        stopAllMicTasks()
        val candidate = repository.candidateFromDeviceTrack(deviceTrack)
        viewModelScope.launch {
            openCandidateTrack(
                candidate = candidate,
                initialPositionMs = deviceTrack.estimatedCurrentPositionMs(),
                autoStartMicSync = false,
                autoRelockAfterLoad = false,
                startRunning = deviceTrack.isPlaying,
                isDeviceBroadcast = true,
            )
        }
    }

    fun handleSharedIntentText(sharedText: String) {
        if (sharedText.isBlank()) return
        stopAllMicTasks()
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoadingTrack = true,
                    micMode = MicMode.MATCHING_ONLINE,
                    statusDetail = "Reading shared song…",
                    errorBanner = null,
                )
            }
            try {
                val resolved = repository.resolveSharedTextOrUrl(sharedText)
                if (resolved != null) {
                    openCandidateTrack(
                        candidate = resolved,
                        initialPositionMs = 0L,
                        autoStartMicSync = _uiState.value.isMicSyncEnabled,
                        autoRelockAfterLoad = false,
                        startRunning = true,
                        isDeviceBroadcast = false,
                    )
                } else {
                    _uiState.update {
                        it.copy(
                            isLoadingTrack = false,
                            micMode = MicMode.IDLE,
                            statusDetail = null,
                            errorBanner = "Couldn't read that shared link automatically. Try searching the song title.",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed handling shared text", e)
                _uiState.update {
                    it.copy(
                        isLoadingTrack = false,
                        micMode = MicMode.IDLE,
                        statusDetail = null,
                        errorBanner = "Could not load shared track: ${e.localizedMessage ?: "network error"}",
                    )
                }
            }
        }
    }

    private fun syncActiveTrackWithDeviceBroadcast(deviceTrack: DevicePlayingTrack) {
        val state = _uiState.value
        val active = state.activeTrack ?: return
        if (state.stage != AppStage.LYRICS) return

        val sameTitle = repository.normalizeText(active.metadata.title) ==
            repository.normalizeText(deviceTrack.title)
        if (!sameTitle) return

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val devicePosMs = deviceTrack.estimatedCurrentPositionMs()
        val currentPosMs = state.clockAnchor.currentPositionMs(nowNanos)
        val posDiff = abs(devicePosMs - currentPosMs)
        val playStateChanged = state.clockAnchor.isRunning != deviceTrack.isPlaying

        if (playStateChanged || (devicePosMs > 0L && posDiff >= 1_800L)) {
            val targetPos = if (devicePosMs > 0L) devicePosMs else currentPosMs
            val clamped = targetPos.coerceIn(0L, active.metadata.durationMs.coerceAtLeast(1L))
            userManuallyPaused = !deviceTrack.isPlaying
            _uiState.update {
                it.copy(
                    autoPausedBySilence = false,
                    isDeviceBroadcastSynced = true,
                    clockAnchor = PlaybackClockAnchor(
                        basePositionMs = clamped,
                        baseElapsedNanos = nowNanos,
                        isRunning = deviceTrack.isPlaying,
                        rate = 1f,
                    ),
                )
            }
        }
    }

    fun selectSearchCandidate(candidate: SongCandidate) {
        _uiState.update { it.copy(isSearchOpen = false) }
        viewModelScope.launch {
            openCandidateTrack(
                candidate = candidate,
                initialPositionMs = 0L,
                autoStartMicSync = _uiState.value.isMicSyncEnabled,
                autoRelockAfterLoad = false,
                startRunning = true,
                isDeviceBroadcast = false,
            )
        }
    }

    private suspend fun openCandidateTrack(
        candidate: SongCandidate,
        initialPositionMs: Long,
        autoStartMicSync: Boolean,
        autoRelockAfterLoad: Boolean,
        startRunning: Boolean,
        isDeviceBroadcast: Boolean,
    ) {
        val loadStartedAtMs = SystemClock.elapsedRealtime()
        userManuallyPaused = !startRunning
        translateJob?.cancel()
        _uiState.update {
            it.copy(
                isLoadingTrack = true,
                micMode = MicMode.MATCHING_ONLINE,
                recordingProgress = 0f,
                statusDetail = "Loading synced lyrics & tempo…",
                errorBanner = null,
            )
        }
        try {
            val syncedTrack = repository.loadFullSyncedTrack(
                candidate = candidate,
                liveDetectedBpm = _uiState.value.telemetry.detectedBpm,
            )
            lastAppliedLiveBpm = syncedTrack.metadata.bpm
            val loadElapsedMs = if (initialPositionMs > 0L && startRunning) {
                (SystemClock.elapsedRealtime() - loadStartedAtMs).coerceIn(0L, 8_000L)
            } else {
                0L
            }
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val clampedStart = (initialPositionMs + loadElapsedMs)
                .coerceIn(0L, syncedTrack.metadata.durationMs.coerceAtLeast(1L))

            val keepTranslationOn = _uiState.value.isTranslationEnabled
            val targetLang = _uiState.value.selectedTranslationLang

            _uiState.update {
                it.copy(
                    stage = AppStage.LYRICS,
                    activeTrack = syncedTrack,
                    clockAnchor = PlaybackClockAnchor(
                        basePositionMs = clampedStart,
                        baseElapsedNanos = nowNanos,
                        isRunning = startRunning,
                        rate = 1f,
                    ),
                    syncOffsetMs = 0L,
                    isLoadingTrack = false,
                    micMode = if (autoStartMicSync) MicMode.BEAT_SYNCING else MicMode.IDLE,
                    isMicSyncEnabled = autoStartMicSync,
                    isDeviceBroadcastSynced = isDeviceBroadcast,
                    liveTranscript = "",
                    statusDetail = null,
                    autoPausedBySilence = false,
                    lastDriftCorrectionMs = 0L,
                    translatedLines = emptyMap(),
                    detectedSourceLang = null,
                    isTranslating = false,
                    ambientPalette = AmbientPalette.DefaultIdle,
                )
            }

            if (keepTranslationOn && syncedTrack.lines.isNotEmpty()) {
                runTranslationForTrack(syncedTrack, targetLang)
            }

            if (autoRelockAfterLoad && syncedTrack.lines.isNotEmpty()) {
                relockCurrentLineWithMic(preferProximity = false)
            } else if (autoStartMicSync) {
                startBeatSyncEngine()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed loading synced track", e)
            _uiState.update {
                it.copy(
                    isLoadingTrack = false,
                    micMode = MicMode.IDLE,
                    recordingProgress = 0f,
                    statusDetail = null,
                    errorBanner = "Could not load track lyrics: ${e.localizedMessage ?: "network error"}",
                )
            }
        }
    }

    fun startBeatSyncEngine() {
        if (beatJob?.isActive == true) return
        vocalCapture.stop()
        _uiState.update {
            it.copy(
                isMicSyncEnabled = true,
                micMode = MicMode.BEAT_SYNCING,
                autoPausedBySilence = false,
            )
        }

        beatJob = viewModelScope.launch {
            beatEngine.observeLiveAudio(
                referenceBpmProvider = { _uiState.value.activeTrack?.metadata?.bpm },
                currentTrackPosMsProvider = {
                    val s = _uiState.value
                    (s.clockAnchor.currentPositionMs() + s.syncOffsetMs).coerceAtLeast(0L)
                },
                activeLinesProvider = { _uiState.value.activeTrack?.lines.orEmpty() },
                isPlaybackRunningProvider = { _uiState.value.clockAnchor.isRunning },
            ).collect { telemetry ->
                val state = _uiState.value
                val track = state.activeTrack

                if (track != null &&
                    track.timingSource == TimingSource.PROSODIC_BEAT_ENGINE &&
                    telemetry.detectedBpm != null
                ) {
                    val prev = lastAppliedLiveBpm
                    if (prev == null || abs(prev - telemetry.detectedBpm) >= 4.5f) {
                        lastAppliedLiveBpm = telemetry.detectedBpm
                        val refinedLines = withContext(Dispatchers.Default) {
                            pacingEngine.refineWithLiveBpm(track.lines, telemetry.detectedBpm)
                        }
                        _uiState.update { cur ->
                            cur.copy(activeTrack = cur.activeTrack?.copy(lines = refinedLines))
                        }
                    }
                }

                val nowMs = SystemClock.elapsedRealtime()
                val nowNanos = SystemClock.elapsedRealtimeNanos()
                var updatedAnchor = state.clockAnchor
                var nextAutoPaused = state.autoPausedBySilence
                var appliedDriftMs = state.lastDriftCorrectionMs

                if (!userManuallyPaused) {
                    if (telemetry.isMusicPausedInRoom && updatedAnchor.isRunning) {
                        updatedAnchor = PlaybackClockAnchor(
                            basePositionMs = updatedAnchor.currentPositionMs(nowNanos),
                            baseElapsedNanos = nowNanos,
                            isRunning = false,
                            rate = updatedAnchor.rate,
                        )
                        nextAutoPaused = true
                    } else if (!telemetry.isMusicPausedInRoom && nextAutoPaused && !updatedAnchor.isRunning) {
                        updatedAnchor = PlaybackClockAnchor(
                            basePositionMs = updatedAnchor.basePositionMs,
                            baseElapsedNanos = nowNanos,
                            isRunning = true,
                            rate = telemetry.tempoLockRatio,
                        )
                        nextAutoPaused = false
                    }
                }

                if (updatedAnchor.isRunning &&
                    telemetry.driftConfidence >= 0.38f &&
                    abs(telemetry.estimatedDriftMs) >= 120L &&
                    nowMs - lastDriftCorrectionAtMs >= 2_200L
                ) {
                    lastDriftCorrectionAtMs = nowMs
                    val dampedPull = (telemetry.estimatedDriftMs * 0.38f).roundToLong()
                        .coerceIn(-280L, 280L)
                    val duration = track?.metadata?.durationMs ?: Long.MAX_VALUE
                    val correctedPos = (updatedAnchor.currentPositionMs(nowNanos) + dampedPull)
                        .coerceIn(0L, duration.coerceAtLeast(1L))
                    updatedAnchor = PlaybackClockAnchor(
                        basePositionMs = correctedPos,
                        baseElapsedNanos = nowNanos,
                        isRunning = true,
                        rate = telemetry.tempoLockRatio,
                    )
                    appliedDriftMs = dampedPull
                } else if (updatedAnchor.isRunning &&
                    abs(updatedAnchor.rate - telemetry.tempoLockRatio) >= 0.008f
                ) {
                    updatedAnchor = PlaybackClockAnchor(
                        basePositionMs = updatedAnchor.currentPositionMs(nowNanos),
                        baseElapsedNanos = nowNanos,
                        isRunning = true,
                        rate = telemetry.tempoLockRatio,
                    )
                }

                _uiState.update {
                    it.copy(
                        telemetry = telemetry,
                        clockAnchor = updatedAnchor,
                        autoPausedBySilence = nextAutoPaused,
                        lastDriftCorrectionMs = appliedDriftMs,
                    )
                }
            }
        }
    }

    fun toggleBeatSync() {
        if (_uiState.value.isMicSyncEnabled) {
            stopBeatSyncEngine()
            _uiState.update {
                it.copy(
                    isMicSyncEnabled = false,
                    micMode = MicMode.IDLE,
                    autoPausedBySilence = false,
                    telemetry = AudioTelemetry(),
                )
            }
        } else {
            startBeatSyncEngine()
        }
    }

    /**
     * Musixmatch-style live re-sync: captures a short 3.8s acoustic fingerprint via the free Shazam DB
     * to re-anchor the exact live timestamp of the song, falling back to vocal line matching if needed.
     */
    fun relockCurrentLineWithMic(preferProximity: Boolean = false) {
        val track = _uiState.value.activeTrack ?: return
        if (_uiState.value.micMode == MicMode.RELOCKING_LINE) {
            identifyJob?.cancel()
            vocalCapture.stop()
            if (_uiState.value.isMicSyncEnabled) startBeatSyncEngine() else {
                _uiState.update { it.copy(micMode = MicMode.IDLE, liveTranscript = "") }
            }
            return
        }

        stopBeatSyncEngine()
        identifyJob?.cancel()
        _uiState.update {
            it.copy(
                micMode = MicMode.RELOCKING_LINE,
                liveTranscript = "Re-syncing to live audio…",
                errorBanner = null,
            )
        }

        val resumeBeatSync: () -> Unit = {
            if (_uiState.value.stage == AppStage.LYRICS && _uiState.value.isMicSyncEnabled) {
                startBeatSyncEngine()
            } else {
                _uiState.update { it.copy(micMode = MicMode.IDLE) }
            }
        }

        // First try fast 4.0s acoustic fingerprint timestamp re-lock against the free Shazam DB
        identifyJob = viewModelScope.launch {
            val clip = audioClipRecorder.recordWavClip(
                durationMs = 4_000L,
                onLiveFrame = { amp, spectrum, _ ->
                    _uiState.update { state ->
                        state.copy(telemetry = state.telemetry.copy(amplitude = amp, spectrum = spectrum))
                    }
                },
            )

            if (clip != null && clip.peakRms >= 0.0018f) {
                val shazamOutcome = repository.identifySongFromShazamSignature(
                    pcmSamples = clip.pcmSamples,
                    tagUuid = UUID.randomUUID().toString().uppercase(),
                    sessionTimestampMs = System.currentTimeMillis(),
                )
                if (shazamOutcome is ShazamRecognitionOutcome.Matched) {
                    val matchedTitle = repository.normalizeText(shazamOutcome.candidate.metadata.title)
                    val activeTitle = repository.normalizeText(track.metadata.title)
                    val elapsed = (SystemClock.elapsedRealtime() - clip.recordedEndElapsedMs)
                        .coerceIn(0L, 10_000L)
                    val exactPos = shazamOutcome.sampleStartOffsetMs + clip.durationMs + elapsed

                    if (matchedTitle == activeTitle || matchedTitle.contains(activeTitle) || activeTitle.contains(matchedTitle)) {
                        userManuallyPaused = false
                        seekTo(exactPos, resumeIfPaused = true)
                        _uiState.update { it.copy(liveTranscript = "", autoPausedBySilence = false) }
                        resumeBeatSync()
                        return@launch
                    } else {
                        // A different song is playing now — switch seamlessly to it!
                        openCandidateTrack(
                            candidate = shazamOutcome.candidate,
                            initialPositionMs = exactPos,
                            autoStartMicSync = true,
                            autoRelockAfterLoad = false,
                            startRunning = true,
                            isDeviceBroadcast = false,
                        )
                        return@launch
                    }
                }
            }

            // Fallback to vocal lyric snippet alignment
            _uiState.update { it.copy(liveTranscript = "") }
            vocalCapture.startContinuous(maxSessionMs = 9_000L) { event ->
                when (event) {
                    is VocalCaptureEvent.Level -> {
                        _uiState.update {
                            it.copy(telemetry = it.telemetry.copy(amplitude = event.normalized, vocalEnergy = event.normalized))
                        }
                    }

                    is VocalCaptureEvent.RollingPhrase -> {
                        val phrase = event.latestSegment.ifBlank { event.accumulatedBuffer }
                        _uiState.update { it.copy(liveTranscript = phrase) }
                        val currentPos = _uiState.value.clockAnchor.currentPositionMs()
                        val match = repository.findBestRelockTimestamp(
                            heardPhrase = phrase,
                            lines = track.lines,
                            currentPositionMs = currentPos,
                            preferProximity = preferProximity,
                        ) ?: repository.findBestRelockTimestamp(
                            heardPhrase = event.accumulatedBuffer,
                            lines = track.lines,
                            currentPositionMs = currentPos,
                            preferProximity = preferProximity,
                        )

                        val wordCount = phrase.split(Regex("\\s+")).count { it.length >= 2 }
                        val scoreGate = if (event.isSegmentFinal) 0.48f else 0.62f
                        if (match != null && match.second >= scoreGate && wordCount >= 2) {
                            vocalCapture.stop()
                            val latencyMs = (SystemClock.elapsedRealtime() - event.observedAtElapsedMs)
                                .coerceIn(0L, 3_000L) + 550L
                            userManuallyPaused = false
                            seekTo(match.first + latencyMs, resumeIfPaused = true)
                            _uiState.update { it.copy(liveTranscript = "", autoPausedBySilence = false) }
                            resumeBeatSync()
                        }
                    }

                    is VocalCaptureEvent.Stopped -> {
                        val heard = _uiState.value.liveTranscript
                        if (heard.isNotBlank()) {
                            val currentPos = _uiState.value.clockAnchor.currentPositionMs()
                            val fallback = repository.findBestRelockTimestamp(
                                heardPhrase = heard,
                                lines = track.lines,
                                currentPositionMs = currentPos,
                                preferProximity = false,
                            )
                            if (fallback != null) {
                                userManuallyPaused = false
                                seekTo(fallback.first + 550L, resumeIfPaused = true)
                            } else {
                                _uiState.update {
                                    it.copy(errorBanner = "Couldn't re-sync automatically. Tap any lyric line to jump straight to it.")
                                }
                            }
                        }
                        _uiState.update { it.copy(liveTranscript = "") }
                        resumeBeatSync()
                    }

                    VocalCaptureEvent.Ready,
                    is VocalCaptureEvent.Retrying -> Unit
                }
            }
        }
    }

    fun togglePlayPause() {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        _uiState.update { state ->
            val currentPos = state.clockAnchor.currentPositionMs(nowNanos)
            val nextRunning = !state.clockAnchor.isRunning
            userManuallyPaused = !nextRunning
            state.copy(
                autoPausedBySilence = false,
                clockAnchor = PlaybackClockAnchor(
                    basePositionMs = currentPos,
                    baseElapsedNanos = nowNanos,
                    isRunning = nextRunning,
                    rate = state.clockAnchor.rate,
                ),
            )
        }
    }

    fun seekTo(positionMs: Long, resumeIfPaused: Boolean = false) {
        val duration = _uiState.value.activeTrack?.metadata?.durationMs ?: Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0L, duration.coerceAtLeast(1L))
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        if (resumeIfPaused) userManuallyPaused = false
        _uiState.update { state ->
            state.copy(
                autoPausedBySilence = false,
                clockAnchor = PlaybackClockAnchor(
                    basePositionMs = clamped,
                    baseElapsedNanos = nowNanos,
                    isRunning = if (resumeIfPaused) true else state.clockAnchor.isRunning,
                    rate = state.clockAnchor.rate,
                ),
            )
        }
    }

    fun adjustSyncOffset(deltaMs: Long) {
        _uiState.update {
            it.copy(syncOffsetMs = (it.syncOffsetMs + deltaMs).coerceIn(-5_000L, 5_000L))
        }
    }

    fun resetSyncOffset() {
        _uiState.update { it.copy(syncOffsetMs = 0L) }
    }

    fun onArtworkLoaded(bitmap: Bitmap) {
        viewModelScope.launch(Dispatchers.Default) {
            val extracted = ArtworkPaletteExtractor.extract(bitmap)
            _uiState.update { it.copy(ambientPalette = extracted) }
        }
    }

    fun openSearch() {
        _uiState.update { it.copy(isSearchOpen = true, errorBanner = null) }
    }

    fun closeSearch() {
        _uiState.update { it.copy(isSearchOpen = false) }
    }

    fun updateSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.trim().length < 2) {
            _uiState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(320L)
            _uiState.update { it.copy(isSearching = true, errorBanner = null) }
            try {
                val results = repository.searchRealTracks(query)
                _uiState.update { it.copy(searchResults = results, isSearching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Online search failed", e)
                _uiState.update {
                    it.copy(
                        isSearching = false,
                        errorBanner = "Search failed: ${e.localizedMessage ?: "check connection"}",
                    )
                }
            }
        }
    }

    fun openCalibrationSheet() {
        _uiState.update { it.copy(isCalibrationOpen = true) }
    }

    fun closeCalibrationSheet() {
        _uiState.update { it.copy(isCalibrationOpen = false) }
    }

    fun returnToListenStage() {
        stopAllMicTasks()
        translateJob?.cancel()
        userManuallyPaused = false
        deviceMonitor.refreshAudioActiveState()
        _uiState.update {
            it.copy(
                stage = AppStage.LISTEN,
                micMode = MicMode.IDLE,
                recordingProgress = 0f,
                clockAnchor = PlaybackClockAnchor(isRunning = false),
                telemetry = AudioTelemetry(),
                ambientPalette = AmbientPalette.DefaultIdle,
                isCalibrationOpen = false,
                isTranslationSheetOpen = false,
                isDeviceBroadcastSynced = false,
                liveTranscript = "",
                statusDetail = null,
                autoPausedBySilence = false,
            )
        }
    }

    fun dismissError() {
        _uiState.update { it.copy(errorBanner = null) }
    }

    private fun stopBeatSyncEngine() {
        beatJob?.cancel()
        beatJob = null
    }

    private fun stopAllMicTasks() {
        identifyJob?.cancel()
        identifyJob = null
        stopBeatSyncEngine()
        vocalCapture.stop()
    }

    override fun onCleared() {
        stopAllMicTasks()
        translateJob?.cancel()
        deviceMonitor.stop()
        super.onCleared()
    }
}
