package com.harken.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ExportNamingTest {
    private val utc = ZoneId.of("UTC")

    @Test
    fun `a name leads with the timestamp so a folder sorts chronologically`() {
        val name = ExportNaming.baseName("2026-09-05T13:41:00Z", "Budget review", utc)

        assertEquals("2026-09-05 1341 Budget review", name)
    }

    @Test
    fun `the timestamp is read in the reader's zone`() {
        // startedAt is stored as UTC. Reading the hour off the string gave a recording made
        // at 1:41 pm local a file called "0811" — see DerivedTitle, same bug.
        val name = ExportNaming.baseName("2026-09-05T08:11:00Z", "Standup", ZoneId.of("Asia/Kolkata"))

        assertEquals("2026-09-05 1341 Standup", name)
    }

    @Test
    fun `characters no filesystem takes are replaced`() {
        val name = ExportNaming.baseName("2026-09-05T13:41:00Z", "Q3/Q4: plan?", utc)

        assertEquals("2026-09-05 1341 Q3 Q4 plan", name)
    }

    @Test
    fun `a title that is nothing but punctuation still gets a name`() {
        val name = ExportNaming.baseName("2026-09-05T13:41:00Z", "///", utc)

        assertEquals("2026-09-05 1341", name)
    }

    @Test
    fun `an unparseable timestamp falls back to the title alone`() {
        assertEquals("Budget review", ExportNaming.baseName("not-a-date", "Budget review", utc))
        assertEquals("Recording", ExportNaming.baseName("not-a-date", "", utc))
    }

    @Test
    fun `a long title is cut to something a path will hold`() {
        val name = ExportNaming.sanitize("x".repeat(200))

        assertEquals(ExportNaming.MAX_TITLE_CHARS, name.length)
    }

    @Test
    fun `trailing dots and spaces go, because Windows strips them silently`() {
        assertEquals("Notes", ExportNaming.sanitize("Notes. . "))
    }

    @Test
    fun `two recordings that would share a name are numbered, not overwritten`() {
        val taken = mutableSetOf<String>()

        assertEquals("Standup", ExportNaming.unique("Standup", taken))
        assertEquals("Standup (2)", ExportNaming.unique("Standup", taken))
        assertEquals("Standup (3)", ExportNaming.unique("Standup", taken))
    }

    @Test
    fun `names that differ only in case still collide`() {
        // They do on the filesystems these files are most likely to end up on.
        val taken = mutableSetOf<String>()
        val first = ExportNaming.unique("Standup", taken)
        val second = ExportNaming.unique("STANDUP", taken)

        assertNotEquals(first, second)
        assertTrue("the second name should be numbered", second.endsWith("(2)"))
    }
}
