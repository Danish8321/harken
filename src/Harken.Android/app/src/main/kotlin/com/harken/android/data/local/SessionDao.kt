package com.harken.android.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<SessionRow>>

    /** Every session's id, for reconciling the recordings on disk against the database. */
    @Query("SELECT id FROM sessions")
    suspend fun allIds(): List<UUID>

    /** Every session, oldest first, for an export that walks the whole library once. */
    @Query("SELECT * FROM sessions ORDER BY startedAt ASC")
    suspend fun allSessions(): List<SessionRow>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: UUID): Flow<SessionRow?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun findById(id: UUID): SessionRow?

    @Query("SELECT * FROM segments WHERE sessionId = :id ORDER BY offsetSeconds ASC")
    fun observeSegments(id: UUID): Flow<List<SegmentRow>>

    /** The same rows, read once. The export is a snapshot, not something that redraws. */
    @Query("SELECT * FROM segments WHERE sessionId = :id ORDER BY offsetSeconds ASC")
    suspend fun segmentsOnce(id: UUID): List<SegmentRow>

    // A local-only session's id is always freshly generated on this device, so a
    // conflict here would mean a real bug (id collision), not a benign re-sync race —
    // unlike insertIfNew, this must not silently swallow it.
    @Insert
    suspend fun insertLocalOnly(session: SessionRow)

    @Query("UPDATE sessions SET localTitle = :title WHERE id = :id")
    suspend fun setTitle(
        id: UUID,
        title: String?,
    )

    @Query("UPDATE sessions SET localTags = :tags WHERE id = :id")
    suspend fun setTags(
        id: UUID,
        tags: String,
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceSegments(segments: List<SegmentRow>)

    @Query("DELETE FROM segments WHERE sessionId = :id")
    suspend fun clearSegments(id: UUID)

    // clearSegments + replaceSegments used to run as two separate statements, so
    // observeSegments' Flow emitted an empty list between the delete and the insert —
    // every poll cycle while a session was still transcribing. That emptied-then-refilled
    // list is what made the transcript visibly flicker/jump when scrolled near the bottom.
    // One transaction means Room's invalidation tracker only fires once, after both
    // statements land.
    @Transaction
    suspend fun replaceSegmentsAtomically(
        id: UUID,
        segments: List<SegmentRow>,
    ) {
        clearSegments(id)
        replaceSegments(segments)
    }

    @Query("UPDATE sessions SET transcriptionStatus = 'Running' WHERE id = :id")
    suspend fun markLocalTranscriptionStarted(id: UUID)

    @Query(
        """
        UPDATE sessions SET
            transcriptionStatus = 'Succeeded',
            segmentCount = :segmentCount,
            durationSeconds = :durationSeconds
        WHERE id = :id
        """,
    )
    suspend fun markLocalTranscriptionSucceeded(
        id: UUID,
        segmentCount: Int,
        durationSeconds: Int,
    )

    // Same flicker-avoidance reasoning as replaceSegmentsAtomically: status/segmentCount
    // and the segment rows themselves must land as one transaction so observers never see
    // a "Succeeded" session with a momentarily empty transcript.
    @Transaction
    suspend fun completeLocalTranscription(
        id: UUID,
        segments: List<SegmentRow>,
        durationSeconds: Int,
    ) {
        markLocalTranscriptionSucceeded(id, segments.size, durationSeconds)
        replaceSegmentsAtomically(id, segments)
    }

    @Query(
        """
        UPDATE sessions SET
            transcriptionStatus = 'Failed',
            transcriptionFailureReason = :reason
        WHERE id = :id
        """,
    )
    suspend fun failLocalTranscription(
        id: UUID,
        reason: String,
    )

    /**
     * Settles transcriptions that were running when the process died. Only one on-device
     * transcription runs at a time and none survives the process, so at launch every
     * 'Running' row is a leftover — left alone it shows "Transcribing" forever, with no
     * way for the user to retry it.
     *
     * @return how many rows were stuck.
     */
    @Query(
        """
        UPDATE sessions SET
            transcriptionStatus = 'Failed',
            transcriptionFailureReason = :reason
        WHERE transcriptionStatus = 'Running'
        """,
    )
    suspend fun failInterruptedTranscriptions(reason: String): Int

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: UUID)

    // Transcript search is a LIKE scan, not FTS4. The segments are the only text there
    // is, they are already indexed by session, and a scan of every segment on a device
    // holding a hundred recordings is a few milliseconds — measured, and reported as
    // `search elapsedMs` telemetry so the decision stays evidence-backed. FTS4 with a
    // content table would mean a virtual table plus five hand-written triggers in a
    // migration over real user data, and it would match whole tokens only: "record" would
    // not find "recording" without the user typing a wildcard. If the telemetry ever says
    // the scan is slow, that is the moment to pay for the index.
    //
    // LIKE is case-insensitive for ASCII in SQLite by default, which is the behaviour a
    // search field is expected to have. :pattern must come from SearchQuery.likePattern.
    @Query(
        """
        SELECT sg.sessionId AS sessionId, sg.id AS segmentId, sg.offsetSeconds AS offsetSeconds, sg.text AS text
        FROM segments sg
        JOIN sessions s ON s.id = sg.sessionId
        WHERE sg.text LIKE '%' || :pattern || '%' ESCAPE '\'
        ORDER BY s.startedAt DESC, sg.offsetSeconds ASC
        LIMIT :limit
        """,
    )
    suspend fun searchSegments(
        pattern: String,
        limit: Int,
    ): List<SegmentMatch>

    /** Only titles the user typed: a derived name is not stored, so it cannot be matched here. */
    @Query(
        """
        SELECT * FROM sessions
        WHERE localTitle LIKE '%' || :pattern || '%' ESCAPE '\'
        ORDER BY startedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchTitles(
        pattern: String,
        limit: Int,
    ): List<SessionRow>

    @Query("SELECT * FROM sessions WHERE id IN (:ids)")
    suspend fun sessionsByIds(ids: List<UUID>): List<SessionRow>
}

class UuidConverters {
    @TypeConverter fun toUuid(value: String?): UUID? = value?.let(UUID::fromString)

    @TypeConverter fun fromUuid(value: UUID?): String? = value?.toString()
}

// Real user data already lives in the sessions table on shipped installs, so this must
// be a real, additive migration — never fallbackToDestructiveMigration().
val MIGRATION_1_2 =
    object : androidx.room.migration.Migration(1, 2) {
        override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE sessions ADD COLUMN isLocalOnly INTEGER NOT NULL DEFAULT 0")
        }
    }

@Database(entities = [SessionRow::class, SegmentRow::class, SummaryRow::class], version = 2, exportSchema = true)
@TypeConverters(UuidConverters::class)
abstract class HarkenDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        @Volatile private var instance: HarkenDatabase? = null

        fun get(context: android.content.Context): HarkenDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room
                    .databaseBuilder(context.applicationContext, HarkenDatabase::class.java, "harken-local.db")
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
