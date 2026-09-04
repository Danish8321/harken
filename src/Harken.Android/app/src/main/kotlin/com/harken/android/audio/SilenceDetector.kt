package com.harken.android.audio

// Ports src/Harken.Core/Audio/SilenceDetector.cs — byte-count-driven (not wall-clock),
// so behavior is identical whether pcm chunks arrive on schedule or late.
enum class RecordingStopReason { None, SilenceTimeout, SessionCap }

class SilenceDetector(
    private val silenceTimeoutMs: Long,
    private val sessionCapMs: Long,
    private val amplitudeThreshold: Int = DefaultAmplitudeThreshold,
) {
    init {
        require(silenceTimeoutMs > 0) { "silenceTimeoutMs must be positive" }
        require(sessionCapMs > 0) { "sessionCapMs must be positive" }
        require(amplitudeThreshold >= 0) { "amplitudeThreshold must not be negative" }
    }

    private var totalBytes: Long = 0
    private var silentBytes: Long = 0

    private val bytesPerMs: Double =
        (WavFormat.SampleRate * WavFormat.Channels * (WavFormat.BitsPerSample / 8)) / 1000.0

    fun add(pcm: ByteArray, offset: Int, length: Int): RecordingStopReason {
        totalBytes += length
        silentBytes = if (isSilent(pcm, offset, length)) {
            silentBytes + length.toLong()
        } else {
            (silentBytes - length.toLong() * AudibleDecayFactor).coerceAtLeast(0L)
        }

        if (toDuration(totalBytes) >= sessionCapMs) return RecordingStopReason.SessionCap
        if (toDuration(silentBytes) >= silenceTimeoutMs) return RecordingStopReason.SilenceTimeout
        return RecordingStopReason.None
    }

    /**
     * The level of the chunk, not its loudest sample.
     *
     * Reading the peak let one click speak for a whole chunk: measured on a Nothing Phone 2,
     * chunk peak cleared 500 in 86% of a 7m25s capture of an ordinary room, holding the
     * longest quiet run to 1.4s against the 300s the timeout needs. Chunk RMS over the same
     * audio ran p50 299.
     *
     * Compared as mean square against the squared threshold — no sqrt, and no overflow: the
     * loudest possible chunk sums 32768² per sample, which stays inside Long.
     */
    private fun isSilent(pcm: ByteArray, offset: Int, length: Int): Boolean {
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
        val threshold = amplitudeThreshold.toLong()
        return samples == 0L || sumOfSquares < threshold * threshold * samples
    }

    private fun toDuration(bytes: Long): Long = (bytes / bytesPerMs).toLong()

    companion object {
        /** RMS level of a chunk below which it counts as silence. */
        const val DefaultAmplitudeThreshold = 500

        /**
         * How much accumulated silence one unit of audible audio burns off.
         *
         * An audible chunk used to zero the run outright, which is why the timeout could
         * not fire in a real room: isolated clicks — a desk knock, a chair — kept
         * restarting it. Decaying keeps a 160ms transient cheap (1.6s of run) while a
         * second of speech still clears the run entirely.
         */
        const val AudibleDecayFactor = 10
    }
}
