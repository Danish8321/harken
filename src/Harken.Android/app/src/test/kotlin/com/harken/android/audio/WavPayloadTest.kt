package com.harken.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The WAV payload reader that feeds the on-device transcriber. It reads real files here,
 * written by the real [WavWriter], because the defects this code has had were all about
 * byte arithmetic — where a span starts, how many bytes a partial block holds — and a fake
 * file would have been written with the same arithmetic the reader uses.
 */
class WavPayloadTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `sample count is the payload, not the file`() {
        val file = wavOf("counted.wav", ShortArray(1000) { it.toShort() })

        RandomAccessFile(file, "r").use { raf ->
            // 44 bytes of header and two bytes per sample, neither of them counted as audio.
            assertEquals(2044L, file.length())
            assertEquals(1000, WavPayload.sampleCount(raf))
        }
    }

    @Test
    fun `a recording that captured nothing has no samples`() {
        val file = wavOf("empty.wav", ShortArray(0))

        RandomAccessFile(file, "r").use { raf ->
            assertEquals(0, WavPayload.sampleCount(raf))
        }
    }

    @Test
    fun `a file too short to hold a header is not a negative number of samples`() {
        val file = File(temp.root, "truncated.wav")
        file.writeBytes(ByteArray(10))

        RandomAccessFile(file, "r").use { raf ->
            assertEquals(0, WavPayload.sampleCount(raf))
        }
    }

    @Test
    fun `a span is read back exactly, across more than one block`() {
        // 40000 samples is 80000 bytes — more than the 64 KiB read block, so the loop runs
        // twice and its second pass starts from a byte offset it has to work out itself.
        val written = ShortArray(40_000) { (it % 2000 - 1000).toShort() }
        val file = wavOf("span.wav", written)

        val read =
            RandomAccessFile(file, "r").use { raf ->
                WavPayload.samples(raf, startSample = 500, count = 39_000)
            }

        assertArrayEquals(written.copyOfRange(500, 39_500), read)
    }

    @Test
    fun `the last block of a span is read to its own length`() {
        // A count that is not a whole number of 64 KiB blocks: the final readFully must ask
        // for what is left, not for a full block off the end of the file.
        val written = ShortArray(33_000) { (it % 700).toShort() }
        val file = wavOf("partial.wav", written)

        val read =
            RandomAccessFile(file, "r").use { raf ->
                WavPayload.samples(raf, startSample = 0, count = 33_000)
            }

        assertArrayEquals(written, read)
    }

    @Test
    fun `one level per window, and a loud window is told from a quiet one`() {
        val windowSamples = WavFormat.SAMPLE_RATE * SpeechSpans.WINDOW_SECONDS
        val samples =
            ShortArray(windowSamples * 2) { index ->
                if (index < windowSamples) 0 else 10_000
            }
        val file = wavOf("levels.wav", samples)

        val levels =
            RandomAccessFile(file, "r").use { raf ->
                WavPayload.windowRms(raf, WavPayload.sampleCount(raf))
            }

        assertEquals(2, levels.size)
        assertEquals(0, levels[0])
        assertEquals(10_000, levels[1])
    }

    @Test
    fun `a recording that ends mid-window is measured on what is there`() {
        val windowSamples = WavFormat.SAMPLE_RATE * SpeechSpans.WINDOW_SECONDS
        // One full window of silence, then a quarter window of sound. The tail is its own
        // window, measured over its own 4000 samples — not diluted by the silence that a
        // whole-window read would have pulled in behind it.
        val samples = ShortArray(windowSamples + windowSamples / 4) { index -> if (index < windowSamples) 0 else 8000 }
        val file = wavOf("tail.wav", samples)

        val levels =
            RandomAccessFile(file, "r").use { raf ->
                WavPayload.windowRms(raf, WavPayload.sampleCount(raf))
            }

        assertEquals(2, levels.size)
        assertEquals(8000, levels[1])
    }

    private fun wavOf(
        name: String,
        samples: ShortArray,
    ): File {
        val file = File(temp.root, name)
        val bytes = ByteArray(samples.size * 2)
        ByteBuffer
            .wrap(bytes)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer()
            .put(samples)
        WavWriter(RandomAccessFile(file, "rw")).use { it.write(bytes, 0, bytes.size) }
        return file
    }
}
