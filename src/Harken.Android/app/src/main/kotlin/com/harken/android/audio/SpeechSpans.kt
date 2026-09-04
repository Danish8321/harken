package com.harken.android.audio

/** A stretch of a recording worth decoding, as sample indices into the PCM. */
data class SpeechSpan(val startSample: Int, val endSampleExclusive: Int) {
    val sampleCount: Int get() = endSampleExclusive - startSample
}

/**
 * Splits a recording into the stretches that actually contain sound, so long silences are
 * never handed to whisper.
 *
 * Measured on a Nothing Phone 2: five minutes of pure silence took ~13.5 minutes of wall
 * clock and ~75 minutes of CPU to "transcribe", and produced eleven segments of the word
 * "you" attributed to two voices. Whisper has nothing to say about silence but still pays
 * full price for it, and its temperature-fallback ladder re-decodes each empty window
 * several times before giving up.
 *
 * Silence is judged by the same RMS rule and threshold [SilenceDetector] uses to end a
 * forgotten recording, so what the recorder calls silence and what the transcriber skips
 * cannot drift apart.
 *
 * Only long silences are cut. Whisper bills by the 30-second window regardless of what is
 * in it, so splitting on every conversational pause would trade one window for two and
 * sever sentences across the seam.
 */
object SpeechSpans {
    /** Silence is judged a second at a time — long enough to average out a single click. */
    const val WindowSeconds = 1

    /**
     * The shortest silence worth cutting. Below this, splitting costs an extra whisper
     * window and risks cutting a speaker off mid-sentence; above it, the silence is
     * plainly a gap in the recording rather than a pause in speech.
     */
    const val MinSkippableSilenceSeconds = 10

    /**
     * Kept on each side of a span, because the window that holds a word's onset is often
     * quiet enough to read as silence on its own and whisper decodes better with a run-up.
     */
    const val PaddingSeconds = 1

    /**
     * The spans of [samples] worth transcribing, in order, never overlapping. Empty when
     * the recording holds no sound at all — there is nothing to transcribe, and asking
     * anyway is what invented the "you"s.
     */
    fun find(
        samples: ShortArray,
        sampleRate: Int = WavFormat.SampleRate,
        amplitudeThreshold: Int = SilenceDetector.DefaultAmplitudeThreshold,
    ): List<SpeechSpan> {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(amplitudeThreshold >= 0) { "amplitudeThreshold must not be negative" }
        if (samples.isEmpty()) return emptyList()

        val windowSamples = sampleRate * WindowSeconds
        val padding = sampleRate * PaddingSeconds
        val maxSilentGap = sampleRate * MinSkippableSilenceSeconds

        val spans = mutableListOf<SpeechSpan>()
        var windowStart = 0
        while (windowStart < samples.size) {
            val windowEnd = minOf(windowStart + windowSamples, samples.size)
            if (!isSilent(samples, windowStart, windowEnd, amplitudeThreshold)) {
                val last = spans.lastOrNull()
                // A gap shorter than MinSkippableSilenceSeconds is a pause, not a hole:
                // absorb it into the running span instead of paying for a second window.
                if (last != null && windowStart - last.endSampleExclusive < maxSilentGap) {
                    spans[spans.lastIndex] = last.copy(endSampleExclusive = windowEnd)
                } else {
                    spans += SpeechSpan(windowStart, windowEnd)
                }
            }
            windowStart = windowEnd
        }

        return spans.map { span ->
            SpeechSpan(
                startSample = (span.startSample - padding).coerceAtLeast(0),
                endSampleExclusive = (span.endSampleExclusive + padding).coerceAtMost(samples.size),
            )
        }
    }

    /**
     * Mean square against the squared threshold — the same comparison [SilenceDetector]
     * makes, without the sqrt. The loudest possible window sums 32768² per sample, which
     * stays inside Long for any window this app produces.
     */
    private fun isSilent(samples: ShortArray, from: Int, toExclusive: Int, amplitudeThreshold: Int): Boolean {
        var sumOfSquares = 0L
        for (i in from until toExclusive) {
            val sample = samples[i].toLong()
            sumOfSquares += sample * sample
        }
        val count = (toExclusive - from).toLong()
        val threshold = amplitudeThreshold.toLong()
        return count == 0L || sumOfSquares < threshold * threshold * count
    }
}
