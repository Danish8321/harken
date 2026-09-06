package com.harken.android.data

/**
 * One recording as it leaves the app: the audio, and the transcript that goes with it.
 *
 * A snapshot rather than a live view — an export writes what the library held when the
 * user asked for it, and a recording finishing mid-export does not belong in it.
 */
data class ExportItem(
    val title: String,
    val startedAt: String,
    /** The WAV on disk, or null once the audio is gone and only the transcript remains. */
    val audioPath: String?,
    val lines: List<TranscriptText.Line>,
)
