package com.harken.android.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class PartOfDayTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun `reads the part of day in the reader's zone, not UTC`() {
        // 08:11Z is 1:41 pm in UTC+5:30. Read off the UTC string, this was "Morning
        // recording" sitting directly above a card reading "1:41 pm".
        assertEquals(PartOfDay.Afternoon, PartOfDay.of("2026-09-04T08:11:00Z", kolkata))
    }

    @Test
    fun `covers every part of day`() {
        assertEquals(PartOfDay.Morning, PartOfDay.of("2026-09-04T03:00:00Z", kolkata)) // 08:30
        assertEquals(PartOfDay.Afternoon, PartOfDay.of("2026-09-04T09:30:00Z", kolkata)) // 15:00
        assertEquals(PartOfDay.Evening, PartOfDay.of("2026-09-04T13:30:00Z", kolkata)) // 19:00
        assertEquals(PartOfDay.LateNight, PartOfDay.of("2026-09-04T20:30:00Z", kolkata)) // 02:00
    }

    @Test
    fun `falls back to the raw hour for a value that is not an instant`() {
        // Rows written before this method existed, and anything hand-edited.
        assertEquals(PartOfDay.Evening, PartOfDay.of("2026-09-04T19:00:00", kolkata))
    }

    @Test
    fun `an unreadable timestamp has no part of day rather than a wrong one`() {
        // Which is what the name resolves through: Unknown is titled "Recording", not
        // "Late night recording" — the old fallback would have guessed.
        assertEquals(PartOfDay.Unknown, PartOfDay.of("not a timestamp", kolkata))
        assertEquals(PartOfDay.Unknown, PartOfDay.of("", kolkata))
    }
}
