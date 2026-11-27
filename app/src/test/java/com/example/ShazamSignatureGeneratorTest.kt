package com.example

import android.util.Base64
import com.example.engine.ShazamSignatureGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
class ShazamSignatureGeneratorTest {

    @Test
    fun generatesValidShazamSignaturePacketWithCrcAndPeaks() {
        val sampleRate = 16_000
        val durationSec = 4
        val pcm = ShortArray(sampleRate * durationSec) { i ->
            val t = i.toDouble() / sampleRate
            // Multi-band harmonic burst (440 Hz, 880 Hz, 2100 Hz) with rhythmic envelope
            val env = 0.5 + 0.5 * sin(2.0 * PI * 2.5 * t)
            val signal = env * (
                0.45 * sin(2.0 * PI * 440.0 * t) +
                    0.35 * sin(2.0 * PI * 980.0 * t) +
                    0.25 * sin(2.0 * PI * 2150.0 * t)
                )
            (signal * 24_000.0).toInt().coerceIn(-32_768, 32_767).toShort()
        }

        val generator = ShazamSignatureGenerator()
        val result = generator.generateFromPcm16(pcm)

        assertTrue(result.dataUri.startsWith("data:audio/vnd.shazam.sig;base64,"))
        assertEquals(4_000, result.sampleDurationMs)
        assertTrue("Expected spectral peaks from multi-tone signal", result.peakCount > 0)

        val b64 = result.dataUri.substringAfter("base64,")
        val raw = Base64.decode(b64, Base64.DEFAULT)
        assertTrue(raw.size > 56)

        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xcafe2580.toInt(), buf.getInt(0))
        val storedCrc = buf.getInt(4)
        assertEquals(raw.size - 48, buf.getInt(8))
        assertEquals(0x94119c00.toInt(), buf.getInt(12))
        assertEquals(3 shl 27, buf.getInt(28))
        assertEquals(0x40000000, buf.getInt(48))

        val crc = CRC32()
        crc.update(raw, 8, raw.size - 8)
        assertEquals(crc.value.toInt(), storedCrc)
    }
}
