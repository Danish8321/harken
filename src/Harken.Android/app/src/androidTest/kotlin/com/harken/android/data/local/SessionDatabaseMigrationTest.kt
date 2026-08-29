package com.harken.android.data.local

import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MIGRATION_1_2 is the Room-world equivalent of an EF migration applied to a database
 * that already ships real user data: it must be additive only. exportSchema is false for
 * this database (no schema-bundle scaffolding exists yet), so rather than
 * androidx.room.testing.MigrationTestHelper (which needs an exported schema JSON), this
 * builds a real v1-shaped "sessions" table by hand, runs the migration against it via a
 * plain SupportSQLiteDatabase, and asserts both that the new column exists and that a
 * pre-existing row survives untouched.
 */
class SessionDatabaseMigrationTest {

    private val dbName = "migration-1-2-test.db"

    @Test
    fun migration1To2AddsIsLocalOnlyColumnAndPreservesExistingRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)

        val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                            db.execSQL(
                                """
                                CREATE TABLE sessions (
                                    id TEXT NOT NULL PRIMARY KEY,
                                    startedAt TEXT NOT NULL,
                                    endedAt TEXT,
                                    source TEXT NOT NULL,
                                    segmentCount INTEGER NOT NULL,
                                    hasSummary INTEGER NOT NULL,
                                    transcriptionStatus TEXT,
                                    transcriptionFailureReason TEXT,
                                    durationSeconds INTEGER,
                                    localTitle TEXT,
                                    localTags TEXT NOT NULL,
                                    pendingUploadPath TEXT,
                                    syncedAt INTEGER NOT NULL
                                )
                                """.trimIndent(),
                            )
                        }

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                )
                .build(),
        )

        val existingId = "11111111-1111-1111-1111-111111111111"
        helper.writableDatabase.execSQL(
            """
            INSERT INTO sessions (
                id, startedAt, endedAt, source, segmentCount, hasSummary,
                transcriptionStatus, transcriptionFailureReason, durationSeconds,
                localTitle, localTags, pendingUploadPath, syncedAt
            ) VALUES ('$existingId', '2026-01-01T00:00:00Z', NULL, 'Mobile', 3, 0,
                'Succeeded', NULL, 42, NULL, '', NULL, 0)
            """.trimIndent(),
        )
        helper.close()

        // Re-open with a callback that runs the real migration on upgrade, mirroring how
        // Room itself would invoke it going from version 1 to 2.
        val migratedHelper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(2) {
                        override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: androidx.sqlite.db.SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) {
                            MIGRATION_1_2.migrate(db)
                        }
                    },
                )
                .build(),
        )

        val db = migratedHelper.writableDatabase

        val columns = mutableListOf<String>()
        db.query("PRAGMA table_info(sessions)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                columns.add(cursor.getString(nameIndex))
            }
        }
        assertTrue("isLocalOnly column should exist after migration", columns.contains("isLocalOnly"))

        db.query("SELECT id, segmentCount, isLocalOnly FROM sessions WHERE id = '$existingId'").use { cursor ->
            assertTrue("pre-existing row should survive the migration", cursor.moveToFirst())
            assertEquals(3, cursor.getInt(cursor.getColumnIndexOrThrow("segmentCount")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("isLocalOnly")))
        }

        migratedHelper.close()
        context.deleteDatabase(dbName)
    }
}
