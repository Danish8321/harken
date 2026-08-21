package com.harken.android.audio

import kotlin.math.abs

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
        silentBytes = if (isSilent(pcm, offset, length)) silentBytes + length else 0

        if (toDuration(totalBytes) >= sessionCapMs) return RecordingStopReason.SessionCap
        if (toDuration(silentBytes) >= silenceTimeoutMs) return RecordingStopReason.SilenceTimeout
        return RecordingStopReason.None
    }

    private fun isSilent(pcm: ByteArray, offset: Int, length: Int): Boolean {
        var i = offset
        while (i + 1 < offset + length) {
            val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
            val magnitude = if (sample.toInt() == Short.MIN_VALUE.toInt()) {
                -Short.MIN_VALUE.toInt()
            } else {
                abs(sample.toInt())
            }
            if (magnitude > amplitudeThreshold) return false
            i += 2
        }
        return true
    }

    private fun toDuration(bytes: Long): Long = (bytes / bytesPerMs).toLong()

    companion object {
        const val DefaultAmplitudeThreshold = 500
    }
}
