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
import com.example.engine.LocalCadenceNeuralNet
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
    val localAiSummary: String = "",
    val autoPausedBySilence: Boolean = false,
    val lastDriftCorrectionMs: Long = 0L,
)

class CadenceViewModel(application: Application) : AndroidViewModel(application) {

    private val localNeuralNet = LocalCadenceNeuralNet()
    private val pacingEngine = WordPacingMlEngine(localNeuralNet)
    private val repository = MusicRepository(neuralNet = localNeuralNet, pacingEngine = pacingEngine)
    private val beatEngine = AcousticBeatEngine(localNeuralNet)
    private val vocalCapture = VocalCaptureController(application.applicationContext)
    private val localAiInfoRepo: CloudAiPacingRepository = LocalOnDevicePacingRepository(localNeuralNet)

    private val _uiState = MutableStateFlow(
        CadenceUiState(
            localAiSummary = localAiInfoRepo.getCloudStatus().statusNote,
        ),
    )
    val uiState: StateFlow<CadenceUiState> = _uiState.asStateFlow()

    private var beatJob: Job? = null
    private var searchJob: Job? = null
    private var identifyJob: Job? = null
    private var lastAppliedLiveBpm: Float? = null
    private var lastQueriedPhrase = ""
    private var lastDriftCorrectionAtMs = 0L
    private var userManuallyPaused = false

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
        lastQueriedPhrase = ""

        _uiState.update {
            it.copy(
                stage = AppStage.LISTEN,
                micMode = MicMode.IDENTIFYING,
                liveTranscript = "",
                statusDetail = "Listening for vocals…",
                errorBanner = null,
            )
        }

        vocalCapture.startContinuous(maxSessionMs = 26_000L) { event ->
            when (event) {
                is VocalCaptureEvent.Ready -> {
                    _uiState.update {
                        if (it.micMode == MicMode.IDENTIFYING) {
                            it.copy(statusDetail = "Listening for vocals…")
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
                            statusDetail = "Analyzing lyrics with local AI…",
                        )
                    }
                    val wordCount = displayPhrase.split(Regex("\\s+")).count { it.length >= 2 }
                    val enoughNewWords = displayPhrase != lastQueriedPhrase &&
                        (event.isSegmentFinal && wordCount >= 3 || wordCount >= 4)

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
                                    statusDetail = null,
                                    errorBanner = event.reason
                                        ?: "No clear vocals caught yet. Hold near the speaker during singing or search directly.",
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
                    // Add ~650ms SpeechRecognizer acoustic lag + elapsed network fetch time
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
                    )
                } else if (stopOnMiss) {
                    _uiState.update {
                        it.copy(
                            micMode = MicMode.IDLE,
                            statusDetail = null,
                            errorBanner = "Couldn't confidently match \"$phrase\". Try closer to the speaker or search by title.",
                        )
                    }
                } else {
                    // Keep listening smoothly for more sung words!
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
                Log.e(TAG, "Song identification failed", e)
                if (stopOnMiss) {
                    _uiState.update {
                        it.copy(
                            micMode = MicMode.IDLE,
                            statusDetail = null,
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
            openCandidateTrack(
                candidate = candidate,
                initialPositionMs = 0L,
                autoStartMicSync = _uiState.value.isMicSyncEnabled,
                autoRelockAfterLoad = false,
            )
        }
    }

    private suspend fun openCandidateTrack(
        candidate: SongCandidate,
        initialPositionMs: Long,
        autoStartMicSync: Boolean,
        autoRelockAfterLoad: Boolean,
    ) {
        val loadStartedAtMs = SystemClock.elapsedRealtime()
        userManuallyPaused = false
        _uiState.update {
            it.copy(
                isLoadingTrack = true,
                micMode = MicMode.MATCHING_ONLINE,
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
            val loadElapsedMs = if (initialPositionMs > 0L) {
                (SystemClock.elapsedRealtime() - loadStartedAtMs).coerceIn(0L, 6_000L)
            } else {
                0L
            }
            val nowNanos = SystemClock.elapsedRealtimeNanos()
            val clampedStart = (initialPositionMs + loadElapsedMs)
                .coerceIn(0L, syncedTrack.metadata.durationMs.coerceAtLeast(1L))

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
                    statusDetail = null,
                    autoPausedBySilence = false,
                    lastDriftCorrectionMs = 0L,
                    ambientPalette = AmbientPalette.DefaultIdle,
                )
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

                // Dynamically refine word pacing with CadenceNet-Nano when live mic locks a BPM
                if (track != null &&
                    track.timingSource == TimingSource.LOCAL_NEURAL_CADENCE_ENGINE &&
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

                // 1. Real room music pause/resume gating via CadenceNet-Nano
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

                // 2. Real acoustic vocal-envelope drift phase correction
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
                    // 3. Smooth tempo PLL rate update
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
     * Listens continuously for up to 12s to hear any live sung phrase, aligns it against
     * the active track's lyrics with CadenceNet-Nano, and snaps playback to the exact moment.
     */
    fun relockCurrentLineWithMic(preferProximity: Boolean = false) {
        val track = _uiState.value.activeTrack ?: return
        if (_uiState.value.micMode == MicMode.RELOCKING_LINE) {
            vocalCapture.stop()
            if (_uiState.value.isMicSyncEnabled) startBeatSyncEngine() else {
                _uiState.update { it.copy(micMode = MicMode.IDLE, liveTranscript = "") }
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

        vocalCapture.startContinuous(maxSessionMs = 12_000L) { event ->
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
                                it.copy(errorBanner = "Couldn't lock line from \"$heard\". Tap any lyric line to snap.")
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
        userManuallyPaused = false
        _uiState.update {
            it.copy(
                stage = AppStage.LISTEN,
                micMode = MicMode.IDLE,
                clockAnchor = PlaybackClockAnchor(isRunning = false),
                telemetry = AudioTelemetry(),
                ambientPalette = AmbientPalette.DefaultIdle,
                isCalibrationOpen = false,
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
        super.onCleared()
    }
}
