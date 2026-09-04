package com.harken.android.data

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DerivedTitleTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")

    @Test
    fun `names the part of day in the reader's zone, not UTC`() {
        // 08:11Z is 1:41 pm in UTC+5:30. Read off the UTC string, this was "Morning
        // recording" sitting directly above a card reading "1:41 pm".
        assertEquals("Afternoon recording", DerivedTitle.of("2026-09-04T08:11:00Z", kolkata))
    }

    @Test
    fun `covers every part of day`() {
        assertEquals("Morning recording", DerivedTitle.of("2026-09-04T03:00:00Z", kolkata)) // 08:30
        assertEquals("Afternoon recording", DerivedTitle.of("2026-09-04T09:30:00Z", kolkata)) // 15:00
        assertEquals("Evening recording", DerivedTitle.of("2026-09-04T13:30:00Z", kolkata)) // 19:00
        assertEquals("Late night recording", DerivedTitle.of("2026-09-04T20:30:00Z", kolkata)) // 02:00
    }

    @Test
    fun `falls back to the raw hour for a value that is not an instant`() {
        // Rows written before this method existed, and anything hand-edited.
        assertEquals("Evening recording", DerivedTitle.of("2026-09-04T19:00:00", kolkata))
        assertEquals("Recording", DerivedTitle.of("not a timestamp", kolkata))
    }
}
