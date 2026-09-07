package com.harken.android.audio

/**
 * The level a chunk has to reach, in this recording, to count as speech.
 *
 * A fixed level cannot answer the question. Measured on real audio: an empty room with
 * a phone on the desk runs at a chunk RMS of ~289, while a four-person meeting captured
 * on a headset mix runs at ~64, because the mix is near-digital-silence between words.
 * The empty room is the *louder* signal, so no threshold separates "someone is talking"
 * from "everyone left" — and the shipped fixed 500 counted 97% of a real meeting as
 * silence and stopped it halfway through.
 *
 * What separates them is structure, not level: over that meeting the ratio of the 90th
 * percentile to the 10th is 77, and over the empty room it is 4.4. So speech is measured
 * as a peak above the recording's own recent floor.
 *
 * This is the same shape as [SpeechSpans.amplitudeThreshold] — read the floor off the
 * recording, multiply, clamp — with the comparison inverted, because the two tiers ask
 * different questions. The transcriber asks "is this second worth decoding?"; the
 * recorder asks "has anyone spoken recently?"
 *
 * Byte-driven like the [SilenceDetector] that owns it, so the window is 60 seconds of
 * audio whether chunks arrive on schedule or late.
 */
class NoiseFloor(
    private val windowBytes: Int = WINDOW_BYTES,
) {
    init {
        require(windowBytes > 0) { "windowBytes must be positive" }
    }

    // Levels, and the bytes each one stood for. The window holds ~375 entries at the
    // 160 ms chunks AudioRecordCapture delivers, so the sort in speechThreshold runs over
    // a few hundred ints, six times a second.
    //
    // Eviction is by bytes, so the window is 60 seconds of audio however chunks are sized;
    // the percentile is then taken over entries rather than weighted by their bytes, which
    // is exact while chunks are uniform and off by at most one entry when they are not.
    private val levels = ArrayDeque<Int>()
    private val sizes = ArrayDeque<Int>()
    private var bytesHeld = 0

    // The last floor computed, or -1 if the window has changed since. The floor moves by
    // at most one entry per chunk but was re-derived — copy the window, sort it — by every
    // read of either getter below, several times a second for the length of the recording
    // (ARC-011). One chunk in, at most one sort.
    private var cachedFloor = -1

    /** Records one chunk's RMS [chunkRms], which stood for [bytes] of audio. */
    fun observe(
        chunkRms: Int,
        bytes: Int,
    ) {
        if (bytes <= 0) return
        levels.addLast(chunkRms)
        sizes.addLast(bytes)
        bytesHeld += bytes
        // Evict whole chunks from the far end until the window fits. A chunk is the
        // smallest thing that has a level, so it is also the smallest thing that can be
        // forgotten.
        while (bytesHeld > windowBytes && levels.size > 1) {
            levels.removeFirst()
            bytesHeld -= sizes.removeFirst()
        }
        cachedFloor = -1
    }

    /**
     * The percentile of the window that stands for "what this recording sounds like when
     * nobody is talking". Memoised: the answer only changes when [observe] changes the
     * window.
     */
    private fun floor(): Int {
        if (cachedFloor >= 0) return cachedFloor
        if (levels.isEmpty()) return 0
        val ordered = levels.toIntArray()
        ordered.sort()
        val index = (ordered.size * SpeechSpans.NOISE_FLOOR_PERCENTILE / 100).coerceAtMost(ordered.lastIndex)
        cachedFloor = ordered[index]
        return cachedFloor
    }

    /**
     * The level a chunk must reach to be speech, given what has been heard recently.
     *
     * Both clamps are load-bearing, and each fixes a case the other cannot. Without the
     * ceiling, a loud room raises the bar to a multiple of a large floor and a quiet
     * speaker never registers — the recording then stops in the middle of a meeting,
     * which is the failure that loses audio. Without the floor, a recording of digital
     * silence has a floor of zero, every chunk is speech, and a phone left running in a
     * bag never times out.
     */
    val speechThreshold: Int
        get() = (floor() * SPEECH_FACTOR).coerceIn(MIN_SPEECH_THRESHOLD, MAX_SPEECH_THRESHOLD)

    /** The current estimate of the floor itself, for telemetry. */
    val estimate: Int get() = floor()

    companion object {
        /**
         * How much recent audio the floor is read from.
         *
         * Long enough to span the gaps in ordinary conversation, short enough to forget a
         * meeting once it ends — which is what lets an abandoned recording time out at
         * all. Measured over the AMI meeting and a 7-minute empty room, 30, 60, 120 and
         * 300 seconds all give the right answer on both; 60 is the middle of that range.
         *
         * Reading the floor from the whole recording instead does *not* work: the near
         * silence between a meeting's words pins the floor near zero for hours afterwards,
         * so the room never rises above it and the timeout never fires again.
         */
        const val WINDOW_SECONDS = 60
        const val WINDOW_BYTES =
            WavFormat.SAMPLE_RATE * WavFormat.CHANNELS * (WavFormat.BITS_PER_SAMPLE / 8) * WINDOW_SECONDS

        /**
         * How far above its own floor a chunk has to sit to be speech.
         *
         * Measured against a live meeting that must not stop and an empty room that must,
         * 8 through 20 both hold; below 8 the empty room takes too long to time out. 12 is
         * the middle of what passed.
         */
        const val SPEECH_FACTOR = 12

        /**
         * A chunk this loud is speech whatever the floor says, and nothing quieter than
         * [MIN_SPEECH_THRESHOLD] is speech however quiet the room.
         *
         * The ceiling is the tightest value that still lets an empty room time out: at 500
         * — the level this detector used to compare against directly — enough of an
         * ordinary room clears the bar that the timeout never fires at all.
         */
        const val MAX_SPEECH_THRESHOLD = 1000
        const val MIN_SPEECH_THRESHOLD = SpeechSpans.MIN_AMPLITUDE_THRESHOLD
    }
}
