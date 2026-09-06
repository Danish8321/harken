package com.harken.android.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.harken.android.data.SearchQuery
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The search queries run against real SQLite, which is the only place LIKE's wildcards and
 * ESCAPE clause actually mean anything — a JVM test can check how the pattern is built
 * (SearchQueryTest) but not how SQLite reads it.
 */
class SessionSearchTest {

    private lateinit var db: HarkenDatabase
    private lateinit var dao: SessionDao

    private val sessionA = UUID.randomUUID()
    private val sessionB = UUID.randomUUID()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, HarkenDatabase::class.java).build()
        dao = db.sessions()
        runBlocking {
            dao.insertLocalOnly(session(sessionA, startedAt = "2026-09-01T10:00:00Z", title = "Quarterly review"))
            dao.insertLocalOnly(session(sessionB, startedAt = "2026-09-02T10:00:00Z", title = null))
            dao.replaceSegments(
                listOf(
                    segment(sessionA, 0, "The budget is approved for the quarter"),
                    segment(sessionA, 12, "Nothing else on the agenda"),
                    segment(sessionB, 5, "Revenue is up 50% on last year"),
                    segment(sessionB, 30, "Budget again, in a different recording"),
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun matchesAreCaseInsensitiveAndOrderedNewestSessionFirst() = runBlocking {
        val matches = dao.searchSegments(SearchQuery.likePattern("budget"), 400)

        assertEquals(2, matches.size)
        // sessionB started a day later, so its match comes first.
        assertEquals(sessionB, matches[0].sessionId)
        assertEquals(30, matches[0].offsetSeconds)
        assertEquals(sessionA, matches[1].sessionId)
    }

    @Test
    fun aPercentInTheTermIsALiteralPercent() = runBlocking {
        val literal = dao.searchSegments(SearchQuery.likePattern("50%"), 400)
        assertEquals(1, literal.size)
        assertTrue(literal[0].text.contains("50%"))

        // Unescaped, "50%" would be the wildcard pattern "%50%%" and match the same row —
        // the escape is only observable on a term that would otherwise match MORE. "e%r"
        // is a wildcard match for "Revenue is up 50% on last year" and a literal match for
        // nothing at all.
        assertEquals(0, dao.searchSegments(SearchQuery.likePattern("e%r"), 400).size)
    }

    @Test
    fun anUnderscoreIsALiteralUnderscore() = runBlocking {
        // "_udget" as a wildcard matches "budget"; escaped, it matches nothing.
        assertEquals(0, dao.searchSegments(SearchQuery.likePattern("_udget"), 400).size)
    }

    @Test
    fun theLimitCapsWhatOneSearchReads() = runBlocking {
        assertEquals(1, dao.searchSegments(SearchQuery.likePattern("budget"), 1).size)
    }

    @Test
    fun onlyTypedTitlesAreSearched() = runBlocking {
        val titles = dao.searchTitles(SearchQuery.likePattern("quarterly"), 50)
        assertEquals(1, titles.size)
        assertEquals(sessionA, titles[0].id)
    }

    @Test
    fun sessionsByIdsReturnsOnlyWhatWasAskedFor() = runBlocking {
        val rows = dao.sessionsByIds(listOf(sessionB))
        assertEquals(1, rows.size)
        assertEquals(sessionB, rows[0].id)
    }

    private fun session(id: UUID, startedAt: String, title: String?) = SessionRow(
        id = id,
        startedAt = startedAt,
        endedAt = null,
        source = "Device",
        segmentCount = 2,
        hasSummary = false,
        transcriptionStatus = "Succeeded",
        transcriptionFailureReason = null,
        durationSeconds = 60,
        localTitle = title,
        isLocalOnly = true,
    )

    private fun segment(sessionId: UUID, offsetSeconds: Int, text: String) = SegmentRow(
        id = UUID.randomUUID(),
        sessionId = sessionId,
        offsetSeconds = offsetSeconds,
        text = text,
        voiceIndex = 0,
    )
}
