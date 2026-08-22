package com.harken.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

// The voice heuristic is the one piece of the redesign with real logic behind it, and the
// one most likely to be mistaken for diarization, so it is tested on its own — no
// Android, no backend, no Compose.
class SpeakerHeuristicTest {

    @Test
    fun `empty transcript yields no voices`() {
        assertEquals(emptyList<Int>(), SpeakerHeuristic.assign(emptyList()))
    }

    @Test
    fun `a single segment is always voice zero`() {
        assertEquals(listOf(0), SpeakerHeuristic.assign(listOf(0)))
    }

    @Test
    fun `back to back segments stay on one voice`() {
        assertEquals(listOf(0, 0, 0), SpeakerHeuristic.assign(listOf(0, 1, 2)))
    }

    @Test
    fun `a long gap flips the voice`() {
        assertEquals(listOf(0, 1, 0), SpeakerHeuristic.assign(listOf(0, 9, 21)))
    }

    @Test
    fun `voice count reports one for a monologue`() {
        assertEquals(1, SpeakerHeuristic.voiceCount(SpeakerHeuristic.assign(listOf(0, 1, 2))))
    }

    @Test
    fun `voice count reports two for an exchange`() {
        assertEquals(2, SpeakerHeuristic.voiceCount(SpeakerHeuristic.assign(listOf(0, 9, 21))))
    }

    @Test
    fun `timespan offsets parse to whole seconds`() {
        assertEquals(0, SpeakerHeuristic.offsetSeconds("00:00:00.0000000"))
        assertEquals(3, SpeakerHeuristic.offsetSeconds("00:00:03.2000000"))
        assertEquals(63, SpeakerHeuristic.offsetSeconds("00:01:03.5000000"))
        assertEquals(3723, SpeakerHeuristic.offsetSeconds("01:02:03"))
    }

    @Test
    fun `a malformed offset degrades to zero rather than throwing`() {
        assertEquals(0, SpeakerHeuristic.offsetSeconds(""))
        assertEquals(0, SpeakerHeuristic.offsetSeconds("not-a-timespan"))
    }
}
