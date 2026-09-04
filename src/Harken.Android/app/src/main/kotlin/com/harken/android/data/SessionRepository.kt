package com.harken.android.data

import com.harken.android.data.local.HarkenDatabase
import com.harken.android.data.local.SegmentRow
import com.harken.android.data.local.SessionRow
import com.harken.android.speech.LocalTranscribedSegment
import com.harken.android.telemetry.Telemetry
import java.time.Instant
import java.time.ZoneId
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

    /** Every session the database holds, for reconciling it against the WAVs on disk. */
    suspend fun sessionIds(): List<UUID> = dao.allIds()

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
    suspend fun createLocalSession(
        id: UUID,
        startedAt: String,
        endedAt: String,
        source: String,
        filePath: String,
        durationSeconds: Int,
    ) {
        dao.insertLocalOnly(
            SessionRow(
                id = id,
                startedAt = startedAt,
                endedAt = endedAt,
                source = source,
                segmentCount = 0,
                hasSummary = false,
                transcriptionStatus = "Recorded",
                transcriptionFailureReason = null,
                // Known here, not only after transcription: the capture's own length. Left
                // null, every un-transcribed session read "0m 00s" in the Library.
                durationSeconds = durationSeconds,
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
     * Settles transcriptions the process died in the middle of, so they offer a retry
     * instead of showing "Transcribing" for good. Called at launch, alongside the other
     * reconciliation sweeps.
     */
    suspend fun failInterruptedTranscriptions(reason: String): Int {
        val stuck = dao.failInterruptedTranscriptions(reason)
        if (stuck > 0) {
            Telemetry.event("transcription_interrupted_recovered", "sessions" to stuck)
        }
        return stuck
    }

    /**
     * Deletes the session and the audio it was recorded from.
     *
     * The file has to go with the row: it is the recording, deleting is the user asking
     * for it to be gone, and [com.harken.android.recording.RecordingRecovery] would
     * otherwise adopt the leftover WAV on the next launch and bring the deleted recording
     * straight back.
     */
    suspend fun purge(id: UUID): Result<Unit> = runCatching {
        val audio = dao.findById(id)?.pendingUploadPath
        dao.deleteSession(id)
        audio?.let { path ->
            val file = java.io.File(path)
            if (file.exists() && !file.delete()) {
                android.util.Log.w("SessionRepository", "Deleted session $id but could not delete $path")
            }
        }
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
    fun of(startedAtIso: String, zone: ZoneId = ZoneId.systemDefault()): String {
        // Parsed into the reader's zone, not read off the string: startedAt is stored as
        // UTC, so a 1:41 pm capture in UTC+5:30 carried the hour "08" and was titled
        // "Morning recording" while the card beside it read "1:41 pm".
        val hour = runCatching { Instant.parse(startedAtIso).atZone(zone).hour }
            .getOrElse { startedAtIso.substringAfter('T', "").take(2).toIntOrNull() }
            ?: return "Recording"
        val partOfDay = when (hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Late night"
        }
        return "$partOfDay recording"
    }
}
