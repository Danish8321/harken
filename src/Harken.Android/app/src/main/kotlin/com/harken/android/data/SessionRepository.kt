package com.harken.android.data

import com.harken.android.data.local.HarkenDatabase
import com.harken.android.data.local.SegmentRow
import com.harken.android.data.local.SessionRow
import com.harken.android.speech.LocalTranscribedSegment
import com.harken.android.telemetry.Telemetry
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

    private companion object {
        /** More title matches than this is a term so common the list stops being useful. */
        const val TitleMatchLimit = 50
    }

    data class SessionView(
        val id: UUID,
        /**
         * The name the user typed, or null if they never did. The name to *show* is
         * [com.harken.android.displayTitle] — this layer does not compose display text
         * and has no opinion about the reader's language (ARC-017).
         */
        val localTitle: String?,
        /** What an untitled recording is named after: when it was made. */
        val partOfDay: PartOfDay,
        val startedAt: String,
        val durationSeconds: Int?,
        val segmentCount: Int,
        val status: String?,
        val failureReason: String?,
        val tags: List<String>,
        val pendingUploadPath: String?,
        val isLocalOnly: Boolean,
    )

    /**
     * One session that matched a search, and the first transcript line that matched in it.
     *
     * [matchCount] is over the segments this search read, not over the whole recording —
     * see [SearchQuery.SegmentMatchLimit].
     */
    data class SearchHit(
        val session: SessionView,
        val matchCount: Int,
        /** The matching transcript line, or null when only the title matched. */
        val snippet: String?,
        val offsetSeconds: Int?,
        /** The segment to open the transcript at, or null when only the title matched. */
        val segmentId: UUID?,
    )

    fun observeSessions(): Flow<List<SessionView>> = dao.observeSessions().map { rows -> rows.map(::toView) }

    fun observeSession(id: UUID): Flow<SessionView?> = dao.observeSession(id).map { row -> row?.let(::toView) }

    fun observeSegments(id: UUID) = dao.observeSegments(id)

    /** Every session the database holds, for reconciling it against the WAVs on disk. */
    suspend fun sessionIds(): List<UUID> = dao.allIds()

    /**
     * The whole library, oldest first, with every transcript, for an export.
     *
     * Read in one pass and held in memory: transcripts are text, so a few hundred
     * recordings is a couple of megabytes, and the audio — the part that is gigabytes — is
     * streamed from its path rather than loaded here.
     */
    /**
     * Every recording, formatted for a backup. [displayTitle] resolves the name of an
     * untitled one — the caller supplies it, because the file names this produces are
     * read by a person and this layer does not know their language (ARC-017).
     */
    suspend fun exportItems(
        displayTitle: (localTitle: String?, partOfDay: PartOfDay) -> String,
    ): List<ExportItem> = dao.allSessions().map { row ->
        ExportItem(
            title = displayTitle(row.localTitle, PartOfDay.of(row.startedAt)),
            startedAt = row.startedAt,
            audioPath = row.pendingUploadPath,
            lines = dao.segmentsOnce(row.id).map { TranscriptText.Line(it.offsetSeconds, it.text) },
        )
    }

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
        filePath: String,
        durationSeconds: Int,
    ) {
        dao.insertLocalOnly(
            SessionRow(
                id = id,
                startedAt = startedAt,
                endedAt = endedAt,
                // source, hasSummary and syncedAt are sync-era columns: nothing on the
                // phone reads them, and every recording has the same origin. They are
                // written once here and drop out with the rename migration (ARC-015),
                // because dropping a column is a migration and migrations wait for it.
                source = "Microphone",
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
     * Searches transcripts and user-typed titles.
     *
     * Sessions come back newest first, each with its earliest matching line, so the result
     * list reads in the same order as the Library behind it. A term shorter than
     * [SearchQuery.MinLength] returns nothing rather than most of the database.
     */
    suspend fun search(query: String): List<SearchHit> {
        val term = query.trim()
        if (term.length < SearchQuery.MinLength) return emptyList()

        val startNanos = System.nanoTime()
        val pattern = SearchQuery.likePattern(term)
        val matches = dao.searchSegments(pattern, SearchQuery.SegmentMatchLimit)
        val titleRows = dao.searchTitles(pattern, TitleMatchLimit)

        // Already ordered by startedAt DESC, offsetSeconds ASC, and groupBy keeps that
        // order — so the first entry of each group is the earliest match in the recording.
        val bySession = matches.groupBy { it.sessionId }
        // Guarded because Room renders an empty list as `IN ()`, which SQLite rejects.
        val matchedRows = if (bySession.isEmpty()) emptyList() else dao.sessionsByIds(bySession.keys.toList())

        val hits = (matchedRows + titleRows)
            .distinctBy { it.id }
            .sortedByDescending { it.startedAt }
            .map { row ->
                val inSession = bySession[row.id].orEmpty()
                val first = inSession.firstOrNull()
                SearchHit(
                    session = toView(row),
                    matchCount = inSession.size,
                    snippet = first?.text,
                    offsetSeconds = first?.offsetSeconds,
                    segmentId = first?.segmentId,
                )
            }

        // Shapes only: the length of what was typed, never the term itself. A search term
        // is as private as the transcript it searches (ADR-0011), and logcat is readable
        // by adb.
        Telemetry.event(
            "search",
            "chars" to term.length,
            "segmentMatches" to matches.size,
            "sessions" to hits.size,
            "elapsedMs" to Telemetry.elapsedMsSince(startNanos),
        )
        return hits
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
        // Audio first, row second. The other order looks harmless — a file the delete
        // missed is just an orphan — except that RecordingRecovery is built to adopt
        // orphans, so a failed delete meant the recording came back at the next launch,
        // with a derived title because the row that carried the real one was gone
        // (ARC-007). Failing here leaves the recording whole and says so; delete is the
        // one operation a user expects to be final, so a half-done one is not reported
        // as done.
        val audio = dao.findById(id)?.pendingUploadPath
        audio?.let { path ->
            val file = java.io.File(path)
            check(!file.exists() || file.delete()) { "Could not delete the audio for this recording" }
        }
        dao.deleteSession(id)
        Unit
    }

    private fun toView(row: SessionRow) = SessionView(
        id = row.id,
        localTitle = row.localTitle,
        partOfDay = PartOfDay.of(row.startedAt),
        startedAt = row.startedAt,
        durationSeconds = row.durationSeconds,
        segmentCount = row.segmentCount,
        status = row.transcriptionStatus,
        failureReason = row.transcriptionFailureReason,
        tags = row.localTags.split(',').filter { it.isNotBlank() },
        pendingUploadPath = row.pendingUploadPath,
        isLocalOnly = row.isLocalOnly,
    )
}

