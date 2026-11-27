package com.example.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

private const val TAG = "AudioClipRecorder"

data class RecordedAudioClip(
    val pcmSamples: ShortArray,
    val wavBytes: ByteArray,
    val durationMs: Long,
    val recordedEndElapsedMs: Long,
    val peakRms: Float,
)

/**
 * Captures 16 kHz mono 16-bit PCM audio directly from the Android microphone for
 * real-time acoustic fingerprinting while streaming live amplitude and 16-band spectrum.
 */
class AudioClipRecorder {

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

    @SuppressLint("MissingPermission")
    suspend fun recordWavClip(
        durationMs: Long = 4_800L,
        prefixSamples: ShortArray = ShortArray(0),
        onLiveFrame: (amplitude: Float, spectrum: List<Float>, progress: Float) -> Unit,
    ): RecordedAudioClip? = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.e(TAG, "Invalid AudioRecord min buffer size: $minBuf")
            return@withContext null
        }

        val bufferBytes = maxOf(minBuf, frameSize * 4)
        val recorder = openRecorder(bufferBytes) ?: return@withContext null

        val targetSamples = ((sampleRate * durationMs) / 1_000L).toInt()
        val totalExpectedSamples = prefixSamples.size + targetSamples
        val allSamples = ShortArray(totalExpectedSamples)
        if (prefixSamples.isNotEmpty()) {
            System.arraycopy(prefixSamples, 0, allSamples, 0, prefixSamples.size)
        }

        val pcmStream = ByteArrayOutputStream(totalExpectedSamples * 2)
        if (prefixSamples.isNotEmpty()) {
            val prefixBytes = ByteArray(prefixSamples.size * 2)
            for (i in prefixSamples.indices) {
                val s = prefixSamples[i].toInt()
                prefixBytes[i * 2] = (s and 0xFF).toByte()
                prefixBytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            pcmStream.write(prefixBytes)
        }

        val shortBuf = ShortArray(frameSize)
        val floatBuf = FloatArray(frameSize)
        val byteScratch = ByteArray(frameSize * 2)
        val smoothBands = FloatArray(bandFrequenciesHz.size)
        var capturedNewSamples = 0
        var peakRms = 0f

        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive && capturedNewSamples < targetSamples) {
                val remaining = targetSamples - capturedNewSamples
                val toRead = minOf(frameSize, remaining)
                val read = recorder.read(shortBuf, 0, toRead)
                if (read <= 0) continue

                var sumSq = 0f
                val writeOffset = prefixSamples.size + capturedNewSamples
                for (i in 0 until read) {
                    val s = shortBuf[i]
                    allSamples[writeOffset + i] = s
                    val norm = s / 32768f
                    sumSq += norm * norm
                    floatBuf[i] = norm
                    byteScratch[i * 2] = (s.toInt() and 0xFF).toByte()
                    byteScratch[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
                }
                if (read < frameSize) {
                    for (i in read until frameSize) floatBuf[i] = 0f
                }

                pcmStream.write(byteScratch, 0, read * 2)
                capturedNewSamples += read

                val rms = sqrt(sumSq / read).coerceIn(0f, 1f)
                if (rms > peakRms) peakRms = rms
                val displayAmp = (rms * 5.5f).coerceIn(0f, 1f)

                for (b in bandCoeffs.indices) {
                    val mag = computeBandEnergy(floatBuf, bandCoeffs[b])
                    val scaled = (mag * 4.2f).coerceIn(0f, 1f)
                    smoothBands[b] = smoothBands[b] * 0.65f + scaled * 0.35f
                }

                val progress = (capturedNewSamples.toFloat() / targetSamples.toFloat()).coerceIn(0f, 1f)
                onLiveFrame(displayAmp, smoothBands.toList(), progress)
            }

            val finalTotal = prefixSamples.size + capturedNewSamples
            if (finalTotal < sampleRate) {
                return@withContext null
            }

            val trimmedPcm = if (finalTotal == allSamples.size) {
                allSamples
            } else {
                allSamples.copyOf(finalTotal)
            }
            val pcmBytes = pcmStream.toByteArray()
            val wavBytes = wrapPcmAsWav(pcmBytes, sampleRate = sampleRate, channels = 1, bitsPerSample = 16)
            RecordedAudioClip(
                pcmSamples = trimmedPcm,
                wavBytes = wavBytes,
                durationMs = (finalTotal * 1_000L) / sampleRate,
                recordedEndElapsedMs = SystemClock.elapsedRealtime(),
                peakRms = peakRms,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed recording audio clip", e)
            null
        } finally {
            runCatching {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            }
            recorder.release()
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecorder(bufferBytes: Int): AudioRecord? {
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
            add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            add(MediaRecorder.AudioSource.MIC)
        }

        for (source in candidates) {
            val rec = try {
                AudioRecord(
                    source,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing RECORD_AUDIO permission", e)
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

    private fun wrapPcmAsWav(
        pcmData: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = (channels * bitsPerSample / 8).toShort()
        val dataSize = pcmData.size
        val chunkSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(chunkSize)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataSize)
        }.array()

        val result = ByteArray(44 + dataSize)
        System.arraycopy(header, 0, result, 0, 44)
        System.arraycopy(pcmData, 0, result, 44, dataSize)
        return result
    }
}
