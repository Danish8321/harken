package com.harken.android.data

import com.harken.android.data.local.HarkenDatabase
import com.harken.android.data.local.SegmentRow
import com.harken.android.data.local.SessionRow
import com.harken.android.speech.LocalTranscribedSegment
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The single place the UI reads sessions from.
 *
 * Room is the source of truth: recordings are transcribed entirely on-device and never
 * synced from/to a backend.
 */
interface TranscriptionSink {
    suspend fun startLocalTranscription(id: UUID)
    suspend fun completeLocal(id: UUID, segments: List<LocalTranscribedSegment>, durationSeconds: Int)
    suspend fun failLocal(id: UUID, reason: String)
}

class SessionRepository(
    private val db: HarkenDatabase,
) : TranscriptionSink {
    private val dao = db.sessions()

    data class SessionView(
        val id: UUID,
        val title: String,
        val hasLocalTitle: Boolean,
        val startedAt: String,
        val durationSeconds: Int?,
        val segmentCount: Int,
        val hasSummary: Boolean,
        val status: String?,
        val failureReason: String?,
        val tags: List<String>,
        val pendingUploadPath: String?,
        val isLocalOnly: Boolean,
    )

    fun observeSessions(): Flow<List<SessionView>> = dao.observeSessions().map { rows -> rows.map(::toView) }

    fun observeSession(id: UUID): Flow<SessionView?> = dao.observeSession(id).map { row -> row?.let(::toView) }

    fun observeSegments(id: UUID) = dao.observeSegments(id)

    fun observeSummary(id: UUID) = dao.observeSummary(id)

    /** Flips a "Recorded" (recorded, not yet transcribed) session to "Running". */
    override suspend fun startLocalTranscription(id: UUID) = dao.markLocalTranscriptionStarted(id)

    /** Local rename. Passing null restores the derived name. */
    suspend fun rename(id: UUID, title: String?) = dao.setTitle(id, title?.trim()?.ifBlank { null })

    suspend fun setTags(id: UUID, tags: List<String>) =
        dao.setTags(id, tags.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","))

    /**
     * Creates a fresh local-only session row (ADR-0011) for a recording that will be
     * transcribed entirely on-device — never synced from/to the backend. The audio is
     * saved but transcription does not start until the user explicitly asks for it (see
     * [startLocalTranscription], [completeLocal], [failLocal]).
     */
    suspend fun createLocalSession(id: UUID, startedAt: String, source: String, filePath: String) {
        dao.insertLocalOnly(
            SessionRow(
                id = id,
                startedAt = startedAt,
                endedAt = null,
                source = source,
                segmentCount = 0,
                hasSummary = false,
                transcriptionStatus = "Recorded",
                transcriptionFailureReason = null,
                durationSeconds = null,
                syncedAt = System.currentTimeMillis(),
                isLocalOnly = true,
                pendingUploadPath = filePath,
            ),
        )
    }

    /** Settles a local-only session as transcribed, using the same voice heuristic used elsewhere. */
    override suspend fun completeLocal(id: UUID, segments: List<LocalTranscribedSegment>, durationSeconds: Int) {
        val offsets = segments.map { it.offsetSeconds }
        val voices = SpeakerHeuristic.assign(offsets)
        dao.completeLocalTranscription(
            id,
            segments.mapIndexed { i, s ->
                SegmentRow(
                    id = UUID.randomUUID(),
                    sessionId = id,
                    offsetSeconds = s.offsetSeconds,
                    text = s.text,
                    voiceIndex = voices[i],
                )
            },
            durationSeconds,
        )
    }

    /** Marks a local-only session's on-device transcription as failed. */
    override suspend fun failLocal(id: UUID, reason: String) = dao.failLocalTranscription(id, reason)

    /**
     * Settles sessions that were transcribing when the process died. Call once at process
     * start, before anything can begin a new transcription: at that moment a row still
     * marked as running cannot be running, so it is an interrupted one. Returns how many
     * rows were recovered.
     */
    suspend fun recoverInterruptedTranscriptions(reason: String): Int =
        dao.failInterruptedTranscriptions(reason)

    /**
     * Removes a recording and everything derived from it: the session row, its segments,
     * its summary, and the audio file itself. The delete confirmation promises the
     * recording leaves the phone, and a ~115 MB/hour WAV left behind on disk would make
     * that a lie in the most expensive way available.
     *
     * The file is deleted after the rows, not before: a failed database delete leaves a
     * session the user can still open, which is recoverable, where the reverse leaves a
     * session pointing at audio that is gone.
     */
    suspend fun purge(id: UUID): Result<Unit> = runCatching {
        val audioPath = dao.findSession(id)?.pendingUploadPath
        dao.deleteSessionCompletely(id)
        audioPath?.let { File(it).delete() }
        Unit
    }

    private fun toView(row: SessionRow) = SessionView(
        id = row.id,
        title = row.localTitle ?: DerivedTitle.of(row.startedAt),
        hasLocalTitle = row.localTitle != null,
        startedAt = row.startedAt,
        durationSeconds = row.durationSeconds,
        segmentCount = row.segmentCount,
        hasSummary = row.hasSummary,
        status = row.transcriptionStatus,
        failureReason = row.transcriptionFailureReason,
        tags = row.localTags.split(',').filter { it.isNotBlank() },
        pendingUploadPath = row.pendingUploadPath,
        isLocalOnly = row.isLocalOnly,
    )
}

/**
 * The name a recording gets before anyone renames it.
 *
 * Reads the time of day back as a phrase a person would use ("Morning recording")
 * instead of "Untitled recording" or a bare timestamp. A real title is one tap away in
 * the session sheet.
 */
object DerivedTitle {
    fun of(startedAtIso: String): String {
        val hour = startedAtIso.substringAfter('T', "").take(2).toIntOrNull() ?: return "Recording"
        val partOfDay = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Late night"
        }
        return "$partOfDay recording"
    }
}
