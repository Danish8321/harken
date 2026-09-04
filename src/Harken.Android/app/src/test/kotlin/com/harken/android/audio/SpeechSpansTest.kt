package com.harken.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSpansTest {

    private val rate = WavFormat.SampleRate

    /** Builds PCM from alternating (seconds, amplitude) pairs. */
    private fun audio(vararg parts: Pair<Int, Int>): ShortArray {
        val samples = ShortArray(parts.sumOf { it.first } * rate)
        var i = 0
        for ((seconds, amplitude) in parts) {
            repeat(seconds * rate) {
                samples[i] = amplitude.toShort()
                i++
            }
        }
        return samples
    }

    /**
     * The spans of a whole recording. Mirrors the window loop in OnDeviceTranscriber,
     * which reads one window of the WAV at a time rather than holding it all — the tests
     * below are about what the windows add up to, not about where they came from.
     */
    private fun spansOf(samples: ShortArray): List<SpeechSpan> {
        val windowSamples = rate * SpeechSpans.WindowSeconds
        val windowCount = (samples.size + windowSamples - 1) / windowSamples
        val silence = BooleanArray(windowCount) { window ->
            val from = window * windowSamples
            SpeechSpans.isWindowSilent(samples, from, minOf(from + windowSamples, samples.size))
        }
        return SpeechSpans.assemble(silence, samples.size)
    }

    @Test
    fun `a recording of nothing has nothing to transcribe`() {
        assertEquals(emptyList<SpeechSpan>(), spansOf(audio(300 to 0)))
    }

    @Test
    fun `room tone below the threshold is still nothing to transcribe`() {
        assertEquals(emptyList<SpeechSpan>(), spansOf(audio(60 to 300)))
    }

    @Test
    fun `speech throughout is decoded in one pass`() {
        val spans = spansOf(audio(30 to 4000))

        assertEquals(1, spans.size)
        assertEquals(0, spans[0].startSample)
        assertEquals(30 * rate, spans[0].endSampleExclusive)
    }

    @Test
    fun `a long silence between two speakers splits the work`() {
        val spans = spansOf(audio(5 to 4000, 60 to 0, 5 to 4000))

        assertEquals(2, spans.size)
        assertEquals(0, spans[0].startSample)
        assertEquals(6 * rate, spans[0].endSampleExclusive) // 5s of speech + 1s padding
        assertEquals(64 * rate, spans[1].startSample) // 1s of padding ahead of the speech
        assertEquals(70 * rate, spans[1].endSampleExclusive)
    }

    @Test
    fun `a pause for breath does not split a sentence`() {
        val spans = spansOf(audio(5 to 4000, 3 to 0, 5 to 4000))

        assertEquals(1, spans.size)
        assertEquals(0, spans[0].startSample)
        assertEquals(13 * rate, spans[0].endSampleExclusive)
    }

    @Test
    fun `silence at the head and tail is left out`() {
        val spans = spansOf(audio(30 to 0, 5 to 4000, 30 to 0))

        assertEquals(1, spans.size)
        assertEquals(29 * rate, spans[0].startSample)
        assertEquals(36 * rate, spans[0].endSampleExclusive)
    }

    @Test
    fun `padding cannot run off either end of the recording`() {
        val spans = spansOf(audio(2 to 4000))

        assertEquals(1, spans.size)
        assertEquals(0, spans[0].startSample)
        assertEquals(2 * rate, spans[0].endSampleExclusive)
    }

    @Test
    fun `a mostly empty recording costs a fraction of its length`() {
        val samples = audio(20 to 4000, 600 to 0, 20 to 4000)

        val decoded = spansOf(samples).sumOf { it.sampleCount }

        assertTrue("decoded $decoded of ${samples.size}", decoded < samples.size / 10)
    }

    @Test
    fun `spans never overlap`() {
        val spans = spansOf(audio(5 to 4000, 12 to 0, 5 to 4000, 12 to 0, 5 to 4000))

        assertEquals(3, spans.size)
        spans.zipWithNext { earlier, later ->
            assertTrue(earlier.endSampleExclusive <= later.startSample)
        }
    }

    @Test
    fun `unbroken speech is cut into spans that fit in memory`() {
        val seconds = SpeechSpans.MaxSpanSeconds + 100
        val spans = spansOf(audio(seconds to 4000))

        // Nothing is dropped and nothing is decoded twice: the cut is a cut, not a window.
        assertEquals(2, spans.size)
        assertEquals(0, spans[0].startSample)
        assertEquals(SpeechSpans.MaxSpanSeconds * rate, spans[0].endSampleExclusive)
        assertEquals(SpeechSpans.MaxSpanSeconds * rate, spans[1].startSample)
        assertEquals(seconds * rate, spans[1].endSampleExclusive)
        spans.forEach { assertTrue(it.sampleCount <= SpeechSpans.MaxSpanSeconds * rate) }
    }

    @Test
    fun `an empty recording has no spans`() {
        assertEquals(emptyList<SpeechSpan>(), spansOf(ShortArray(0)))
    }
}
