package com.harken.android.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * Transcription runs in this process and nowhere else, so a row still marked as running
 * at process start belongs to a process that died mid-run. HarkenApp settles those rows;
 * this covers the query that does it, including the rows it must leave alone.
 */
class InterruptedTranscriptionRecoveryTest {

    private lateinit var db: HarkenDatabase
    private val dao get() = db.sessions()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, HarkenDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun session(status: String, audioPath: String? = null) = SessionRow(
        id = UUID.randomUUID(),
        startedAt = "2026-01-01T09:00:00Z",
        endedAt = null,
        source = "Mobile",
        segmentCount = 0,
        hasSummary = false,
        transcriptionStatus = status,
        transcriptionFailureReason = null,
        durationSeconds = null,
        pendingUploadPath = audioPath,
        isLocalOnly = true,
    )

    private suspend fun row(id: UUID): SessionRow = dao.observeSession(id).first()!!

    @Test
    fun aSessionLeftRunningByADeadProcessIsSettledWithAReason() = runBlocking {
        val running = session("Running")
        dao.insertLocalOnly(running)

        val recovered = dao.failInterruptedTranscriptions("Transcribing stopped when the app closed.")

        assertEquals(1, recovered)
        assertEquals("Failed", row(running.id).transcriptionStatus)
        assertEquals("Transcribing stopped when the app closed.", row(running.id).transcriptionFailureReason)
    }

    @Test
    fun aPendingSessionIsSettledToo() = runBlocking {
        val pending = session("Pending")
        dao.insertLocalOnly(pending)

        assertEquals(1, dao.failInterruptedTranscriptions("interrupted"))
        assertEquals("Failed", row(pending.id).transcriptionStatus)
    }

    @Test
    fun sessionsThatWereNotTranscribingAreLeftAlone() = runBlocking {
        val recorded = session("Recorded")
        val succeeded = session("Succeeded")
        dao.insertLocalOnly(recorded)
        dao.insertLocalOnly(succeeded)

        assertEquals(0, dao.failInterruptedTranscriptions("interrupted"))

        assertEquals("Recorded", row(recorded.id).transcriptionStatus)
        assertEquals("Succeeded", row(succeeded.id).transcriptionStatus)
        assertNull(row(recorded.id).transcriptionFailureReason)
    }

    @Test
    fun theAudioPathSurvivesSoTheUserCanRetry() = runBlocking {
        val path = "/data/user/0/com.harken.android/files/rec.wav"
        val running = session("Running", audioPath = path)
        dao.insertLocalOnly(running)

        dao.failInterruptedTranscriptions("interrupted")

        assertEquals(path, row(running.id).pendingUploadPath)
    }
}
