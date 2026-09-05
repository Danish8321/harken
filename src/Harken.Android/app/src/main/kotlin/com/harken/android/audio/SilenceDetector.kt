package com.harken.android.audio

// Byte-count-driven (not wall-clock), so behavior is identical whether pcm chunks arrive
// on schedule or late.
enum class RecordingStopReason { None, SilenceTimeout, SessionCap }

class SilenceDetector(
    private val silenceTimeoutMs: Long,
    private val sessionCapMs: Long,
    private val noiseFloor: NoiseFloor = NoiseFloor(),
) {
    init {
        require(silenceTimeoutMs > 0) { "silenceTimeoutMs must be positive" }
        require(sessionCapMs > 0) { "sessionCapMs must be positive" }
    }

    private var totalBytes: Long = 0
    private var silentBytes: Long = 0

    /** The high-water mark of the silence run, for telemetry — see [peakSilentMs]. */
    private var peakSilentBytes: Long = 0

    private val bytesPerMs: Double =
        (WavFormat.SampleRate * WavFormat.Channels * (WavFormat.BitsPerSample / 8)) / 1000.0

    fun add(pcm: ByteArray, offset: Int, length: Int): RecordingStopReason {
        totalBytes += length
        noiseFloor.observe(chunkRms(pcm, offset, length), length)

        silentBytes = if (isSilent(pcm, offset, length)) {
            silentBytes + length.toLong()
        } else {
            (silentBytes - length.toLong() * AudibleDecayFactor).coerceAtLeast(0L)
        }
        if (silentBytes > peakSilentBytes) peakSilentBytes = silentBytes

        if (toDuration(totalBytes) >= sessionCapMs) return RecordingStopReason.SessionCap
        if (toDuration(silentBytes) >= silenceTimeoutMs) return RecordingStopReason.SilenceTimeout
        return RecordingStopReason.None
    }

    /** The level this recording currently has to clear to count as speech. */
    val speechThreshold: Int get() = noiseFloor.speechThreshold

    /** The recording's own noise floor, as currently estimated. */
    val noiseFloorEstimate: Int get() = noiseFloor.estimate

    /**
     * The longest the silence run ever got. Reported when a recording ends, because "it
     * stopped in the middle of my meeting" and "it recorded an empty room for three hours"
     * are the same event with this number at opposite ends, and neither is answerable from
     * the stop reason alone.
     */
    val peakSilentMs: Long get() = toDuration(peakSilentBytes)

    /**
     * Whether nobody has spoken in this chunk.
     *
     * Silence is the absence of speech, not the absence of sound. A fixed level cannot
     * express that: measured on real recordings, an empty room with the phone on a desk
     * sits at a chunk RMS of ~289 while a four-person meeting on a headset mix sits at
     * ~64. Comparing either against a constant answers the wrong question, and the
     * constant that shipped (500) counted 97% of that meeting as silence and would have
     * stopped it at 617 seconds of 1273.
     *
     * So a chunk is speech when it rises well above what this recording has sounded like
     * recently — see [NoiseFloor], which owns that judgement and its constants.
     */
    private fun isSilent(pcm: ByteArray, offset: Int, length: Int): Boolean =
        chunkRms(pcm, offset, length) < noiseFloor.speechThreshold

    /**
     * The level of the chunk, not its loudest sample.
     *
     * Reading the peak let one click speak for a whole chunk: measured on a Nothing Phone
     * 2, chunk peak cleared 500 in 86% of a 7m25s capture of an ordinary room, holding the
     * longest quiet run to 1.4s against the 300s the timeout needs.
     *
     * The mean square is taken in Long — the loudest possible chunk sums 32768² per sample
     * and stays inside it — and rooted once per chunk, six times a second at the 160 ms
     * chunks the capture delivers. The root is what makes the value comparable to a floor
     * read off other chunks.
     */
    private fun chunkRms(pcm: ByteArray, offset: Int, length: Int): Int {
        var sumOfSquares = 0L
        var samples = 0L
        var i = offset
        // Whole 16-bit little-endian samples only. A trailing odd byte cannot be read as a
        // sample and is ignored rather than misread as a loud one.
        while (i + 1 < offset + length) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toLong()
            sumOfSquares += sample * sample
            samples++
            i += 2
        }
        if (samples == 0L) return 0
        return kotlin.math.sqrt(sumOfSquares.toDouble() / samples).toInt()
    }

    private fun toDuration(bytes: Long): Long = (bytes / bytesPerMs).toLong()

    companion object {
        /**
         * How much accumulated silence one unit of audible audio burns off.
         *
         * An audible chunk used to zero the run outright, which is why the timeout could
         * not fire in a real room: isolated clicks — a desk knock, a chair — kept
         * restarting it. Decaying keeps a 160ms transient cheap (1.6s of run) while a
         * second of speech still clears the run entirely.
         *
         * Note what this costs: the run only grows while more than 90.9% of chunks are
         * silent, which is why the threshold above it has to be right about what silence
         * is. A threshold that calls 83% of an empty room silent produces no timeout at
         * all, however long the room is left recording.
         */
        const val AudibleDecayFactor = 10
    }
}
