package com.harken.android.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

// The app's database. ADR-0010 shaped it as a full local mirror of a backend; ADR-0011
// removed the backend, so there is nothing on the other side to mirror and every column
// here is owned by this device. The sync-era shape survived that change and was still
// describing a server in version 2 — ARC-015 renamed and dropped the columns that did.
//
// Anything added here is local, permanent, and the only copy: nothing else holds it.

@Entity(tableName = "sessions")
data class SessionRow(
    @PrimaryKey val id: UUID,
    val startedAt: String,
    val endedAt: String?,
    val segmentCount: Int,
    val transcriptionStatus: String?,
    val transcriptionFailureReason: String?,
    /** Set from the capture's own length when the row is written, refined once transcribed. */
    val durationSeconds: Int?,
    /** Null means "show the derived name". */
    val localTitle: String? = null,
    /** Comma-separated, empty means untagged. */
    val localTags: String = "",
    /**
     * Absolute path of the WAV this recording was captured to, or null once the audio is
     * gone. The only pointer to it: deleting a session deletes this file, and nothing
     * re-derives the path from the row.
     */
    val audioPath: String? = null,
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

/**
 * A transcript line that matched a search, with the session it came from.
 *
 * A projection, not a table: Room maps the columns of the search JOIN onto it, so the
 * result rows carry only what a result card renders.
 */
data class SegmentMatch(
    val sessionId: UUID,
    val segmentId: UUID,
    val offsetSeconds: Int,
    val text: String,
)
