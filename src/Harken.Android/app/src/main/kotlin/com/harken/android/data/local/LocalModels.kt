package com.harken.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// The app's first Room database. Scope decision (ADR-0010): a FULL local mirror, not
// just an overrides table. Library then opens instantly and reads offline, and the API
// becomes a sync source rather than the thing the UI blocks on.
//
// Two kinds of column live here and they must not be confused:
//   * mirrored   — owned by the backend, overwritten on every sync
//   * local-only — owned by this device, never overwritten (title, tags)

@Entity(tableName = "sessions")
data class SessionRow(
    @PrimaryKey val id: UUID,
    val startedAt: String,
    val endedAt: String?,
    val source: String,
    val segmentCount: Int,
    val hasSummary: Boolean,
    val transcriptionStatus: String?,
    val transcriptionFailureReason: String?,
    /** Derived from the last segment's offset, since the API returns no duration. */
    val durationSeconds: Int?,
    /** Local: null means "show the derived name". */
    val localTitle: String? = null,
    /** Local: comma-separated, empty means untagged. */
    val localTags: String = "",
    /** Local: set when a recording was captured but never reached the backend. */
    val pendingUploadPath: String? = null,
    val syncedAt: Long = 0L,
)

@Entity(tableName = "segments")
data class SegmentRow(
    @PrimaryKey val id: UUID,
    val sessionId: UUID,
    val offsetSeconds: Int,
    val text: String,
    /**
     * Local: a heuristic voice index, NOT diarization. Whisper base.en returns no
     * speaker information at all, so this is inferred from gap length and labelled
     * "Voice 1"/"Voice 2" rather than claiming to know who spoke. See SpeakerHeuristic.
     */
    val voiceIndex: Int,
)

@Entity(tableName = "summaries")
data class SummaryRow(
    @PrimaryKey val sessionId: UUID,
    val summary: String,
    val generatedAt: String,
)
