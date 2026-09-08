package com.harken.android.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * ARC-047: a plain `DELETE FROM sessions` left every segment behind — there is no
 * `@ForeignKey` cascade onto [SegmentRow]. [SessionDao.deleteSessionAndSegments] is the
 * fix; this proves it against real SQLite, not just that the Kotlin compiles.
 */
class SessionDeleteTest {
    private lateinit var db: HarkenDatabase
    private lateinit var dao: SessionDao

    private val sessionId = UUID.randomUUID()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, HarkenDatabase::class.java).build()
        dao = db.sessions()
        runBlocking {
            dao.insertLocalOnly(
                SessionRow(
                    id = sessionId,
                    startedAt = "2026-09-01T10:00:00Z",
                    endedAt = null,
                    segmentCount = 2,
                    transcriptionStatus = "Succeeded",
                    transcriptionFailureReason = null,
                    durationSeconds = 60,
                    localTitle = "Deleted later",
                ),
            )
            dao.replaceSegments(
                listOf(
                    SegmentRow(id = UUID.randomUUID(), sessionId = sessionId, offsetSeconds = 0, text = "first", voiceIndex = 0),
                    SegmentRow(id = UUID.randomUUID(), sessionId = sessionId, offsetSeconds = 5, text = "second", voiceIndex = 0),
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun deletingASessionDeletesItsSegmentsToo() =
        runBlocking {
            assertEquals(2, dao.segmentsOnce(sessionId).size)

            dao.deleteSessionAndSegments(sessionId)

            assertEquals(0, dao.segmentsOnce(sessionId).size)
            assertNull(dao.findById(sessionId))
        }
}
