package com.harken.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16Test {
    /** [samples] little-endian 16-bit samples, all at [amplitude]. */
    private fun tone(
        samples: Int,
        amplitude: Int,
    ): ByteArray {
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            bytes[i * 2] = (amplitude and 0xFF).toByte()
            bytes[i * 2 + 1] = ((amplitude shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `silence is zero`() {
        assertEquals(0, Pcm16.rms(ByteArray(320), 0, 320))
    }

    @Test
    fun `a constant tone reads back at its own amplitude`() {
        assertEquals(300, Pcm16.rms(tone(160, 300), 0, 320))
    }

    @Test
    fun `only the window it is given is read`() {
        // The capture buffer is reused and only partly filled, so a level read over the
        // whole array would be measuring the previous chunk's tail (ARC-013).
        val buffer = tone(200, 1000)
        java.util.Arrays.fill(buffer, 100, 400, 0.toByte())

        assertEquals(1000, Pcm16.rms(buffer, 0, 100))
        assertEquals(0, Pcm16.rms(buffer, 100, 300))
    }

    @Test
    fun `a trailing odd byte is ignored rather than read as a loud sample`() {
        assertEquals(0, Pcm16.rms(ByteArray(9), 0, 9))
    }

    @Test
    fun `an empty window is silent, not a crash`() {
        assertEquals(0, Pcm16.rms(ByteArray(0), 0, 0))
        assertEquals(0, Pcm16.rms(ByteArray(2), 0, 1))
    }

    @Test
    fun `the meter reads full scale at full scale and nowhere above it`() {
        assertEquals(0f, Pcm16.normalized(0), 0f)
        assertEquals(1f, Pcm16.normalized(Short.MAX_VALUE.toInt()), 0f)
        assertEquals(1f, Pcm16.normalized(Int.MAX_VALUE), 0f)
        assertTrue(Pcm16.normalized(3277) in 0.09f..0.11f)
    }
}
