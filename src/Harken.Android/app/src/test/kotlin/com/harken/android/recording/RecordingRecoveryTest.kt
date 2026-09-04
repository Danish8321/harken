package com.harken.android.recording

import com.harken.android.audio.WavFormat
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingRecoveryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun wav(id: UUID, seconds: Int, modifiedAtMs: Long = 1_757_000_000_000L): File {
        val bytesPerSecond = WavFormat.SampleRate * WavFormat.Channels * (WavFormat.BitsPerSample / 8)
        val file = folder.newFile("$id.wav")
        file.writeBytes(ByteArray(WavFormat.HeaderLength + seconds * bytesPerSecond))
        file.setLastModified(modifiedAtMs)
        return file
    }

    @Test
    fun `a wav with no session is an orphan, sized and dated from the file`() {
        val id = UUID.randomUUID()
        val endedAtMs = 1_757_000_000_000L
        wav(id, seconds = 90, modifiedAtMs = endedAtMs)

        val orphans = RecordingRecovery.orphanRecordings(folder.root.listFiles()!!.toList(), emptySet())

        assertEquals(1, orphans.size)
        assertEquals(id, orphans[0].id)
        assertEquals(90, orphans[0].durationSeconds)
        assertEquals(endedAtMs, orphans[0].endedAt.toEpochMilli())
        // Dated to when it was captured, not to when the app next opened.
        assertEquals(endedAtMs - 90_000, orphans[0].startedAt.toEpochMilli())
    }

    @Test
    fun `a wav that already has a session is left alone`() {
        val id = UUID.randomUUID()
        wav(id, seconds = 12)

        assertTrue(RecordingRecovery.orphanRecordings(folder.root.listFiles()!!.toList(), setOf(id)).isEmpty())
    }

    @Test
    fun `a header-only file captured nothing and is not resurrected`() {
        val id = UUID.randomUUID()
        wav(id, seconds = 0)

        assertTrue(RecordingRecovery.orphanRecordings(folder.root.listFiles()!!.toList(), emptySet()).isEmpty())
    }

    @Test
    fun `files that are not id-named wavs are ignored`() {
        folder.newFile("notes.txt").writeBytes(ByteArray(100_000))
        folder.newFile("ggml-base.en.bin").writeBytes(ByteArray(100_000))
        folder.newFile("scratch.wav").writeBytes(ByteArray(100_000))

        assertTrue(RecordingRecovery.orphanRecordings(folder.root.listFiles()!!.toList(), emptySet()).isEmpty())
    }

    @Test
    fun `orphans come back newest first`() {
        val older = UUID.randomUUID()
        val newer = UUID.randomUUID()
        wav(older, seconds = 5, modifiedAtMs = 1_757_000_000_000L)
        wav(newer, seconds = 5, modifiedAtMs = 1_757_000_500_000L)

        val orphans = RecordingRecovery.orphanRecordings(folder.root.listFiles()!!.toList(), emptySet())

        assertEquals(listOf(newer, older), orphans.map { it.id })
    }
}
