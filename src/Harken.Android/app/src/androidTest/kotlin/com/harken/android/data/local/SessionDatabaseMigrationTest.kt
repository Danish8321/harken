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
 * A migration applied to a database that already holds real recordings must be additive:
 * it adds, it never drops, and every row that was there is still there afterwards.
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
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        HarkenDatabase::class.java,
    )

    @Test
    fun migration1To2AddsIsLocalOnlyColumnAndPreservesExistingRows() {
        val existingId = "11111111-1111-1111-1111-111111111111"

        helper.createDatabase(DbName, 1).use { db ->
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
        val migrated = helper.runMigrationsAndValidate(DbName, 2, true, MIGRATION_1_2)

        migrated.query(
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

    private companion object {
        const val DbName = "harken-migration-test.db"
    }
}
