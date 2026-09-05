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
     * Whisper's own window. A three-second span and a thirty-second span cost the same
     * decode, so a span shorter than this is grown into the audio around it rather than
     * decoded as it stands: the surrounding seconds are free, and they carry the context
     * that makes the words on either side of a pause decodable.
     *
     * Measured on a real meeting (AMI ES2002a): nineteen spans, eighteen of them 3–4
     * seconds, each still costing ~4.7 s of decode for its full 30-second window.
     */
    const val MinSpanSeconds = 30

    /**
     * The longest span handed to whisper in one call. Unbroken speech has no silence to
     * split on, so without this a three-hour recording of a talkative meeting is one span,
     * and its samples alone are 345 MB before the model is counted.
     *
     * Five minutes is 9.6 MB. Whisper decodes in 30-second windows regardless, so the only
     * cost of the cut is the context lost at the seam, and that is paid once per five
     * minutes rather than once per pause.
     */
    const val MaxSpanSeconds = 300

    /**
     * The quiet end of a recording, taken as its noise floor. A tenth of the windows sit
     * below this by definition, which is low enough to be floor in anything with pauses and
     * high enough not to be moved by a single click.
     */
    const val NoiseFloorPercentile = 10

    /** How far above its own noise floor a window has to sit to count as speech. */
    const val NoiseFloorFactor = 3

    /**
     * The bounds the noise-floor estimate is clamped between. Both are load-bearing.
     *
     * Without the ceiling, a recording that is *entirely* speech silences itself: its own
     * tenth percentile is speech, so three times that sits above everything and no window
     * qualifies. Measured against a 71-second all-speech fixture, which the unclamped rule
     * cut from 71 decoded seconds to 3.
     *
     * Without the floor, a recording of a quiet room decodes its own hiss.
     */
    const val MinAmplitudeThreshold = 60

    /**
     * Lives here because this is the only thing that still compares against it. It was
     * SilenceDetector.DefaultAmplitudeThreshold, the fixed level the recorder called
     * silence, until that turned out to count 97% of a real meeting as silence — see
     * [NoiseFloor]. As a ceiling on an estimate it is sound; as a threshold in its own
     * right it was the defect.
     */
    const val MaxAmplitudeThreshold = 500

    /**
     * The level a window has to reach, in this recording, to count as speech.
     *
     * One fixed threshold cannot serve both a phone lying on a table and a headset mix.
     * [MaxAmplitudeThreshold] is right for the second and far too high for the first: a
     * real four-person meeting (AMI ES2002a) has a median window RMS of 126, so nine
     * tenths of it read as silence and a tenth of the audio reached whisper.
     * Reading the floor off the recording itself recovers 88% of that meeting while
     * leaving every louder recording exactly where it already was.
     */
    fun amplitudeThreshold(windowRms: IntArray): Int {
        if (windowRms.isEmpty()) return MinAmplitudeThreshold
        val ordered = windowRms.sortedArray()
        val floorIndex = (ordered.size * NoiseFloorPercentile / 100).coerceAtMost(ordered.lastIndex)
        return (ordered[floorIndex] * NoiseFloorFactor)
            .coerceIn(MinAmplitudeThreshold, MaxAmplitudeThreshold)
    }

    /**
     * The spans worth transcribing, in order, never overlapping. Empty when the recording
     * holds no sound at all — there is nothing to transcribe, and asking anyway is what
     * invented the "you"s.
     *
     * Takes one RMS reading per [WindowSeconds] of [totalSamples] rather than the audio
     * itself, so the caller can measure a window at a time without holding the recording in
     * memory — see OnDeviceTranscriber, which streams a file it must never materialise.
     * The readings arrive as levels rather than as verdicts because the threshold is a
     * property of the whole recording, which no single window is in a position to know.
     */
    fun assemble(
        windowRms: IntArray,
        totalSamples: Int,
        sampleRate: Int = WavFormat.SampleRate,
    ): List<SpeechSpan> {
        require(sampleRate > 0) { "sampleRate must be positive" }
        if (totalSamples <= 0) return emptyList()

        val threshold = amplitudeThreshold(windowRms)
        val windowSamples = sampleRate * WindowSeconds
        val padding = sampleRate * PaddingSeconds
        val maxSilentGap = sampleRate * MinSkippableSilenceSeconds

        val spans = mutableListOf<SpeechSpan>()
        for (window in windowRms.indices) {
            if (windowRms[window] < threshold) continue

            val windowStart = window * windowSamples
            val windowEnd = minOf(windowStart + windowSamples, totalSamples)
            val last = spans.lastOrNull()
            // A gap shorter than MinSkippableSilenceSeconds is a pause, not a hole:
            // absorb it into the running span instead of paying for a second window.
            if (last != null && windowStart - last.endSampleExclusive < maxSilentGap) {
                spans[spans.lastIndex] = last.copy(endSampleExclusive = windowEnd)
            } else {
                spans += SpeechSpan(windowStart, windowEnd)
            }
        }

        return spans
            .map { span ->
                SpeechSpan(
                    startSample = (span.startSample - padding).coerceAtLeast(0),
                    endSampleExclusive = (span.endSampleExclusive + padding).coerceAtMost(totalSamples),
                )
            }
            .map { span -> span.grownTo(sampleRate * MinSpanSeconds, totalSamples) }
            .mergedWhereTheyTouch()
            .flatMap { span -> span.chunked(sampleRate * MaxSpanSeconds) }
    }

    /**
     * [this] widened to [minSamples], centred on the speech it already holds and clamped to
     * the recording. A recording shorter than [minSamples] is the one case that cannot
     * reach it, and then the whole recording is the span.
     */
    private fun SpeechSpan.grownTo(minSamples: Int, totalSamples: Int): SpeechSpan {
        if (sampleCount >= minSamples) return this

        val wanted = minOf(minSamples, totalSamples)
        val missing = wanted - sampleCount
        var start = startSample - missing / 2
        var end = endSampleExclusive + (missing - missing / 2)
        if (start < 0) {
            end -= start
            start = 0
        }
        if (end > totalSamples) {
            start = (start - (end - totalSamples)).coerceAtLeast(0)
            end = totalSamples
        }
        return SpeechSpan(start, end)
    }

    /** Growing spans can push two of them into each other, and overlapping spans decode twice. */
    private fun List<SpeechSpan>.mergedWhereTheyTouch(): List<SpeechSpan> =
        fold(mutableListOf()) { merged: MutableList<SpeechSpan>, span ->
            val last = merged.lastOrNull()
            if (last != null && span.startSample <= last.endSampleExclusive) {
                merged[merged.lastIndex] = last.copy(
                    endSampleExclusive = maxOf(last.endSampleExclusive, span.endSampleExclusive),
                )
            } else {
                merged += span
            }
            merged
        }

    /** [span] split into consecutive pieces no longer than [maxSamples]. */
    private fun SpeechSpan.chunked(maxSamples: Int): List<SpeechSpan> {
        if (sampleCount <= maxSamples) return listOf(this)

        val pieces = mutableListOf<SpeechSpan>()
        var start = startSample
        while (start < endSampleExclusive) {
            val end = minOf(start + maxSamples, endSampleExclusive)
            pieces += SpeechSpan(start, end)
            start = end
        }
        return pieces
    }

    /**
     * The RMS level of one window, measured the way [SilenceDetector] measures a chunk.
     * Truncated to a whole amplitude, which is finer than anything it is compared against.
     */
    fun windowRms(samples: ShortArray, from: Int, toExclusive: Int): Int {
        var sumOfSquares = 0L
        for (i in from until toExclusive) {
            val sample = samples[i].toLong()
            sumOfSquares += sample * sample
        }
        val count = (toExclusive - from).toLong()
        if (count == 0L) return 0
        return kotlin.math.sqrt(sumOfSquares.toDouble() / count).toInt()
    }
}
