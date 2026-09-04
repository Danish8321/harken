package com.harken.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackCursorTest {

    private val offsets = listOf(0, 4, 9, 30)

    @Test
    fun `the playhead sits in the segment that started last`() {
        assertEquals(0, PlaybackCursor.activeSegment(offsets, 1_000))
        assertEquals(1, PlaybackCursor.activeSegment(offsets, 4_000))
        assertEquals(1, PlaybackCursor.activeSegment(offsets, 8_999))
        assertEquals(2, PlaybackCursor.activeSegment(offsets, 9_000))
    }

    @Test
    fun `the last segment runs to the end of the recording`() {
        assertEquals(3, PlaybackCursor.activeSegment(offsets, 600_000))
    }

    @Test
    fun `nothing is highlighted before the first segment`() {
        assertNull(PlaybackCursor.activeSegment(listOf(12, 20), 5_000))
    }

    @Test
    fun `a transcript with no segments highlights nothing`() {
        assertNull(PlaybackCursor.activeSegment(emptyList(), 5_000))
    }

    @Test
    fun `the clock reads minutes and seconds`() {
        assertEquals("0:00", PlaybackCursor.formatClock(0))
        assertEquals("0:07", PlaybackCursor.formatClock(7_400))
        assertEquals("4:07", PlaybackCursor.formatClock(247_000))
    }

    @Test
    fun `an hour-long recording reads in hours`() {
        assertEquals("1:02:09", PlaybackCursor.formatClock(3_729_000))
    }

    @Test
    fun `the clock never reads negative`() {
        assertEquals("0:00", PlaybackCursor.formatClock(-500))
    }

    @Test
    fun `scrubbing lands proportionally and stays inside the recording`() {
        assertEquals(0, PlaybackCursor.seekTarget(0f, 60_000))
        assertEquals(30_000, PlaybackCursor.seekTarget(0.5f, 60_000))
        assertEquals(60_000, PlaybackCursor.seekTarget(1.4f, 60_000))
        assertEquals(0, PlaybackCursor.seekTarget(-0.2f, 60_000))
    }

    @Test
    fun `a recording of unknown length cannot be scrubbed`() {
        assertEquals(0, PlaybackCursor.seekTarget(0.5f, 0))
        assertEquals(0f, PlaybackCursor.progress(5_000, 0), 0f)
    }

    @Test
    fun `progress is the fraction played`() {
        assertEquals(0.25f, PlaybackCursor.progress(15_000, 60_000), 0.001f)
        assertEquals(1f, PlaybackCursor.progress(90_000, 60_000), 0f)
    }
}
