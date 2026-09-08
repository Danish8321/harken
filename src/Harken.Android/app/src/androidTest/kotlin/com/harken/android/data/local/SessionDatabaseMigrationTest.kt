package com.harken.android.data.local

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A migration applied to a database that already holds real recordings must not lose any
 * of them. Version 2 met that by only adding. Version 3 drops four columns and renames a
 * fifth, so "additive" is no longer the rule being checked — the rule is that every row,
 * and every value in a column that survives, is still there afterwards.
 *
 * This runs against the schema Room itself exported (`app/schemas`), not a `CREATE TABLE`
 * typed out here by hand. That is the whole point of the rewrite in ARC-039: a hand-built
 * table asserts what the author believed the old version looked like, so it agrees with
 * the migration and disagrees with the phone. `runMigrationsAndValidate` also checks the
 * result against the *new* schema, which catches a migration that runs cleanly and still
 * leaves a table Room will refuse to open.
 */
@RunWith(AndroidJUnit4::class)
class SessionDatabaseMigrationTest {
    @get:Rule
    val helper =
        MigrationTestHelper(
            InstrumentationRegistry.getInstrumentation(),
            HarkenDatabase::class.java,
        )

    @Test
    fun migration1To2AddsIsLocalOnlyColumnAndPreservesExistingRows() {
        val existingId = "11111111-1111-1111-1111-111111111111"

        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO sessions (
                    id, startedAt, endedAt, source, segmentCount, hasSummary,
                    transcriptionStatus, transcriptionFailureReason, durationSeconds,
                    localTitle, localTags, pendingUploadPath, syncedAt
                ) VALUES ('$existingId', '2026-01-01T00:00:00Z', NULL, 'Mobile', 3, 0,
                    'Succeeded', NULL, 42, 'Board review', 'work', NULL, 0)
                """.trimIndent(),
            )
        }

        // validateDroppedTables = true: a migration that quietly loses a table is the
        // failure this whole tier exists to catch.
        val migrated = helper.runMigrationsAndValidate(DB_NAME, 2, true, MIGRATION_1_2)

        migrated
            .query(
                "SELECT segmentCount, localTitle, localTags, isLocalOnly FROM sessions WHERE id = ?",
                arrayOf(existingId),
            ).use { cursor ->
                assertTrue("the row written at version 1 should survive the migration", cursor.moveToFirst())
                assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("segmentCount")))
                // The two local-only columns matter most: they hold what the user typed, and
                // nothing else on the device has a copy of them.
                assertEquals("Board review", cursor.getString(cursor.getColumnIndexOrThrow("localTitle")))
                assertEquals("work", cursor.getString(cursor.getColumnIndexOrThrow("localTags")))
                assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isLocalOnly")))
            }
        migrated.close()
    }

    /**
     * The one that can destroy data. `pendingUploadPath` holds the absolute path of a
     * recording's WAV and nothing else on the device holds a second copy of it, so a
     * migration that creates `audioPath` without carrying the old values across leaves
     * every existing recording pointing at nothing (ARC-015). This asserts the value
     * arrives, not merely that a column by that name exists.
     */
    @Test
    fun migration2To3CarriesEveryRecordingsAudioPathIntoTheRenamedColumn() {
        val withAudio = "22222222-2222-2222-2222-222222222222"
        val withoutAudio = "33333333-3333-3333-3333-333333333333"
        val path = "/data/user/0/com.harken.android/files/recordings/22222222.wav"

        helper.createDatabase(DB_NAME, 2).use { db ->
            db.execSQL(
                """
                INSERT INTO sessions (
                    id, startedAt, endedAt, source, segmentCount, hasSummary,
                    transcriptionStatus, transcriptionFailureReason, durationSeconds,
                    localTitle, localTags, pendingUploadPath, syncedAt, isLocalOnly
                ) VALUES ('$withAudio', '2026-02-02T00:00:00Z', NULL, 'Microphone', 7, 0,
                    'Succeeded', NULL, 315, 'Site walkthrough', 'work,field', '$path', 99, 1)
                """.trimIndent(),
            )
            // A row whose audio has already been deleted: null must stay null rather than
            // becoming the empty string, because the delete path tests it with `?.`.
            db.execSQL(
                """
                INSERT INTO sessions (
                    id, startedAt, endedAt, source, segmentCount, hasSummary,
                    transcriptionStatus, transcriptionFailureReason, durationSeconds,
                    localTitle, localTags, pendingUploadPath, syncedAt, isLocalOnly
                ) VALUES ('$withoutAudio', '2026-02-03T00:00:00Z', NULL, 'Microphone', 0, 0,
                    'Failed', 'No speech found', NULL, NULL, '', NULL, 0, 1)
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 3, true, MIGRATION_2_3)

        migrated
            .query(
                "SELECT audioPath, localTitle, localTags, segmentCount FROM sessions WHERE id = ?",
                arrayOf(withAudio),
            ).use { cursor ->
                assertTrue("the recording written at version 2 should survive the rebuild", cursor.moveToFirst())
                assertEquals(path, cursor.getString(cursor.getColumnIndexOrThrow("audioPath")))
                assertEquals("Site walkthrough", cursor.getString(cursor.getColumnIndexOrThrow("localTitle")))
                assertEquals("work,field", cursor.getString(cursor.getColumnIndexOrThrow("localTags")))
                assertEquals(7, cursor.getInt(cursor.getColumnIndexOrThrow("segmentCount")))
            }

        migrated
            .query(
                "SELECT audioPath FROM sessions WHERE id = ?",
                arrayOf(withoutAudio),
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue("a null path must stay null", cursor.isNull(cursor.getColumnIndexOrThrow("audioPath")))
            }

        migrated.query("SELECT COUNT(*) FROM sessions").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("the rebuild must not lose or duplicate rows", 2, cursor.getInt(0))
        }
        migrated.close()
    }

    /**
     * The upgrade a phone that has never been updated actually performs. Running the two
     * migrations separately proves each in isolation; only the chain proves they compose,
     * which is what Room will do on that device.
     */
    @Test
    fun migration1To3RunsTheWholeChainAndKeepsTheRow() {
        val existingId = "44444444-4444-4444-4444-444444444444"
        val path = "/data/user/0/com.harken.android/files/recordings/44444444.wav"

        helper.createDatabase(DB_NAME, 1).use { db ->
            db.execSQL(
                """
                INSERT INTO sessions (
                    id, startedAt, endedAt, source, segmentCount, hasSummary,
                    transcriptionStatus, transcriptionFailureReason, durationSeconds,
                    localTitle, localTags, pendingUploadPath, syncedAt
                ) VALUES ('$existingId', '2026-01-01T00:00:00Z', NULL, 'Mobile', 3, 0,
                    'Succeeded', NULL, 42, 'Board review', 'work', '$path', 0)
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 3, true, MIGRATION_1_2, MIGRATION_2_3)

        migrated
            .query(
                "SELECT audioPath, localTitle FROM sessions WHERE id = ?",
                arrayOf(existingId),
            ).use { cursor ->
                assertTrue("a version 1 row should reach version 3 intact", cursor.moveToFirst())
                assertEquals(path, cursor.getString(cursor.getColumnIndexOrThrow("audioPath")))
                assertEquals("Board review", cursor.getString(cursor.getColumnIndexOrThrow("localTitle")))
            }
        migrated.close()
    }

    /**
     * `summaries` had an entity and no reader or writer anywhere (ARC-048). Dropping it
     * must not touch the tables that hold actual user data.
     */
    @Test
    fun migration3To4DropsSummariesTableAndKeepsSessionsAndSegments() {
        val sessionId = "55555555-5555-5555-5555-555555555555"
        val segmentId = "66666666-6666-6666-6666-666666666666"

        helper.createDatabase(DB_NAME, 3).use { db ->
            db.execSQL(
                """
                INSERT INTO sessions (
                    id, startedAt, endedAt, segmentCount, transcriptionStatus,
                    transcriptionFailureReason, durationSeconds, localTitle, localTags, audioPath
                ) VALUES ('$sessionId', '2026-03-01T00:00:00Z', NULL, 1, 'Succeeded', NULL, 60,
                    'Standup', '', '/data/user/0/com.harken.android/files/recordings/55555555.wav')
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO segments (id, sessionId, offsetSeconds, text, voiceIndex)
                VALUES ('$segmentId', '$sessionId', 0, 'hello', 0)
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT INTO summaries (sessionId, summary, generatedAt)
                VALUES ('$sessionId', 'a summary nothing reads', '2026-03-01T00:01:00Z')
                """.trimIndent(),
            )
        }

        val migrated = helper.runMigrationsAndValidate(DB_NAME, 4, true, MIGRATION_3_4)

        migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='summaries'").use { cursor ->
            assertTrue("summaries must be gone after the migration", cursor.count == 0)
        }
        migrated.query("SELECT localTitle FROM sessions WHERE id = ?", arrayOf(sessionId)).use { cursor ->
            assertTrue("the session row must survive", cursor.moveToFirst())
            assertEquals("Standup", cursor.getString(cursor.getColumnIndexOrThrow("localTitle")))
        }
        migrated.query("SELECT text FROM segments WHERE id = ?", arrayOf(segmentId)).use { cursor ->
            assertTrue("the segment row must survive", cursor.moveToFirst())
            assertEquals("hello", cursor.getString(cursor.getColumnIndexOrThrow("text")))
        }
        migrated.close()
    }

    private companion object {
        const val DB_NAME = "harken-migration-test.db"
    }
}
