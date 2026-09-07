package com.harken.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSpansTest {
    private val rate = WavFormat.SAMPLE_RATE

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
        val windowSamples = rate * SpeechSpans.WINDOW_SECONDS
        val windowCount = (samples.size + windowSamples - 1) / windowSamples
        val levels =
            IntArray(windowCount) { window ->
                val from = window * windowSamples
                SpeechSpans.windowRms(samples, from, minOf(from + windowSamples, samples.size))
            }
        return SpeechSpans.assemble(levels, samples.size)
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
        // Each speaker's five seconds is grown to a whisper window it was going to pay for
        // anyway, and the grown spans stay clear of each other.
        assertEquals(SpeechSpans.MIN_SPAN_SECONDS * rate, spans[0].sampleCount)
        assertEquals(0, spans[0].startSample)
        assertTrue(spans[1].startSample >= spans[0].endSampleExclusive)
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
        // Centred on the speech at 30-35 s, widened to whisper's window.
        assertEquals(SpeechSpans.MIN_SPAN_SECONDS * rate, spans[0].sampleCount)
        assertTrue(spans[0].startSample <= 29 * rate)
        assertTrue(spans[0].endSampleExclusive >= 36 * rate)
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
        val spans = spansOf(audio(5 to 4000, 60 to 0, 5 to 4000, 60 to 0, 5 to 4000))

        assertEquals(3, spans.size)
        spans.zipWithNext { earlier, later ->
            assertTrue(earlier.endSampleExclusive <= later.startSample)
        }
    }

    @Test
    fun `unbroken speech is cut into spans that fit in memory`() {
        val seconds = SpeechSpans.MAX_SPAN_SECONDS + 100
        val spans = spansOf(audio(seconds to 4000))

        // Nothing is dropped and nothing is decoded twice: the cut is a cut, not a window.
        assertEquals(2, spans.size)
        assertEquals(0, spans[0].startSample)
        assertEquals(SpeechSpans.MAX_SPAN_SECONDS * rate, spans[0].endSampleExclusive)
        assertEquals(SpeechSpans.MAX_SPAN_SECONDS * rate, spans[1].startSample)
        assertEquals(seconds * rate, spans[1].endSampleExclusive)
        spans.forEach { assertTrue(it.sampleCount <= SpeechSpans.MAX_SPAN_SECONDS * rate) }
    }

    @Test
    fun `an empty recording has no spans`() {
        assertEquals(emptyList<SpeechSpan>(), spansOf(ShortArray(0)))
    }

    @Test
    fun `a quiet recording is measured against its own noise floor`() {
        // A real meeting recorded at a distance: speech at an amplitude the app's fixed
        // threshold of 500 would have thrown away entirely.
        val spans = spansOf(audio(20 to 20, 20 to 200, 20 to 20))

        assertTrue("expected the quiet speech to be found", spans.isNotEmpty())
        assertTrue(spans.sumOf { it.sampleCount } >= 20 * rate)
    }

    @Test
    fun `a recording that is all speech does not silence itself`() {
        // The failure the ceiling exists to prevent: with no clamp, the tenth percentile of
        // an all-speech recording is speech, so three times it is above every window.
        val spans = spansOf(audio(40 to 3000))

        assertEquals(1, spans.size)
        assertEquals(40 * rate, spans[0].sampleCount)
    }

    @Test
    fun `the threshold sits between its bounds`() {
        val quiet = IntArray(100) { 5 }
        val loud = IntArray(100) { 9000 }
        val ordinary = IntArray(100) { if (it < 20) 50 else 900 }

        assertEquals(SpeechSpans.MIN_AMPLITUDE_THRESHOLD, SpeechSpans.amplitudeThreshold(quiet))
        assertEquals(SpeechSpans.MAX_AMPLITUDE_THRESHOLD, SpeechSpans.amplitudeThreshold(loud))
        assertEquals(50 * SpeechSpans.NOISE_FLOOR_FACTOR, SpeechSpans.amplitudeThreshold(ordinary))
    }

    @Test
    fun `no span is shorter than a whisper window unless the recording is`() {
        // Two seconds of speech either side of a long gap: neither is worth a window of its
        // own, and both are grown until they are — here far enough to meet and merge.
        val spans = spansOf(audio(30 to 0, 2 to 4000, 30 to 0, 2 to 4000))

        assertTrue(spans.isNotEmpty())
        spans.forEach {
            assertTrue(
                "span of ${it.sampleCount} samples",
                it.sampleCount >= SpeechSpans.MIN_SPAN_SECONDS * rate,
            )
        }
    }

    @Test
    fun `windowRms reads the level of a window`() {
        val samples = ShortArray(rate) { 1000 }

        assertEquals(1000, SpeechSpans.windowRms(samples, 0, samples.size))
        assertEquals(0, SpeechSpans.windowRms(samples, 0, 0))
    }
}
