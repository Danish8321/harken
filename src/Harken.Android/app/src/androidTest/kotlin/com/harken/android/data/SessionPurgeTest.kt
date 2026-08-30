package com.harken.android.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.harken.android.data.local.HarkenDatabase
import com.harken.android.data.local.SegmentRow
import com.harken.android.data.local.SessionRow
import com.harken.android.data.local.SummaryRow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Delete tells the user "the transcript and its segments are removed from this phone".
 * Neither segments nor summaries declare a foreign key, so nothing cascades, and the WAV
 * is a file the database knows nothing about — every part of that promise has to be kept
 * explicitly.
 */
class SessionPurgeTest {

    private lateinit var db: HarkenDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, HarkenDatabase::class.java).build()
        repository = SessionRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(audio: File): UUID {
        val id = UUID.randomUUID()
        db.sessions().insertLocalOnly(
            SessionRow(
                id = id,
                startedAt = "2026-01-01T09:00:00Z",
                endedAt = null,
                source = "Microphone",
                segmentCount = 1,
                hasSummary = true,
                transcriptionStatus = "Succeeded",
                transcriptionFailureReason = null,
                durationSeconds = 12,
                pendingUploadPath = audio.absolutePath,
                isLocalOnly = true,
            ),
        )
        db.sessions().replaceSegments(
            listOf(SegmentRow(UUID.randomUUID(), id, 0, "hello", 0)),
        )
        db.sessions().replaceSummary(SummaryRow(id, "a summary", "2026-01-01T09:05:00Z"))
        return id
    }

    @Test
    fun deletingARecordingTakesItsSegmentsSummaryAndAudioWithIt() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File(context.cacheDir, "purge-test.wav").apply { writeText("fake wav") }
        val id = seed(audio)

        assertTrue(repository.purge(id).isSuccess)

        assertNull(db.sessions().findSession(id))
        assertTrue(db.sessions().observeSegments(id).first().isEmpty())
        assertNull(db.sessions().observeSummary(id).first())
        assertFalse("the audio file must not survive the recording", audio.exists())
    }

    @Test
    fun deletingARecordingLeavesOtherRecordingsAlone() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val doomed = File(context.cacheDir, "purge-doomed.wav").apply { writeText("fake wav") }
        val kept = File(context.cacheDir, "purge-kept.wav").apply { writeText("fake wav") }
        val doomedId = seed(doomed)
        val keptId = seed(kept)

        repository.purge(doomedId)

        assertNull(db.sessions().findSession(doomedId))
        assertTrue(db.sessions().observeSegments(keptId).first().isNotEmpty())
        assertTrue(kept.exists())
        kept.delete()
    }

    @Test
    fun aRecordingWhoseAudioIsAlreadyGoneStillDeletes() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val audio = File(context.cacheDir, "purge-missing.wav")
        val id = seed(audio)

        assertTrue(repository.purge(id).isSuccess)
        assertNull(db.sessions().findSession(id))
    }
}
