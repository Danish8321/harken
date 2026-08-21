package com.harken.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test

// Mirrors tests/Harken.Core.UnitTests/Audio/SilenceDetectorTests.cs's coverage of the
// byte-count-driven stop logic.
class SilenceDetectorTest {

    private fun silentChunk(bytes: Int) = ByteArray(bytes)

    private fun loudChunk(bytes: Int): ByteArray {
        val chunk = ByteArray(bytes)
        var i = 0
        while (i + 1 < bytes) {
            chunk[i] = 0xFF.toByte()
            chunk[i + 1] = 0x7F // 0x7FFF, above default threshold of 500
            i += 2
        }
        return chunk
    }

    @Test
    fun loudAudioNeverTriggersStop() {
        val detector = SilenceDetector(silenceTimeoutMs = 1000, sessionCapMs = 10_000)
        repeat(5) {
            assertEquals(RecordingStopReason.None, detector.add(loudChunk(3200), 0, 3200))
        }
    }

    @Test
    fun sustainedSilenceTriggersSilenceTimeout() {
        // bytesPerMs = 32 for 16kHz/16-bit/mono, so 1000ms of silence = 32000 bytes.
        val detector = SilenceDetector(silenceTimeoutMs = 1000, sessionCapMs = 60_000)
        val chunk = silentChunk(16000)
        assertEquals(RecordingStopReason.None, detector.add(chunk, 0, chunk.size))
        assertEquals(RecordingStopReason.SilenceTimeout, detector.add(chunk, 0, chunk.size))
    }

    @Test
    fun sessionCapWinsOverSilenceOnTheSameChunk() {
        val detector = SilenceDetector(silenceTimeoutMs = 1000, sessionCapMs = 1000)
        val chunk = silentChunk(32000)
        assertEquals(RecordingStopReason.SessionCap, detector.add(chunk, 0, chunk.size))
    }

    @Test
    fun nonSilentChunkResetsTheSilenceRun() {
        val detector = SilenceDetector(silenceTimeoutMs = 1000, sessionCapMs = 60_000)
        val silent = silentChunk(16000)
        val loud = loudChunk(16000)
        assertEquals(RecordingStopReason.None, detector.add(silent, 0, silent.size))
        assertEquals(RecordingStopReason.None, detector.add(loud, 0, loud.size))
        assertEquals(RecordingStopReason.None, detector.add(silent, 0, silent.size))
    }
}
