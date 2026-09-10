package com.harken.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What an import does to the audio before it becomes a Recording (ADR-0016).
 *
 * The test that matters here is [`content above 8 kHz is attenuated, not folded down`].
 * Every other property below would also hold for the linear interpolation this
 * deliberately is not, so that one is the only evidence that the filter exists at all.
 */
class ResamplingTest {
    @Test
    fun `stereo is averaged, not halved and not left-only`() {
        // One voice louder on the left, one on the right — dropping a channel drops a speaker.
        val interleaved = shortArrayOf(1000, 0, 0, 1000, 500, 500)
        val out = ShortArray(3)

        assertEquals(3, Downmix.toMono(interleaved, interleaved.size, 2, out))
        assertArrayEquals(shortArrayOf(500, 500, 500), out)
    }

    @Test
    fun `mono is passed through untouched`() {
        val mono = shortArrayOf(1, -2, 3, -4)
        val out = ShortArray(4)

        assertEquals(4, Downmix.toMono(mono, mono.size, 1, out))
        assertArrayEquals(mono, out)
    }

    @Test
    fun `a source already at 16 kHz is not filtered`() {
        // Filtering it could only lose: there is nothing above Nyquist to fold.
        val input = sine(1000.0, WavFormat.SAMPLE_RATE, 4000)

        assertArrayEquals(input, resample(input, WavFormat.SAMPLE_RATE))
    }

    @Test
    fun `speech-band content survives the rate change`() {
        val output = resample(sine(1000.0, 44100, 44100), 44100)

        // A 1 kHz tone sits far inside the passband, so it should arrive at its own level.
        assertTrue("peak was ${peak(output)}", abs(peak(output) - AMPLITUDE) < AMPLITUDE * 0.05)
    }

    @Test
    fun `content above 8 kHz is attenuated, not folded down`() {
        // The whole reason this is a windowed sinc and not linear interpolation. Sampled at
        // 16 kHz, 12 kHz is indistinguishable from 4 kHz — so without a filter ahead of the
        // decimation this tone would arrive intact, sitting on top of the consonants, and
        // nothing downstream would ever report a problem.
        val input = sine(12000.0, 44100, 44100)
        val output = resample(input, 44100)

        val ratio = rms(output) / rms(input)
        assertTrue("survived at ${(ratio * 100).roundToInt()}% of its input level", ratio < 0.02)
    }

    @Test
    fun `a 48 kHz second becomes a 16 kHz second`() {
        val output = resample(sine(440.0, 48000, 48000), 48000)

        assertTrue("got ${output.size} samples", abs(output.size - WavFormat.SAMPLE_RATE) <= 2)
    }

    @Test
    fun `a source below 16 kHz is interpolated up`() {
        // A phone-call recording at 8 kHz: nothing to fold, everything it has is kept.
        val output = resample(sine(1000.0, 8000, 8000), 8000)

        assertTrue("got ${output.size} samples", abs(output.size - WavFormat.SAMPLE_RATE) <= 2)
        assertTrue("peak was ${peak(output)}", abs(peak(output) - AMPLITUDE) < AMPLITUDE * 0.05)
    }

    @Test
    fun `buffer boundaries do not change the result`() {
        // A decoder hands over one buffer at a time, and the filter needs samples from
        // either side of every seam. If history did not cross the boundary there would be a
        // click every few milliseconds — audible, and invisible to every other test here.
        val input = sine(1000.0, 44100, 44100)

        assertArrayEquals(resample(input, 44100), resample(input, 44100, chunk = 997))
    }

    private fun resample(
        input: ShortArray,
        sourceRate: Int,
        chunk: Int = input.size,
    ): ShortArray {
        val resampler = Resampler16k(sourceRate)
        val collected = ArrayList<Short>(input.size)
        var offset = 0
        while (offset < input.size) {
            val length = minOf(chunk, input.size - offset)
            val slice = input.copyOfRange(offset, offset + length)
            val out = ShortArray(resampler.maxOutputFor(length))
            val produced = resampler.process(slice, length, out)
            for (i in 0 until produced) collected.add(out[i])
            offset += length
        }
        val tail = ShortArray(resampler.maxDrainOutput())
        val drained = resampler.drain(tail)
        for (i in 0 until drained) collected.add(tail[i])
        return collected.toShortArray()
    }

    /** Ignores the filter's start and end transients, where the window runs off the audio. */
    private fun steady(samples: ShortArray): ShortArray =
        if (samples.size <= EDGE * 2) samples else samples.copyOfRange(EDGE, samples.size - EDGE)

    private fun peak(samples: ShortArray): Double = steady(samples).maxOf { abs(it.toInt()) }.toDouble()

    private fun rms(samples: ShortArray): Double {
        val body = steady(samples)
        if (body.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in body) sum += sample.toDouble() * sample.toDouble()
        return sqrt(sum / body.size)
    }

    private fun sine(
        frequencyHz: Double,
        sampleRate: Int,
        samples: Int,
    ): ShortArray = ShortArray(samples) { (AMPLITUDE * sin(2.0 * PI * frequencyHz * it / sampleRate)).roundToInt().toShort() }

    private companion object {
        const val AMPLITUDE = 10000.0

        /** Samples to ignore at each end — comfortably wider than the filter's half-width. */
        const val EDGE = 200
    }
}
