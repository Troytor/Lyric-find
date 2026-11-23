package com.example.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import com.example.model.AudioTelemetry
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
import kotlin.math.sqrt

private const val TAG = "AcousticBeatEngine"

/**
 * Live microphone acoustic analyzer using Android AudioRecord and a 16-band
 * resonator filterbank for real-time spectrum, beat onset detection, BPM estimation,
 * and vocal band energy tracking.
 */
class AcousticBeatEngine {

    private val sampleRate = 16_000
    private val frameSize = 512

    // 16 logarithmically spaced musical center frequencies from 55 Hz to 6400 Hz
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
    fun observeLiveAudio(referenceBpmProvider: () -> Float?): Flow<AudioTelemetry> = flow {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "AudioRecord invalid buffer size: $minBuf")
            return@flow
        }

        val bufferBytes = maxOf(minBuf, frameSize * 4)
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Microphone permission missing for AudioRecord", e)
            return@flow
        } catch (e: Exception) {
            Log.e(TAG, "Failed to construct AudioRecord", e)
            return@flow
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            Log.e(TAG, "AudioRecord failed to initialize")
            return@flow
        }

        val pcmBuffer = ShortArray(frameSize)
        val windowed = FloatArray(frameSize)
        val smoothBands = FloatArray(bandFrequenciesHz.size)
        val prevBassFluxHistory = FloatArray(28)
        var historyCursor = 0
        var prevLowEnergy = 0f
        var lastBeatAtMs = 0L
        var beatPulseDecay = 0f
        var beatCount = 0
        val beatIntervals = ArrayDeque<Long>()

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
                val normalizedAmp = (rms * 5.2f).coerceIn(0f, 1f)

                // Compute 16 musical band magnitudes with Goertzel recurrence
                for (b in bandCoeffs.indices) {
                    val mag = computeBandEnergy(windowed, bandCoeffs[b])
                    val scaled = (mag * 3.8f).coerceIn(0f, 1f)
                    val smoothing = if (scaled > smoothBands[b]) 0.55f else 0.18f
                    smoothBands[b] = smoothBands[b] + (scaled - smoothBands[b]) * smoothing
                }

                // Sub-bass + kick bands (indices 0..3: 55..160 Hz)
                val bassEnergy = ((smoothBands[0] + smoothBands[1] + smoothBands[2] + smoothBands[3]) / 4f)
                    .coerceIn(0f, 1f)
                // Vocal formant range (indices 5..12: 320..3200 Hz)
                var vocalAccum = 0f
                for (v in 5..12) vocalAccum += smoothBands[v]
                val vocalEnergy = (vocalAccum / 8f).coerceIn(0f, 1f)

                val positiveFlux = (bassEnergy - prevLowEnergy).coerceAtLeast(0f)
                prevLowEnergy = bassEnergy

                prevBassFluxHistory[historyCursor] = positiveFlux
                historyCursor = (historyCursor + 1) % prevBassFluxHistory.size

                val meanFlux = prevBassFluxHistory.average().toFloat()
                val threshold = maxOf(0.032f, meanFlux * 1.95f)
                val nowMs = SystemClock.elapsedRealtime()
                val elapsedSinceBeat = nowMs - lastBeatAtMs

                var firedBeat = false
                if (positiveFlux > threshold && bassEnergy > 0.08f && elapsedSinceBeat >= 285L) {
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

                val liveBpm = estimateBpm(beatIntervals, referenceBpmProvider())
                val refBpm = referenceBpmProvider()
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
                        isVocalActive = vocalEnergy > 0.14f,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Audio loop terminated unexpectedly", e)
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
            recorder.release()
        }
    }.flowOn(Dispatchers.Default)

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

        // Resolve double-time or half-time harmonic ambiguity against the track's reference BPM
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
            // Soft damped tracking so playback clock nudges smoothly without jitter
            1f + ((ratio - 1f) * 0.45f)
        } else {
            1f
        }
    }
}
