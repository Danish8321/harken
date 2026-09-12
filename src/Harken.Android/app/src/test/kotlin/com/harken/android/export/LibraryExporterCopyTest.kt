package com.harken.android.export

import com.harken.android.data.ExportItem
import com.harken.android.data.TranscriptText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * What an export leaves behind when a copy does not finish.
 *
 * [ExportService]'s own reason for existing is that "a directory of half-written recordings
 * that looks like a backup is worse than no backup, because they would not know" — and a
 * process death was the only way it guarded against. Cancelling is a button on its
 * notification, and an I/O failure needs no user at all (ARC-060).
 *
 * Runs against a destination in memory. The real one is [SafDestination], whose every call
 * is a `DocumentsContract` static — unmocked stubs on the JVM — which is why the copy loop,
 * the whole of the app's backup story, had no test before this.
 */
class LibraryExporterCopyTest {
    @get:Rule val temp = TemporaryFolder()

    /** Called before each write with the document's name and what it holds so far. */
    private class FakeDestination(
        private val beforeWrite: (name: String, writtenSoFar: Int) -> Unit = { _, _ -> },
    ) : ExportDestination {
        val documents = linkedMapOf<String, FakeDocument>()

        override fun create(
            mimeType: String,
            displayName: String,
        ): ExportDocument {
            val document = FakeDocument { written -> beforeWrite(displayName, written) }
            documents[displayName] = document
            return document
        }

        /** What is still in the folder when the export ends, in creation order. */
        fun surviving(): List<String> = documents.filterValues { !it.discarded }.keys.toList()

        fun only(extension: String): String = documents.keys.single { it.endsWith(extension) }

        fun bytesOf(name: String): Int = documents.getValue(name).sink.size()
    }

    private class FakeDocument(
        private val beforeWrite: (writtenSoFar: Int) -> Unit,
    ) : ExportDocument {
        val sink = ByteArrayOutputStream()
        var discarded = false
            private set

        override val stream: OutputStream =
            object : OutputStream() {
                override fun write(b: Int) {
                    beforeWrite(sink.size())
                    sink.write(b)
                }

                override fun write(
                    b: ByteArray,
                    off: Int,
                    len: Int,
                ) {
                    beforeWrite(sink.size())
                    sink.write(b, off, len)
                }
            }

        override fun discard() {
            discarded = true
        }

        override fun close() = Unit
    }

    private fun item(
        title: String,
        audio: File?,
        text: String = "hello",
    ) = ExportItem(
        title = title,
        startedAt = "2026-03-04T09:15:00Z",
        audioPath = audio?.path,
        lines = listOf(TranscriptText.Line(offsetSeconds = 0, text = text)),
    )

    private fun wav(
        name: String,
        bytes: Int,
    ): File = temp.newFile(name).apply { writeBytes(ByteArray(bytes) { it.toByte() }) }

    @Test
    fun `a copy that fails part way leaves nothing behind`() =
        runTest {
            val audio = wav("a.wav", 500_000)
            val destination =
                FakeDestination { name, written ->
                    if (name.endsWith(".wav") && written >= 128 * 1024) throw IOException("the destination filled up")
                }

            val report = LibraryExporter(destination).export(listOf(item("Meeting", audio))) { }

            assertEquals("a failed copy must not be counted as a backed-up recording", 0, report.audioFiles)
            assertEquals(1, report.failed)
            assertTrue(
                "a partial recording was left in the backup folder: ${destination.surviving()}",
                destination.surviving().none { it.endsWith(".wav") },
            )
            // The transcript is its own document and still belongs to the user.
            assertEquals(1, report.transcripts)
            assertEquals(listOf(destination.only(".txt")), destination.surviving())
        }

    @Test
    fun `cancelling mid-copy leaves nothing behind`() =
        runTest {
            val audio = wav("b.wav", 500_000)
            val job = Job()
            val destination =
                FakeDestination { name, written ->
                    // A Cancel tap landing inside the copy, which is where one lands on a
                    // recording big enough to be worth cancelling. The throw comes from the
                    // exporter's own ensureActive on the next pass, not from here.
                    if (name.endsWith(".wav") && written >= 128 * 1024) job.cancel()
                }

            val thrown =
                try {
                    withContext(job) {
                        LibraryExporter(destination).export(listOf(item("Standup", audio))) { }
                    }
                    null
                } catch (e: CancellationException) {
                    e
                }

            assertNotNull("the export ran to completion through a cancelled job", thrown)
            assertTrue(
                "cancelling left files behind: ${destination.surviving()}",
                destination.surviving().isEmpty(),
            )
        }

    @Test
    fun `a copy that finishes survives whole`() =
        runTest {
            val audio = wav("c.wav", 150_000)
            val destination = FakeDestination()

            val report = LibraryExporter(destination).export(listOf(item("Retro", audio))) { }

            assertEquals(1, report.audioFiles)
            assertEquals(0, report.failed)
            assertEquals(150_000, destination.bytesOf(destination.only(".wav")))
            assertEquals(2, destination.surviving().size)
        }

    @Test
    fun `the reported size counts the transcript's bytes, not its characters`() =
        runTest {
            // A transcript with an accent in it is longer as UTF-8 than as a String, and this
            // number is what the user is told they just backed up.
            val destination = FakeDestination()

            val report = LibraryExporter(destination).export(listOf(item("Réunion", audio = null, text = "café très chère"))) { }

            assertEquals(destination.bytesOf(destination.only(".txt")).toLong(), report.bytes)
        }
}
