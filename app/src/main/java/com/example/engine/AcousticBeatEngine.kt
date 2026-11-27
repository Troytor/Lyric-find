package com.example.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.model.AudioTelemetry
import com.example.model.LyricLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "AcousticBeatEngine"

private data class VocalObservation(
    val trackPosMs: Long,
    val vocalLevel: Float,
)

/**
 * Pure deterministic real-time microphone DSP analyzer:
 * - Adaptive AGC + rolling noise-floor tracking
 * - 16-band musical Goertzel resonator filterbank
 * - Sub-bass spectral flux beat onset detector & BPM estimator
 * - Vocal formant envelope cross-correlation drift lock against active LRC lines
 */
class AcousticBeatEngine {

    private val sampleRate = 16_000
    private val frameSize = 512

    private val bandFrequenciesHz = floatArrayOf(
        55f, 82f, 115f, 160f,
        230f, 320f, 450f, 640f,
        900f, 1250f, 1750f, 2400f,
        3200f, 4200f, 5200f, 6400f,
    )

    private val bandCoeffs = FloatArray(bandFrequenciesHz.size) { idx ->
        val omega = 2.0 * PI * bandFrequenciesHz[idx] / sampleRate.toDouble()
        (2.0 * cos(omega)).toFloat()
    }

    private val hannWindow = FloatArray(frameSize) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (frameSize - 1))).toFloat()
    }

    @SuppressLint("MissingPermission")
    fun observeLiveAudio(
        referenceBpmProvider: () -> Float?,
        currentTrackPosMsProvider: () -> Long,
        activeLinesProvider: () -> List<LyricLine>,
        isPlaybackRunningProvider: () -> Boolean,
    ): Flow<AudioTelemetry> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid AudioRecord buffer size: $minBuf")
            return@flow
        }

        val bufferBytes = maxOf(minBuf, frameSize * 4)
        val recorder = openAudioRecorder(bufferBytes) ?: return@flow

        val pcmBuffer = ShortArray(frameSize)
        val windowed = FloatArray(frameSize)
        val smoothBands = FloatArray(bandFrequenciesHz.size)
        val fluxWindow = FloatArray(28)
        var fluxCursor = 0
        var prevLowEnergy = 0f
        var lastBeatAtMs = 0L
        var beatPulseDecay = 0f
        var beatCount = 0
        val beatIntervals = ArrayDeque<Long>()

        var noiseFloor = 0.012f
        var peakEnvelope = 0.14f
        var smoothVocalLevel = 0f
        var silenceStartMs = 0L
        var isPausedInRoom = false

        val vocalRing = ArrayDeque<VocalObservation>()
        var lastRingSampleMs = 0L
        var lastDriftEvalMs = 0L
        var measuredDriftMs = 0L
        var measuredDriftConf = 0f

        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val readCount = recorder.read(pcmBuffer, 0, frameSize)
                if (readCount <= 0) continue

                var sumSq = 0f
                for (i in 0 until readCount) {
                    val sample = pcmBuffer[i] / 32768f
                    sumSq += sample * sample
                    windowed[i] = sample * hannWindow[i]
                }
                if (readCount < frameSize) {
                    for (i in readCount until frameSize) windowed[i] = 0f
                }

                val rms = sqrt(sumSq / readCount).coerceIn(0f, 1f)
                noiseFloor = if (rms < noiseFloor) {
                    noiseFloor * 0.88f + rms * 0.12f
                } else {
                    noiseFloor * 0.996f + rms * 0.004f
                }.coerceIn(0.004f, 0.06f)

                peakEnvelope = if (rms > peakEnvelope) {
                    peakEnvelope * 0.72f + rms * 0.28f
                } else {
                    peakEnvelope * 0.992f + rms * 0.008f
                }.coerceIn(0.08f, 0.85f)

                val agcGain = (1f / max(0.06f, peakEnvelope - noiseFloor)).coerceIn(1.5f, 16f)
                val normalizedAmp = ((rms - noiseFloor * 0.7f).coerceAtLeast(0f) * agcGain).coerceIn(0f, 1f)

                for (b in bandCoeffs.indices) {
                    val rawMag = computeBandEnergy(windowed, bandCoeffs[b])
                    val scaled = (rawMag * agcGain * 0.95f).coerceIn(0f, 1f)
                    val alpha = if (scaled > smoothBands[b]) 0.56f else 0.20f
                    smoothBands[b] = smoothBands[b] + (scaled - smoothBands[b]) * alpha
                }

                val bassEnergy = ((smoothBands[0] + smoothBands[1] + smoothBands[2] + smoothBands[3]) / 4f)
                    .coerceIn(0f, 1f)
                var vocalBandSum = 0f
                for (v in 5..12) vocalBandSum += smoothBands[v]
                val vocalEnergy = (vocalBandSum / 8f).coerceIn(0f, 1f)
                smoothVocalLevel = smoothVocalLevel * 0.72f + vocalEnergy * 0.28f

                val positiveFlux = (bassEnergy - prevLowEnergy).coerceAtLeast(0f)
                prevLowEnergy = bassEnergy
                fluxWindow[fluxCursor] = positiveFlux
                fluxCursor = (fluxCursor + 1) % fluxWindow.size
                val meanFlux = fluxWindow.average().toFloat()

                val nowMs = SystemClock.elapsedRealtime()

                // Room silence / pause gating using broadband energy
                val broadbandEnergy = (bassEnergy * 0.45f + vocalEnergy * 0.55f)
                if (broadbandEnergy < 0.09f && normalizedAmp < 0.10f) {
                    if (silenceStartMs == 0L) silenceStartMs = nowMs
                    if (nowMs - silenceStartMs >= 2_800L) {
                        isPausedInRoom = true
                    }
                } else if (broadbandEnergy >= 0.15f || normalizedAmp >= 0.16f) {
                    silenceStartMs = 0L
                    isPausedInRoom = false
                }

                // Adaptive sub-bass spectral flux beat detector
                val fluxThreshold = maxOf(0.026f, meanFlux * 1.85f)
                val elapsedSinceBeat = nowMs - lastBeatAtMs
                var firedBeat = false
                if (positiveFlux > fluxThreshold && bassEnergy > 0.10f && elapsedSinceBeat >= 275L) {
                    firedBeat = true
                    beatPulseDecay = 1f
                    beatCount++
                    if (lastBeatAtMs > 0L && elapsedSinceBeat in 300L..1_150L) {
                        beatIntervals.addLast(elapsedSinceBeat)
                        if (beatIntervals.size > 16) beatIntervals.removeFirst()
                    }
                    lastBeatAtMs = nowMs
                } else {
                    beatPulseDecay = (beatPulseDecay * 0.84f).coerceAtLeast(0f)
                }

                // Vocal formant envelope ring buffer for drift cross-correlation
                if (isPlaybackRunningProvider() && !isPausedInRoom && nowMs - lastRingSampleMs >= 60L) {
                    lastRingSampleMs = nowMs
                    vocalRing.addLast(
                        VocalObservation(
                            trackPosMs = currentTrackPosMsProvider(),
                            vocalLevel = smoothVocalLevel,
                        ),
                    )
                    while (vocalRing.size > 84) vocalRing.removeFirst()
                }

                if (vocalRing.size >= 45 && nowMs - lastDriftEvalMs >= 1_400L) {
                    lastDriftEvalMs = nowMs
                    val lines = activeLinesProvider()
                    if (lines.isNotEmpty() && !isPausedInRoom) {
                        val (drift, conf) = estimateVocalEnvelopeDrift(vocalRing, lines)
                        measuredDriftMs = drift
                        measuredDriftConf = conf
                    } else {
                        measuredDriftMs = 0L
                        measuredDriftConf = 0f
                    }
                }

                val refBpm = referenceBpmProvider()
                val liveBpm = estimateBpm(beatIntervals, refBpm)
                val pllRatio = computePllRatio(liveBpm, refBpm)

                emit(
                    AudioTelemetry(
                        amplitude = normalizedAmp,
                        vocalEnergy = smoothVocalLevel,
                        bassEnergy = bassEnergy,
                        spectrum = smoothBands.toList(),
                        isBeatPulse = firedBeat,
                        beatIntensity = beatPulseDecay,
                        detectedBpm = liveBpm,
                        tempoLockRatio = pllRatio,
                        beatCount = beatCount,
                        isVocalActive = smoothVocalLevel >= 0.22f,
                        estimatedDriftMs = measuredDriftMs,
                        driftConfidence = measuredDriftConf,
                        isMusicPausedInRoom = isPausedInRoom,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio analysis loop stopped", e)
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
            recorder.release()
        }
    }.flowOn(Dispatchers.Default)

    @SuppressLint("MissingPermission")
    private fun openAudioRecorder(bufferBytes: Int): AudioRecord? {
        val sources = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }
        for (src in sources) {
            val rec = try {
                AudioRecord(
                    src,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "RECORD_AUDIO permission missing", e)
                return null
            } catch (e: Exception) {
                null
            }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            rec?.release()
        }
        return null
    }

    private fun estimateVocalEnvelopeDrift(
        observations: ArrayDeque<VocalObservation>,
        lines: List<LyricLine>,
    ): Pair<Long, Float> {
        val n = observations.size
        if (n < 30) return 0L to 0f

        var obsMean = 0f
        for (o in observations) obsMean += o.vocalLevel
        obsMean /= n

        var obsVar = 0f
        for (o in observations) {
            val d = o.vocalLevel - obsMean
            obsVar += d * d
        }
        if (obsVar / n < 0.006f) return 0L to 0f

        var zeroLagCorr = 0f
        var bestLagMs = 0L
        var bestCorr = -1f

        var lagMs = -1_200L
        while (lagMs <= 1_200L) {
            var expMean = 0f
            val expVals = FloatArray(n)
            var idx = 0
            for (o in observations) {
                val expected = expectedVocalAt(o.trackPosMs + lagMs, lines)
                expVals[idx++] = expected
                expMean += expected
            }
            expMean /= n

            var cov = 0f
            var expVar = 0f
            idx = 0
            for (o in observations) {
                val dx = o.vocalLevel - obsMean
                val dy = expVals[idx++] - expMean
                cov += dx * dy
                expVar += dy * dy
            }

            val denom = sqrt((obsVar * expVar).toDouble()).toFloat()
            val corr = if (denom > 1e-5f) (cov / denom) else 0f
            if (lagMs == 0L) zeroLagCorr = corr
            if (corr > bestCorr) {
                bestCorr = corr
                bestLagMs = lagMs
            }
            lagMs += 60L
        }

        val improvement = bestCorr - zeroLagCorr
        return if (bestCorr >= 0.36f && (improvement >= 0.05f || abs(bestLagMs) <= 60L)) {
            bestLagMs to bestCorr.coerceIn(0f, 1f)
        } else {
            0L to 0f
        }
    }

    private fun expectedVocalAt(ms: Long, lines: List<LyricLine>): Float {
        if (ms < 0L) return 0f
        for (line in lines) {
            if (line.isInstrumental) continue
            if (ms < line.startMs - 80L) return 0f
            if (ms in line.startMs..line.endMs) {
                if (line.words.isEmpty()) return 0.9f
                for (w in line.words) {
                    if (ms in w.startMs..w.endMs) return 1.0f
                }
                return 0.45f
            }
        }
        return 0f
    }

    private fun computeBandEnergy(samples: FloatArray, coeff: Float): Float {
        var sPrev = 0f
        var sPrev2 = 0f
        for (i in samples.indices) {
            val s = samples[i] + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        val power = (sPrev2 * sPrev2) + (sPrev * sPrev) - (coeff * sPrev * sPrev2)
        val safePower = if (power > 0f) power else 0f
        return sqrt(safePower) / (samples.size * 0.25f)
    }

    private fun estimateBpm(intervals: ArrayDeque<Long>, referenceBpm: Float?): Float? {
        if (intervals.size < 4) return null
        val sorted = intervals.sorted()
        val medianMs = sorted[sorted.size / 2].toFloat()
        if (medianMs <= 0f) return null

        var bpm = 60_000f / medianMs
        while (bpm < 68f) bpm *= 2f
        while (bpm > 176f) bpm /= 2f

        if (referenceBpm != null && referenceBpm in 55f..190f) {
            val candidates = listOf(bpm, bpm * 2f, bpm / 2f).filter { it in 55f..195f }
            val nearest = candidates.minByOrNull { abs(it - referenceBpm) }
            if (nearest != null) bpm = nearest
        }
        return bpm
    }

    private fun computePllRatio(liveBpm: Float?, referenceBpm: Float?): Float {
        if (liveBpm == null || referenceBpm == null || referenceBpm <= 40f) return 1f
        val ratio = liveBpm / referenceBpm
        return if (ratio in 0.94f..1.06f) {
            1f + ((ratio - 1f) * 0.45f)
        } else {
            1f
        }
    }
}
