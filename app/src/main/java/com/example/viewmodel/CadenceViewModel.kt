package com.example.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.remote.MusicRepository
import com.example.engine.AcousticBeatEngine
import com.example.engine.ArtworkPaletteExtractor
import com.example.engine.CloudAiPacingRepository
import com.example.engine.LocalOnDevicePacingRepository
import com.example.engine.VocalCaptureController
import com.example.engine.VocalCaptureEvent
import com.example.engine.WordPacingMlEngine
import com.example.model.AppStage
import com.example.model.AudioTelemetry
import com.example.model.MicMode
import com.example.model.SongCandidate
import com.example.model.SyncedTrack
import com.example.model.TimingSource
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
import kotlin.math.abs
import kotlin.math.roundToLong

private const val TAG = "CadenceViewModel"

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
    val liveTranscript: String = "",
    val statusDetail: String? = null,
    val activeTrack: SyncedTrack? = null,
    val clockAnchor: PlaybackClockAnchor = PlaybackClockAnchor(),
    val syncOffsetMs: Long = 0L,
    val isMicSyncEnabled: Boolean = true,
    val telemetry: AudioTelemetry = AudioTelemetry(),
    val ambientPalette: AmbientPalette = AmbientPalette.DefaultIdle,
    val isSearchOpen: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<SongCandidate> = emptyList(),
    val isSearching: Boolean = false,
    val isLoadingTrack: Boolean = false,
    val isCalibrationOpen: Boolean = false,
    val errorBanner: String? = null,
    val cloudPacingNotice: String = "",
)

class CadenceViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = MusicRepository()
    private val pacingEngine = WordPacingMlEngine()
    private val beatEngine = AcousticBeatEngine()
    private val vocalCapture = VocalCaptureController(application.applicationContext)
    private val cloudPacingRepo: CloudAiPacingRepository = LocalOnDevicePacingRepository()

    private val _uiState = MutableStateFlow(
        CadenceUiState(
            cloudPacingNotice = cloudPacingRepo.getCloudStatus().statusNote,
        ),
    )
    val uiState: StateFlow<CadenceUiState> = _uiState.asStateFlow()

    private var beatJob: Job? = null
    private var searchJob: Job? = null
    private var identifyJob: Job? = null
    private var lastAppliedLiveBpm: Float? = null
    private var lastPartialQueryWordCount = 0

    fun toggleListeningForSong() {
        val mode = _uiState.value.micMode
        if (mode == MicMode.IDENTIFYING || mode == MicMode.MATCHING_ONLINE) {
            stopAllMicTasks()
            _uiState.update {
                it.copy(
                    micMode = MicMode.IDLE,
                    liveTranscript = "",
                    statusDetail = null,
                )
            }
        } else {
            startSongIdentification()
        }
    }

    fun startSongIdentification() {
        stopBeatSyncEngine()
        identifyJob?.cancel()
        lastPartialQueryWordCount = 0

        _uiState.update {
            it.copy(
                stage = AppStage.LISTEN,
                micMode = MicMode.IDENTIFYING,
                liveTranscript = "",
                statusDetail = null,
                errorBanner = null,
            )
        }

        vocalCapture.start { event ->
            when (event) {
                is VocalCaptureEvent.Ready -> {
                    _uiState.update { it.copy(micMode = MicMode.IDENTIFYING) }
                }

                is VocalCaptureEvent.Level -> {
                    _uiState.update { state ->
                        val pulseBands = List(16) { idx ->
                            val phase = ((idx * 0.45f) + event.normalized * 4f)
                            val wave = (kotlin.math.sin(phase.toDouble()).toFloat() * 0.25f) + 0.75f
                            (event.normalized * wave).coerceIn(0.05f, 1f)
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

                is VocalCaptureEvent.PartialPhrase -> {
                    _uiState.update { it.copy(liveTranscript = event.phrase) }
                    val wordCount = event.phrase.split(Regex("\\s+")).count { it.length >= 2 }
                    if (wordCount >= 4 && wordCount >= lastPartialQueryWordCount + 2) {
                        lastPartialQueryWordCount = wordCount
                        attemptIdentifyFromPhrase(event.phrase, isFinal = false)
                    }
                }

                is VocalCaptureEvent.FinalPhrase -> {
                    _uiState.update { it.copy(liveTranscript = event.phrase) }
                    attemptIdentifyFromPhrase(event.phrase, isFinal = true)
                }

                is VocalCaptureEvent.Stopped -> {
                    if (_uiState.value.micMode == MicMode.IDENTIFYING) {
                        val transcript = _uiState.value.liveTranscript
                        if (transcript.split(Regex("\\s+")).count { it.length >= 2 } >= 3) {
                            attemptIdentifyFromPhrase(transcript, isFinal = true)
                        } else {
                            _uiState.update {
                                it.copy(
                                    micMode = MicMode.IDLE,
                                    errorBanner = event.reason ?: "No vocals heard yet. Tap the orb while singing plays or search directly.",
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun attemptIdentifyFromPhrase(phrase: String, isFinal: Boolean) {
        if (identifyJob?.isActive == true && !isFinal) return
        identifyJob?.cancel()
        val captureEpochMs = SystemClock.elapsedRealtime()

        identifyJob = viewModelScope.launch {
            if (isFinal) {
                _uiState.update { it.copy(micMode = MicMode.MATCHING_ONLINE) }
            }
            try {
                val match = repository.identifySongFromVocalTranscript(phrase)
                if (match != null) {
                    vocalCapture.stop()
                    val networkLatencyMs = (SystemClock.elapsedRealtime() - captureEpochMs).coerceIn(400L, 4_500L)
                    val startMs = ((match.matchedLineStartMs ?: 0L) + networkLatencyMs).coerceAtLeast(0L)
                    openCandidateTrack(match, initialPositionMs = startMs, autoStartMicSync = true)
                } else if (isFinal) {
                    _uiState.update {
                        it.copy(
                            micMode = MicMode.IDLE,
                            errorBanner = "Couldn't match \"$phrase\" to a synced track. Try again closer to the speaker or use search.",
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Song identification failed", e)
                if (isFinal) {
                    _uiState.update {
                        it.copy(
                            micMode = MicMode.IDLE,
                            errorBanner = "Network lookup failed: ${e.localizedMessage ?: "check internet connection"}",
                        )
                    }
                }
            }
        }
    }

    fun selectSearchCandidate(candidate: SongCandidate) {
        _uiState.update { it.copy(isSearchOpen = false) }
        viewModelScope.launch {
            openCandidateTrack(candidate, initialPositionMs = 0L, autoStartMicSync = _uiState.value.isMicSyncEnabled)
        }
    }

    private suspend fun openCandidateTrack(
        candidate: SongCandidate,
        initialPositionMs: Long,
        autoStartMicSync: Boolean,
    ) {
        _uiState.update {
            it.copy(
                isLoadingTrack = true,
                micMode = MicMode.MATCHING_ONLINE,
                errorBanner = null,
            )
        }
        try {
            val syncedTrack = repository.loadFullSyncedTrack(
                candidate = candidate,
                liveDetectedBpm = _uiState.value.telemetry.detectedBpm,
            )
            lastAppliedLiveBpm = syncedTrack.metadata.bpm
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val clampedStart = initialPositionMs.coerceIn(0L, syncedTrack.metadata.durationMs.coerceAtLeast(1L))

            _uiState.update {
                it.copy(
                    stage = AppStage.LYRICS,
                    activeTrack = syncedTrack,
                    clockAnchor = PlaybackClockAnchor(
                        basePositionMs = clampedStart,
                        baseElapsedNanos = nowNanos,
                        isRunning = true,
                        rate = 1f,
                    ),
                    syncOffsetMs = 0L,
                    isLoadingTrack = false,
                    micMode = if (autoStartMicSync) MicMode.BEAT_SYNCING else MicMode.IDLE,
                    liveTranscript = "",
                    ambientPalette = AmbientPalette.DefaultIdle,
                )
            }
            if (autoStartMicSync) {
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
                    errorBanner = "Could not load track lyrics: ${e.localizedMessage ?: "network error"}",
                )
            }
        }
    }

    fun startBeatSyncEngine() {
        if (beatJob?.isActive == true) return
        vocalCapture.stop()
        _uiState.update { it.copy(isMicSyncEnabled = true, micMode = MicMode.BEAT_SYNCING) }

        beatJob = viewModelScope.launch {
            beatEngine.observeLiveAudio(
                referenceBpmProvider = { _uiState.value.activeTrack?.metadata?.bpm },
            ).collect { telemetry ->
                val state = _uiState.value
                val track = state.activeTrack

                // Dynamically refine word pacing if track had no Deezer BPM and live mic locked onto one
                if (track != null &&
                    track.timingSource == TimingSource.ML_PROSODIC_BEAT_ENGINE &&
                    telemetry.detectedBpm != null
                ) {
                    val prev = lastAppliedLiveBpm
                    if (prev == null || abs(prev - telemetry.detectedBpm) >= 4.5f) {
                        lastAppliedLiveBpm = telemetry.detectedBpm
                        val refinedLines = withContext(Dispatchers.Default) {
                            pacingEngine.refineWithLiveBpm(track.lines, telemetry.detectedBpm)
                        }
                        _uiState.update { cur ->
                            cur.copy(
                                activeTrack = cur.activeTrack?.copy(lines = refinedLines),
                            )
                        }
                    }
                }

                // Update PLL clock rate gently when running
                val currentAnchor = state.clockAnchor
                val updatedAnchor = if (currentAnchor.isRunning &&
                    abs(currentAnchor.rate - telemetry.tempoLockRatio) >= 0.008f
                ) {
                    val nowNanos = SystemClock.elapsedRealtimeNanos()
                    PlaybackClockAnchor(
                        basePositionMs = currentAnchor.currentPositionMs(nowNanos),
                        baseElapsedNanos = nowNanos,
                        isRunning = true,
                        rate = telemetry.tempoLockRatio,
                    )
                } else {
                    currentAnchor
                }

                _uiState.update {
                    it.copy(
                        telemetry = telemetry,
                        clockAnchor = updatedAnchor,
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
                    telemetry = AudioTelemetry(),
                )
            }
        } else {
            startBeatSyncEngine()
        }
    }

    /**
     * Momentarily listens to the current sung vocal line with SpeechRecognizer,
     * aligns it against the active track's LRC lines, and snaps playback to that moment.
     */
    fun relockCurrentLineWithMic() {
        val track = _uiState.value.activeTrack ?: return
        if (_uiState.value.micMode == MicMode.RELOCKING_LINE) {
            vocalCapture.stop()
            if (_uiState.value.isMicSyncEnabled) startBeatSyncEngine() else {
                _uiState.update { it.copy(micMode = MicMode.IDLE) }
            }
            return
        }

        stopBeatSyncEngine()
        _uiState.update {
            it.copy(
                micMode = MicMode.RELOCKING_LINE,
                liveTranscript = "",
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

        vocalCapture.start { event ->
            when (event) {
                is VocalCaptureEvent.Level -> {
                    _uiState.update {
                        it.copy(telemetry = it.telemetry.copy(amplitude = event.normalized, vocalEnergy = event.normalized))
                    }
                }

                is VocalCaptureEvent.PartialPhrase -> {
                    _uiState.update { it.copy(liveTranscript = event.phrase) }
                    val currentPos = _uiState.value.clockAnchor.currentPositionMs()
                    val matchedMs = repository.findBestRelockTimestamp(event.phrase, track.lines, currentPos)
                    if (matchedMs != null && event.phrase.split(Regex("\\s+")).size >= 3) {
                        vocalCapture.stop()
                        seekTo(matchedMs, resumeIfPaused = true)
                        _uiState.update { it.copy(liveTranscript = "") }
                        resumeBeatSync()
                    }
                }

                is VocalCaptureEvent.FinalPhrase -> {
                    val currentPos = _uiState.value.clockAnchor.currentPositionMs()
                    val matchedMs = repository.findBestRelockTimestamp(event.phrase, track.lines, currentPos)
                    if (matchedMs != null) {
                        seekTo(matchedMs, resumeIfPaused = true)
                    } else {
                        _uiState.update {
                            it.copy(errorBanner = "Couldn't match line from \"${event.phrase}\". Tap any lyric line to snap.")
                        }
                    }
                    _uiState.update { it.copy(liveTranscript = "") }
                    resumeBeatSync()
                }

                is VocalCaptureEvent.Stopped -> {
                    _uiState.update { it.copy(liveTranscript = "") }
                    resumeBeatSync()
                }

                VocalCaptureEvent.Ready -> Unit
            }
        }
    }

    fun togglePlayPause() {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        _uiState.update { state ->
            val currentPos = state.clockAnchor.currentPositionMs(nowNanos)
            val nextRunning = !state.clockAnchor.isRunning
            state.copy(
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
        _uiState.update { state ->
            state.copy(
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
        _uiState.update {
            it.copy(
                stage = AppStage.LISTEN,
                micMode = MicMode.IDLE,
                clockAnchor = PlaybackClockAnchor(isRunning = false),
                telemetry = AudioTelemetry(),
                ambientPalette = AmbientPalette.DefaultIdle,
                isCalibrationOpen = false,
                liveTranscript = "",
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
        super.onCleared()
    }
}
