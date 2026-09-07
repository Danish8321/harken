package com.harken.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The recorder, the recovery pass and the transcriber all ask the file how long it is
 * (ARC-009). One implementation, so one test.
 */
class WavDurationTest {
    @Test
    fun `duration is the audio byte count, header excluded`() {
        // 10 seconds of 16 kHz mono 16-bit PCM, plus the 44-byte header.
        assertEquals(10, WavFormat.durationSeconds(wavOf(WavFormat.BYTES_PER_SECOND * 10)))
    }

    @Test
    fun `a partial second rounds down rather than up`() {
        assertEquals(3, WavFormat.durationSeconds(wavOf(WavFormat.BYTES_PER_SECOND * 3 + 1)))
    }

    @Test
    fun `a header-only file is zero, not negative`() {
        // What a capture that started and recorded nothing leaves behind.
        assertEquals(0, WavFormat.durationSeconds(wavOf(0)))
    }

    @Test
    fun `a truncated file is zero, not negative`() {
        // A process death can leave fewer bytes on disk than the header claims.
        assertEquals(0, WavFormat.durationSeconds(wavOf(-20)))
    }

    @Test
    fun `a missing file is zero`() {
        assertEquals(0, WavFormat.durationSeconds(File("/does/not/exist.wav")))
    }

    private fun wavOf(audioBytes: Int): File =
        File.createTempFile("harken", ".wav").apply {
            deleteOnExit()
            writeBytes(ByteArray((WavFormat.HEADER_LENGTH + audioBytes).coerceAtLeast(0)))
        }
}
