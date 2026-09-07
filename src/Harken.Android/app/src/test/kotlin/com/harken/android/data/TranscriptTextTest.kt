package com.harken.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class TranscriptTextTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun `a timestamp under an hour is minutes and seconds`() {
        assertEquals("0:00", TranscriptText.timestamp(0))
        assertEquals("0:09", TranscriptText.timestamp(9))
        assertEquals("12:22", TranscriptText.timestamp(742))
        assertEquals("59:59", TranscriptText.timestamp(3599))
    }

    @Test
    fun `an hour in, the hour is shown`() {
        // The app records for up to three hours, so "184:09" would be a real output of the
        // minutes-only form — and unreadable.
        assertEquals("1:00:00", TranscriptText.timestamp(3600))
        assertEquals("3:04:09", TranscriptText.timestamp(11049))
    }

    @Test
    fun `a negative offset reads as the start, not as a negative time`() {
        assertEquals("0:00", TranscriptText.timestamp(-5))
    }

    @Test
    fun `each line carries its timestamp`() {
        val body =
            TranscriptText.body(
                listOf(
                    TranscriptText.Line(0, "Morning."),
                    TranscriptText.Line(742, "Let us start with the budget."),
                ),
            )

        assertEquals("[0:00] Morning.\n[12:22] Let us start with the budget.", body)
    }

    @Test
    fun `an empty transcript is an empty body, not a blank line`() {
        assertEquals("", TranscriptText.body(emptyList()))
    }

    @Test
    fun `the file names the recording and when it was made`() {
        val text =
            TranscriptText.file(
                title = "Budget review",
                startedAtIso = "2026-09-05T13:41:00Z",
                lines = listOf(TranscriptText.Line(5, "Hello.")),
                zone = utc,
            )

        val lines = text.lines()
        assertEquals("Budget review", lines[0])
        // Loosely, not to the letter: the month is rendered in the reader's own locale,
        // which is a property of the machine the test happens to run on.
        assertTrue("the header lost the time: ${lines[1]}", lines[1].contains("13:41"))
        assertTrue("the header lost the date: ${lines[1]}", lines[1].contains("2026"))
        assertTrue("the transcript is missing", text.contains("[0:05] Hello."))
    }

    @Test
    fun `an unparseable timestamp is written through rather than dropped`() {
        // Better a header the reader can puzzle over than one that silently says nothing:
        // startedAt comes from the database and a corrupt row still deserves its export.
        val text = TranscriptText.file("Odd one", "not-a-date", emptyList(), utc)

        assertTrue(text.contains("not-a-date"))
    }
}
