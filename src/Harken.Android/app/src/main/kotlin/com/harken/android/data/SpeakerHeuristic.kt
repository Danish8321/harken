package com.harken.android.data

import com.harken.android.network.TranscriptSegmentView

/**
 * Assigns a voice index to each transcript segment.
 *
 * This is NOT diarization. Whisper base.en returns no speaker information whatsoever, so
 * there is nothing to derive a real identity from. What we do have is segment offsets,
 * and a long gap between two segments is weak but genuine evidence that the turn changed.
 *
 * So: a gap of at least [TURN_GAP_SECONDS] flips the voice index, and the UI labels the
 * result "Voice 1" and "Voice 2" — never "Speaker A", never a name. If the backend later
 * gains real diarization, this class is the single thing that gets deleted.
 */
object SpeakerHeuristic {

    const val TURN_GAP_SECONDS = 2

    /** @return one voice index per segment, in the order given. */
    fun assign(offsetsSeconds: List<Int>, turnGapSeconds: Int = TURN_GAP_SECONDS): List<Int> {
        if (offsetsSeconds.isEmpty()) return emptyList()
        val out = ArrayList<Int>(offsetsSeconds.size)
        var voice = 0
        out.add(voice)
        for (i in 1 until offsetsSeconds.size) {
            val gap = offsetsSeconds[i] - offsetsSeconds[i - 1]
            if (gap >= turnGapSeconds) voice = 1 - voice
            out.add(voice)
        }
        return out
    }

    /** How many distinct voices the heuristic found — 1 means "don't show labels at all". */
    fun voiceCount(indices: List<Int>): Int = indices.distinct().size

    /**
     * Backend offsets arrive as raw TimeSpan strings ("00:00:03.2000000"). Whole seconds
     * is all the reader ever sees, and all the heuristic needs.
     */
    fun offsetSeconds(offset: String): Int {
        val head = offset.substringBefore('.')
        val parts = head.split(':')
        return when (parts.size) {
            3 -> (parts[0].toIntOrNull() ?: 0) * 3600 + (parts[1].toIntOrNull() ?: 0) * 60 + (parts[2].toIntOrNull() ?: 0)
            2 -> (parts[0].toIntOrNull() ?: 0) * 60 + (parts[1].toIntOrNull() ?: 0)
            else -> parts.firstOrNull()?.toIntOrNull() ?: 0
        }
    }

    fun offsetSeconds(segments: List<TranscriptSegmentView>): List<Int> = segments.map { offsetSeconds(it.offset) }
}
