package com.harken.android.network

import java.util.UUID

// Mirrors src/Harken.Core/AudioSource.cs and src/Harken.Core/TranscriptionStatus.cs —
// the client contract types, never hand-written independently of the backend (Gson
// matches enum constant names by default, so these must spell the C# names exactly).
enum class AudioSource { Microphone, SystemAudio }

enum class TranscriptionStatus { Pending, Running, Succeeded, Failed }

// Mirrors src/Harken.Core/Contracts/SessionListItem.cs.
data class SessionListItem(
    val id: UUID,
    val startedAt: String,
    val endedAt: String?,
    val source: AudioSource,
    val segmentCount: Int,
    val hasSummary: Boolean,
)

// Mirrors src/Harken.Core/Contracts/SessionDetail.cs.
data class SessionDetail(
    val id: UUID,
    val startedAt: String,
    val endedAt: String?,
    val source: AudioSource,
    val segments: List<TranscriptSegmentView>,
    val summary: SessionSummary?,
    val transcriptionStatus: TranscriptionStatus?,
    val transcriptionFailureReason: String?,
)

// Mirrors src/Harken.Core/Contracts/SessionDetail.cs's TranscriptSegmentView record.
data class TranscriptSegmentView(
    val id: UUID,
    val offset: String,
    val text: String,
    val recognizedAt: String,
)

// Mirrors src/Harken.Core/Contracts/SessionSummary.cs.
data class SessionSummary(
    val sessionId: UUID,
    val summary: String,
    val generatedAt: String,
)
