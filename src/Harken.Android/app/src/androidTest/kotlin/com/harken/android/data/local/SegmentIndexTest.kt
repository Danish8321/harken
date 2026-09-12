package com.harken.android.data.local

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every query that reads a session's transcript filters `segments` by `sessionId`, and
 * `segments` is the table that grows without bound — one row per spoken line, of every
 * recording ever kept. Unindexed, each of those reads is a scan of all of them (ARC-057).
 *
 * The assertion is on SQLite's own plan rather than on a duration, because a timing here
 * would only prove that a test database with a handful of rows is small. `EXPLAIN QUERY
 * PLAN` says which strategy was chosen, which is the thing the index is for, and it says it
 * the same way on an empty database and on a full one.
 *
 * SQLite reports `SCAN <table>` for a full read and `SEARCH <table> USING INDEX <name>` for
 * an indexed one. No `ANALYZE` runs here, so the planner has no row statistics and picks
 * the index on shape alone — which is exactly what a fresh install's database also has.
 */
class SegmentIndexTest {
    private lateinit var db: HarkenDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, HarkenDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    /** The plan SQLite prints for [sql], joined into one line. */
    private fun planFor(sql: String): String =
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql").use { cursor ->
            buildString {
                while (cursor.moveToNext()) {
                    append(cursor.getString(cursor.getColumnIndexOrThrow("detail")))
                    append(" | ")
                }
            }
        }

    @Test
    fun readingOneSessionsSegmentsUsesTheIndexRatherThanScanningEveryTranscript() {
        // The body of observeSegments and segmentsOnce, which are the same statement.
        val plan = planFor("SELECT * FROM segments WHERE sessionId = 'x' ORDER BY offsetSeconds ASC")

        assertTrue(
            "reading one session's segments should use the sessionId index, but SQLite planned: $plan",
            plan.contains("SEARCH segments USING INDEX index_segments_sessionId"),
        )
    }

    @Test
    fun clearingOneSessionsSegmentsUsesTheIndexToo() {
        // Runs on every transcription completion and on every delete, so it is not a rare
        // path — and it is the one that cannot be seen in the UI when it is slow.
        val plan = planFor("DELETE FROM segments WHERE sessionId = 'x'")

        assertTrue(
            "clearing one session's segments should use the sessionId index, but SQLite planned: $plan",
            plan.contains("index_segments_sessionId"),
        )
    }

    /**
     * The deliberate exception. `searchSegments` is a `LIKE '%term%'`, which no index on any
     * column can satisfy, so its scan is the design (`SessionDao` records the trade and what
     * evidence would reverse it). Asserted so that adding the index above is not later
     * mistaken for having made search indexed as well.
     */
    @Test
    fun transcriptSearchStillScans() {
        val plan = planFor("SELECT sg.id FROM segments sg JOIN sessions s ON s.id = sg.sessionId WHERE sg.text LIKE '%x%'")

        assertTrue("transcript search is a LIKE scan by design, but SQLite planned: $plan", plan.contains("SCAN sg"))
    }
}
