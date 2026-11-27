package com.example.engine

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

data class AcousticSignatureResult(
    val dataUri: String,
    val sampleDurationMs: Int,
    val peakCount: Int,
)

private data class SpectralPeak(
    val passIndex: Int,
    val magnitudeCode: Int,
    val freqBinCode: Int,
)

/**
 * Generates compact 16 kHz spectral constellation signatures compatible with
 * the open Shazam discovery v5 acoustic database (zero API key required).
 */
class ShazamSignatureGenerator {

    private val sampleRate = 16_000
    private val fftSize = 2_048
    private val hopSize = 128
    private val halfBins = (fftSize / 2) + 1 // 1025 bins
    private val ringCapacity = 256
    private val maxPeaksPerBand = 255

    private val hannWindow = DoubleArray(fftSize) { idx ->
        0.5 * (1.0 - cos(2.0 * PI * (idx + 1).toDouble() / (fftSize + 1).toDouble()))
    }

    // Precomputed twiddle tables for fast 2048-point iterative complex FFT
    private val cosTable = DoubleArray(fftSize / 2) { k -> cos(-2.0 * PI * k / fftSize) }
    private val sinTable = DoubleArray(fftSize / 2) { k -> sin(-2.0 * PI * k / fftSize) }
    private val bitRev = IntArray(fftSize) { i ->
        var x = i
        var rev = 0
        repeat(11) { // log2(2048) = 11
            rev = (rev shl 1) or (x and 1)
            x = x ushr 1
        }
        rev
    }

    fun generateFromPcm16(pcmSamples: ShortArray): AcousticSignatureResult {
        val sampleCount = pcmSamples.size
        val durationMs = ((sampleCount.toLong() * 1_000L) / sampleRate).toInt().coerceAtLeast(1)

        val bandPeaks = Array(4) { ArrayList<SpectralPeak>(128) }
        if (sampleCount < fftSize) {
            return buildEncodedSignature(sampleCount, durationMs, bandPeaks)
        }

        val rawRing = Array(ringCapacity) { DoubleArray(halfBins) }
        val spreadRing = Array(ringCapacity) { DoubleArray(halfBins) }
        val realBuf = DoubleArray(fftSize)
        val imagBuf = DoubleArray(fftSize)
        val spreadWork = DoubleArray(halfBins)

        var frameCount = 0
        var offset = 0
        while (offset + fftSize <= sampleCount) {
            // Window current 2048-sample slice (unnormalized PCM16 amplitude matches reference scaling)
            for (i in 0 until fftSize) {
                val dest = bitRev[i]
                realBuf[dest] = pcmSamples[offset + i].toDouble() * hannWindow[i]
                imagBuf[dest] = 0.0
            }

            runInPlaceFft(realBuf, imagBuf)

            val slot = frameCount and (ringCapacity - 1)
            val rawOut = rawRing[slot]
            for (k in 0 until halfBins) {
                val re = realBuf[k]
                val im = imagBuf[k]
                val scaled = ((re * re) + (im * im)) / 131_072.0
                rawOut[k] = if (scaled < 1e-10) 1e-10 else scaled
            }

            // Local frequency spreading across adjacent 3 bins
            System.arraycopy(rawOut, 0, spreadWork, 0, halfBins)
            for (k in 0 until (halfBins - 2)) {
                var mx = spreadWork[k]
                if (spreadWork[k + 1] > mx) mx = spreadWork[k + 1]
                if (spreadWork[k + 2] > mx) mx = spreadWork[k + 2]
                spreadWork[k] = mx
            }

            // Store current spread and propagate maxima backward across prior frames (-2, -4, -7)
            val curSpread = spreadRing[slot]
            System.arraycopy(spreadWork, 0, curSpread, 0, halfBins)
            for (back in intArrayOf(2, 4, 7)) {
                if (frameCount >= back) {
                    val prevSpread = spreadRing[(frameCount - back) and (ringCapacity - 1)]
                    for (k in 0 until halfBins) {
                        if (spreadWork[k] > prevSpread[k]) {
                            prevSpread[k] = spreadWork[k]
                        }
                    }
                }
            }

            // Extract local constellation peaks once enough look-ahead frames exist
            if (frameCount >= 46) {
                val candPass = frameCount - 46
                val candRaw = rawRing[candPass and (ringCapacity - 1)]
                val refSpread = spreadRing[(candPass - 3) and (ringCapacity - 1)]

                for (bin in 10 until 1019) {
                    val centerMag = candRaw[bin]
                    if (centerMag <= (1.0 / 64.0)) continue
                    if (centerMag <= refSpread[bin]) continue
                    if (centerMag <= refSpread[bin - 1] || centerMag <= refSpread[bin + 1]) continue
                    if (centerMag <= candRaw[bin - 1] || centerMag <= candRaw[bin + 1]) continue

                    val logPrev = ln(max(1e-10, candRaw[bin - 1])) * 1477.3 + 6144.0
                    val logCurr = ln(max(1e-10, centerMag)) * 1477.3 + 6144.0
                    val logNext = ln(max(1e-10, candRaw[bin + 1])) * 1477.3 + 6144.0

                    val curvature = (2.0 * logCurr) - logPrev - logNext
                    if (curvature <= 0.0) continue

                    val fineShift = ((logNext - logPrev) * 32.0) / curvature
                    val correctedFreqBin = (bin * 64 + fineShift.toInt()).coerceIn(0, 65_535)
                    val magnitudeCode = logCurr.toInt().coerceIn(0, 65_535)

                    val freqHz = correctedFreqBin * (sampleRate.toDouble() / (fftSize.toDouble() * 64.0))
                    val bandIndex = when {
                        freqHz in 250.0..<520.0 -> 0
                        freqHz in 520.0..<1450.0 -> 1
                        freqHz in 1450.0..<3500.0 -> 2
                        freqHz in 3500.0..5500.0 -> 3
                        else -> -1
                    }
                    if (bandIndex >= 0 && bandPeaks[bandIndex].size < maxPeaksPerBand) {
                        bandPeaks[bandIndex].add(
                            SpectralPeak(
                                passIndex = candPass,
                                magnitudeCode = magnitudeCode,
                                freqBinCode = correctedFreqBin,
                            ),
                        )
                    }
                }
            }

            frameCount++
            offset += hopSize
        }

        return buildEncodedSignature(sampleCount, durationMs, bandPeaks)
    }

    private fun runInPlaceFft(re: DoubleArray, im: DoubleArray) {
        var len = 2
        while (len <= fftSize) {
            val half = len ushr 1
            val step = fftSize / len
            var base = 0
            while (base < fftSize) {
                var twIdx = 0
                for (j in 0 until half) {
                    val wr = cosTable[twIdx]
                    val wi = sinTable[twIdx]
                    val uIdx = base + j
                    val vIdx = uIdx + half

                    val vr = re[vIdx]
                    val vi = im[vIdx]
                    val tr = (wr * vr) - (wi * vi)
                    val ti = (wr * vi) + (wi * vr)

                    re[vIdx] = re[uIdx] - tr
                    im[vIdx] = im[uIdx] - ti
                    re[uIdx] = re[uIdx] + tr
                    im[uIdx] = im[uIdx] + ti

                    twIdx += step
                }
                base += len
            }
            len = len shl 1
        }
    }

    private fun buildEncodedSignature(
        sampleCount: Int,
        durationMs: Int,
        bandPeaks: Array<ArrayList<SpectralPeak>>,
    ): AcousticSignatureResult {
        val innerStream = ByteArrayOutputStream()
        var totalPeaks = 0

        for (bandIdx in 0 until 4) {
            val peaks = bandPeaks[bandIdx]
            if (peaks.isEmpty()) continue
            totalPeaks += peaks.size

            val payload = ByteArrayOutputStream()
            var prevPass = 0
            for (peak in peaks) {
                val delta = peak.passIndex - prevPass
                if (delta >= 255) {
                    payload.write(0xFF)
                    writeIntLE(payload, peak.passIndex)
                } else {
                    payload.write(delta.coerceAtLeast(0))
                }
                prevPass = peak.passIndex
                writeShortLE(payload, peak.magnitudeCode)
                writeShortLE(payload, peak.freqBinCode)
            }

            val rawBandBytes = payload.toByteArray()
            val bandTypeId = 0x60030040 + bandIdx
            writeIntLE(innerStream, bandTypeId)
            writeIntLE(innerStream, rawBandBytes.size)
            innerStream.write(rawBandBytes)

            val pad = (4 - (rawBandBytes.size % 4)) % 4
            repeat(pad) { innerStream.write(0) }
        }

        val innerBytes = innerStream.toByteArray()
        val bodySize = 8 + innerBytes.size // outer TLV header (8 bytes) + inner chunks
        val totalSize = 48 + bodySize

        val packet = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)
        packet.putInt(0xcafe2580.toInt()) // magic1
        packet.putInt(0) // placeholder for CRC32
        packet.putInt(bodySize) // size minus 48-byte header
        packet.putInt(0x94119c00.toInt()) // magic2
        packet.putInt(0)
        packet.putInt(0)
        packet.putInt(0)
        packet.putInt(3 shl 27) // sampleRateId = 3 (16,000 Hz)
        packet.putInt(0)
        packet.putInt(0)
        packet.putInt(sampleCount + (sampleRate * 0.24).toInt())
        packet.putInt((15 shl 19) + 0x40000) // fixed protocol marker

        // Outer container TLV
        packet.putInt(0x40000000)
        packet.putInt(innerBytes.size)
        packet.put(innerBytes)

        val bytes = packet.array()
        val crc = CRC32()
        crc.update(bytes, 8, bytes.size - 8)
        val crcValue = crc.value.toInt()
        ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crcValue)

        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return AcousticSignatureResult(
            dataUri = "data:audio/vnd.shazam.sig;base64,$encoded",
            sampleDurationMs = durationMs,
            peakCount = totalPeaks,
        )
    }

    private fun writeIntLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }

    private fun writeShortLE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }
}
