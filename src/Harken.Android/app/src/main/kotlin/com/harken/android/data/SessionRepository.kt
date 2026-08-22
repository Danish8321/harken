package com.harken.android.data

import com.harken.android.data.local.HarkenDatabase
import com.harken.android.data.local.SegmentRow
import com.harken.android.data.local.SessionRow
import com.harken.android.data.local.SummaryRow
import com.harken.android.network.HarkenApi
import com.harken.android.network.SessionDetail
import com.harken.android.network.SessionListItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The single place the UI reads sessions from.
 *
 * Room is the source of truth for the SCREEN; the API is the source of truth for the
 * BACKEND'S fields. Library renders from the local mirror immediately and a refresh is a
 * background reconciliation, so the list never shows a spinner where content used to be.
 */
class SessionRepository(
    private val db: HarkenDatabase,
    private val api: HarkenApi,
) {
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
    )

    fun observeSessions(): Flow<List<SessionView>> = dao.observeSessions().map { rows -> rows.map(::toView) }

    fun observeSession(id: UUID): Flow<SessionView?> = dao.observeSession(id).map { row -> row?.let(::toView) }

    fun observeSegments(id: UUID) = dao.observeSegments(id)

    fun observeSummary(id: UUID) = dao.observeSummary(id)

    /** Local rename. Passing null restores the derived name. */
    suspend fun rename(id: UUID, title: String?) = dao.setTitle(id, title?.trim()?.ifBlank { null })

    suspend fun setTags(id: UUID, tags: List<String>) =
        dao.setTags(id, tags.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(","))

    /** Reconcile the mirror against the backend. Local-only columns are never touched. */
    suspend fun refresh(): Result<Unit> = runCatching {
        val response = api.listSessions()
        if (!response.isSuccessful) error("listSessions returned ${response.code()}")
        val now = System.currentTimeMillis()
        response.body().orEmpty().forEach { item -> dao.upsertMirrored(item.toRow(now)) }
    }

    /** Pull one session's detail, including segments, summary and the voice heuristic. */
    suspend fun refreshDetail(id: UUID): Result<Unit> = runCatching {
        val response = api.getSession(id)
        if (!response.isSuccessful) error("getSession returned ${response.code()}")
        val detail = response.body() ?: error("getSession returned an empty body")
        val offsets = SpeakerHeuristic.offsetSeconds(detail.segments)
        val voices = SpeakerHeuristic.assign(offsets)
        dao.upsertMirrored(detail.toRow(System.currentTimeMillis(), offsets.lastOrNull()))
        dao.clearSegments(id)
        dao.replaceSegments(
            detail.segments.mapIndexed { i, s ->
                SegmentRow(
                    id = s.id,
                    sessionId = id,
                    offsetSeconds = offsets[i],
                    text = s.text,
                    voiceIndex = voices[i],
                )
            },
        )
        detail.summary?.let { dao.replaceSummary(SummaryRow(id, it.summary, it.generatedAt)) }
    }

    suspend fun summarize(id: UUID): Result<Unit> = runCatching {
        val response = api.summarize(id)
        if (!response.isSuccessful) error("summarize returned ${response.code()}")
        refreshDetail(id).getOrThrow()
    }

    suspend fun softDelete(id: UUID): Result<Unit> = runCatching {
        val response = api.deleteSession(id)
        if (!response.isSuccessful) error("deleteSession returned ${response.code()}")
        dao.deleteSession(id)
    }

    suspend fun purge(id: UUID): Result<Unit> = runCatching {
        val response = api.purgeSession(id)
        if (!response.isSuccessful) error("purgeSession returned ${response.code()}")
        dao.deleteSession(id)
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
    )

    private fun SessionListItem.toRow(now: Long) = SessionRow(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        source = source.name,
        segmentCount = segmentCount,
        hasSummary = hasSummary,
        transcriptionStatus = null,
        transcriptionFailureReason = null,
        durationSeconds = null,
        syncedAt = now,
    )

    private fun SessionDetail.toRow(now: Long, lastOffset: Int?) = SessionRow(
        id = id,
        startedAt = startedAt,
        endedAt = endedAt,
        source = source.name,
        segmentCount = segments.size,
        hasSummary = summary != null,
        transcriptionStatus = transcriptionStatus?.name,
        transcriptionFailureReason = transcriptionFailureReason,
        durationSeconds = lastOffset,
        syncedAt = now,
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
