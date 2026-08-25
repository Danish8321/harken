package com.harken.android.data.local

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface SessionDao {

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<SessionRow>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observeSession(id: UUID): Flow<SessionRow?>

    @Query("SELECT * FROM segments WHERE sessionId = :id ORDER BY offsetSeconds ASC")
    fun observeSegments(id: UUID): Flow<List<SegmentRow>>

    @Query("SELECT * FROM summaries WHERE sessionId = :id")
    fun observeSummary(id: UUID): Flow<SummaryRow?>

    // insertIfNew + updateMirroredFields deliberately do NOT touch localTitle, localTags
    // or pendingUploadPath: a sync must never clobber something the user typed on this
    // device. That is the whole reason this is a hand-written UPDATE rather than an
    // @Upsert of the full row.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(session: SessionRow)

    // A local-only session's id is always freshly generated on this device, so a
    // conflict here would mean a real bug (id collision), not a benign re-sync race —
    // unlike insertIfNew, this must not silently swallow it.
    @Insert
    suspend fun insertLocalOnly(session: SessionRow)

    @Query(
        """
        UPDATE sessions SET
            startedAt = :startedAt,
            endedAt = :endedAt,
            source = :source,
            segmentCount = :segmentCount,
            hasSummary = :hasSummary,
            transcriptionStatus = :status,
            transcriptionFailureReason = :failureReason,
            durationSeconds = COALESCE(:durationSeconds, durationSeconds),
            syncedAt = :syncedAt
        WHERE id = :id
        """,
    )
    suspend fun updateMirroredFields(
        id: UUID,
        startedAt: String,
        endedAt: String?,
        source: String,
        segmentCount: Int,
        hasSummary: Boolean,
        status: String?,
        failureReason: String?,
        durationSeconds: Int?,
        syncedAt: Long,
    )

    @Transaction
    suspend fun upsertMirrored(row: SessionRow) {
        insertIfNew(row)
        updateMirroredFields(
            id = row.id,
            startedAt = row.startedAt,
            endedAt = row.endedAt,
            source = row.source,
            segmentCount = row.segmentCount,
            hasSummary = row.hasSummary,
            status = row.transcriptionStatus,
            failureReason = row.transcriptionFailureReason,
            durationSeconds = row.durationSeconds,
            syncedAt = row.syncedAt,
        )
    }

    @Query("UPDATE sessions SET localTitle = :title WHERE id = :id")
    suspend fun setTitle(id: UUID, title: String?)

    @Query("UPDATE sessions SET localTags = :tags WHERE id = :id")
    suspend fun setTags(id: UUID, tags: String)

    @Query("UPDATE sessions SET pendingUploadPath = :path WHERE id = :id")
    suspend fun setPendingUpload(id: UUID, path: String?)

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
    suspend fun replaceSegmentsAtomically(id: UUID, segments: List<SegmentRow>) {
        clearSegments(id)
        replaceSegments(segments)
    }

    @Query(
        """
        UPDATE sessions SET
            transcriptionStatus = 'Succeeded',
            segmentCount = :segmentCount,
            durationSeconds = :durationSeconds
        WHERE id = :id
        """,
    )
    suspend fun markLocalTranscriptionSucceeded(id: UUID, segmentCount: Int, durationSeconds: Int)

    // Same flicker-avoidance reasoning as replaceSegmentsAtomically: status/segmentCount
    // and the segment rows themselves must land as one transaction so observers never see
    // a "Succeeded" session with a momentarily empty transcript.
    @Transaction
    suspend fun completeLocalTranscription(id: UUID, segments: List<SegmentRow>, durationSeconds: Int) {
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
    suspend fun failLocalTranscription(id: UUID, reason: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replaceSummary(summary: SummaryRow)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: UUID)

    @Query("SELECT DISTINCT localTags FROM sessions WHERE localTags != ''")
    fun observeTagStrings(): Flow<List<String>>
}

class UuidConverters {
    @TypeConverter fun toUuid(value: String?): UUID? = value?.let(UUID::fromString)
    @TypeConverter fun fromUuid(value: UUID?): String? = value?.toString()
}

// Real user data already lives in the sessions table on shipped installs, so this must
// be a real, additive migration — never fallbackToDestructiveMigration().
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN isLocalOnly INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [SessionRow::class, SegmentRow::class, SummaryRow::class], version = 2, exportSchema = false)
@TypeConverters(UuidConverters::class)
abstract class HarkenDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao

    companion object {
        @Volatile private var instance: HarkenDatabase? = null

        fun get(context: android.content.Context): HarkenDatabase = instance ?: synchronized(this) {
            instance ?: androidx.room.Room
                .databaseBuilder(context.applicationContext, HarkenDatabase::class.java, "harken-local.db")
                .addMigrations(MIGRATION_1_2)
                .build()
                .also { instance = it }
        }
    }
}
