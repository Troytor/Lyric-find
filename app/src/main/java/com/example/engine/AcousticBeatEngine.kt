package com.example.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

private const val TAG = "AcousticBeatEngine"

private data class VocalObservation(
    val trackPosMs: Long,
    val vocalProb: Float,
)

/**
 * Real-time microphone acoustic DSP & local neural inference engine:
 * - Adaptive AGC + rolling noise-floor tracking
 * - 16-band musical Goertzel filterbank + spectral shape features
 * - CadenceNet-Nano per-frame inference (vocal, beat, music presence)
 * - Vocal-envelope cross-correlation drift measurement against active LRC lines
 */
class AcousticBeatEngine(
    private val neuralNet: LocalCadenceNeuralNet = LocalCadenceNeuralNet(),
) {

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

        // Adaptive gain & noise floor state
        var noiseFloor = 0.012f
        var peakEnvelope = 0.14f
        var smoothVocalProb = 0f
        var smoothMusicProb = 0.5f
        var silenceStartMs = 0L
        var isPausedInRoom = false

        // Vocal envelope ring buffer for real acoustic drift cross-correlation
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
                var zeroCrossings = 0
                var prevSample = 0f
                for (i in 0 until readCount) {
                    val sample = pcmBuffer[i] / 32768f
                    sumSq += sample * sample
                    if (i > 0 && ((sample >= 0f && prevSample < 0f) || (sample < 0f && prevSample >= 0f))) {
                        zeroCrossings++
                    }
                    prevSample = sample
                    windowed[i] = sample * hannWindow[i]
                }
                if (readCount < frameSize) {
                    for (i in readCount until frameSize) windowed[i] = 0f
                }

                val rms = sqrt(sumSq / readCount).coerceIn(0f, 1f)
                // Adapt noise floor and dynamic AGC peak smoothly
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
                val zcr = (zeroCrossings.toFloat() / readCount.toFloat() * 3.5f).coerceIn(0f, 1f)

                // Compute 16 musical resonator bands with adaptive AGC scaling
                var weightedFreqSum = 0f
                var totalBandSum = 0f
                var logSum = 0.0
                for (b in bandCoeffs.indices) {
                    val rawMag = computeBandEnergy(windowed, bandCoeffs[b])
                    val scaled = (rawMag * agcGain * 0.95f).coerceIn(0f, 1f)
                    val alpha = if (scaled > smoothBands[b]) 0.56f else 0.20f
                    smoothBands[b] = smoothBands[b] + (scaled - smoothBands[b]) * alpha

                    val v = smoothBands[b]
                    weightedFreqSum += v * (b.toFloat() / (bandCoeffs.size - 1).toFloat())
                    totalBandSum += v
                    logSum += ln((v + 1e-4f).toDouble())
                }

                val spectralCentroid = if (totalBandSum > 0.01f) {
                    (weightedFreqSum / totalBandSum).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val arithmeticMean = (totalBandSum / bandCoeffs.size).coerceAtLeast(1e-4f)
                val geometricMean = exp(logSum / bandCoeffs.size).toFloat()
                val spectralFlatness = (geometricMean / arithmeticMean).coerceIn(0f, 1f)

                val bassEnergy = ((smoothBands[0] + smoothBands[1] + smoothBands[2] + smoothBands[3]) / 4f)
                    .coerceIn(0f, 1f)
                var vocalBandSum = 0f
                for (v in 5..12) vocalBandSum += smoothBands[v]
                val vocalEnergy = (vocalBandSum / 8f).coerceIn(0f, 1f)

                val positiveFlux = (bassEnergy - prevLowEnergy).coerceAtLeast(0f)
                prevLowEnergy = bassEnergy
                fluxWindow[fluxCursor] = positiveFlux
                fluxCursor = (fluxCursor + 1) % fluxWindow.size
                val meanFlux = fluxWindow.average().toFloat()

                // Local AI inference (CadenceNet-Nano) on 12 acoustic features
                val features = AcousticFeatureVector(
                    bandSubBass = (smoothBands[0] + smoothBands[1]) * 0.5f,
                    bandBass = (smoothBands[2] + smoothBands[3]) * 0.5f,
                    bandLowMid = (smoothBands[4] + smoothBands[5]) * 0.5f,
                    bandVocalLow = (smoothBands[6] + smoothBands[7]) * 0.5f,
                    bandVocalMid = (smoothBands[8] + smoothBands[9]) * 0.5f,
                    bandVocalHigh = (smoothBands[10] + smoothBands[11]) * 0.5f,
                    bandPresence = (smoothBands[12] + smoothBands[13]) * 0.5f,
                    bandBrilliance = (smoothBands[14] + smoothBands[15]) * 0.5f,
                    spectralCentroid = spectralCentroid,
                    spectralFlatness = spectralFlatness,
                    zeroCrossingRate = zcr,
                    bassOnsetFlux = (positiveFlux * 4.5f).coerceIn(0f, 1f),
                )
                val neuralOut = neuralNet.inferAcousticFrame(features)
                smoothVocalProb = smoothVocalProb * 0.70f + neuralOut.vocalProbability * 0.30f
                smoothMusicProb = smoothMusicProb * 0.85f + neuralOut.musicPresenceProbability * 0.15f

                val nowMs = SystemClock.elapsedRealtime()

                // Room music pause / resume detection via neural music-presence gate
                if (smoothMusicProb < 0.22f && normalizedAmp < 0.10f) {
                    if (silenceStartMs == 0L) silenceStartMs = nowMs
                    if (nowMs - silenceStartMs >= 2_800L) {
                        isPausedInRoom = true
                    }
                } else if (smoothMusicProb >= 0.30f || normalizedAmp >= 0.16f) {
                    silenceStartMs = 0L
                    isPausedInRoom = false
                }

                // Hybrid Neural + Spectral Flux beat onset trigger
                val fluxThreshold = maxOf(0.028f, meanFlux * 1.80f)
                val elapsedSinceBeat = nowMs - lastBeatAtMs
                var firedBeat = false
                val beatTriggered = (neuralOut.beatOnsetProbability >= 0.58f && positiveFlux > fluxThreshold * 0.85f) ||
                    (positiveFlux > fluxThreshold * 1.25f && bassEnergy > 0.12f)

                if (beatTriggered && elapsedSinceBeat >= 275L) {
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

                // Record vocal envelope observations for real acoustic drift cross-correlation
                if (isPlaybackRunningProvider() && !isPausedInRoom && nowMs - lastRingSampleMs >= 60L) {
                    lastRingSampleMs = nowMs
                    vocalRing.addLast(
                        VocalObservation(
                            trackPosMs = currentTrackPosMsProvider(),
                            vocalProb = smoothVocalProb,
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
                        vocalEnergy = vocalEnergy,
                        bassEnergy = bassEnergy,
                        spectrum = smoothBands.toList(),
                        isBeatPulse = firedBeat,
                        beatIntensity = beatPulseDecay,
                        detectedBpm = liveBpm,
                        tempoLockRatio = pllRatio,
                        beatCount = beatCount,
                        isVocalActive = smoothVocalProb >= 0.48f,
                        neuralVocalProb = smoothVocalProb,
                        neuralBeatProb = neuralOut.beatOnsetProbability,
                        neuralMusicProb = smoothMusicProb,
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
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
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
                Log.w(TAG, "AudioRecord source $src failed", e)
                null
            }
            if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                return rec
            }
            rec?.release()
        }
        Log.e(TAG, "Unable to initialize any AudioRecord source")
        return null
    }

    /**
     * Cross-correlates the observed neural vocal probability envelope from the microphone
     * against the track's expected vocal phrasing across [-1200ms, +1200ms] to measure real drift.
     * Positive drift means the real audio is AHEAD of the current lyrics clock.
     */
    private fun estimateVocalEnvelopeDrift(
        observations: ArrayDeque<VocalObservation>,
        lines: List<LyricLine>,
    ): Pair<Long, Float> {
        val n = observations.size
        if (n < 30) return 0L to 0f

        var obsMean = 0f
        for (o in observations) obsMean += o.vocalProb
        obsMean /= n

        var obsVar = 0f
        for (o in observations) {
            val d = o.vocalProb - obsMean
            obsVar += d * d
        }
        // Require real vocal modulation (silences + singing) so correlation is meaningful
        if (obsVar / n < 0.008f) return 0L to 0f

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
                val dx = o.vocalProb - obsMean
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

        // Only report drift when correlation is genuinely strong and clearly improves over zero-lag
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
                // Higher weight inside active word spans, softer dip during inter-word pauses
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
