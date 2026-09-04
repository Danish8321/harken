package com.harken.android.ui

/**
 * The pure parts of following a transcript while it plays: which segment the playhead is
 * inside, and how a position reads on screen. Kept out of the ViewModel so both are
 * testable without a MediaPlayer.
 */
object PlaybackCursor {
    /**
     * The index of the segment the playhead is inside, or null when it sits before the
     * first one. A segment owns the time from its own offset until the next segment's, so
     * the last one runs to the end of the recording.
     *
     * Offsets are whole seconds (that is the resolution the transcript stores) and are
     * assumed ordered, which is how the transcript is read out of the database.
     */
    fun activeSegment(offsetsSeconds: List<Int>, positionMs: Int): Int? {
        if (offsetsSeconds.isEmpty()) return null
        val positionSeconds = positionMs / 1000
        val index = offsetsSeconds.indexOfLast { it <= positionSeconds }
        return if (index < 0) null else index
    }

    /** "4:07", or "1:02:09" once a recording runs past an hour. Never negative. */
    fun formatClock(ms: Int): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val seconds = total % 60
        val minutes = (total / 60) % 60
        val hours = total / 3600
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /**
     * Where a scrubber sitting [fraction] of the way along lands, in milliseconds, clamped
     * to the recording. A zero-length recording has nowhere to seek to.
     */
    fun seekTarget(fraction: Float, durationMs: Int): Int {
        if (durationMs <= 0) return 0
        return (fraction.coerceIn(0f, 1f) * durationMs).toInt().coerceIn(0, durationMs)
    }

    /** Where the scrubber sits, 0f..1f. A recording with no known length shows empty. */
    fun progress(positionMs: Int, durationMs: Int): Float =
        if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
