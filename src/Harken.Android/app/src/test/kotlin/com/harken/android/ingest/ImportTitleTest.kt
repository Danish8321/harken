package com.harken.android.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImportTitleTest {
    @Test
    fun `the extension goes and the name stays`() {
        assertEquals("Team sync 2019-03-14", ImportTitle.from("Team sync 2019-03-14.m4a"))
    }

    @Test
    fun `only the last extension is removed`() {
        // A name with dots in it is still a name; "Q3.final" is not an extension.
        assertEquals("Q3.final", ImportTitle.from("Q3.final.mp3"))
    }

    @Test
    fun `a name with no extension survives whole`() {
        assertEquals("voice memo", ImportTitle.from("voice memo"))
    }

    @Test
    fun `the filename is read, not rewritten`() {
        // Underscores and capitalisation are the user's own naming. Prettifying them would
        // be inventing a title rather than reading one.
        assertEquals("PTT-20190314_WA0001", ImportTitle.from("PTT-20190314_WA0001.opus"))
    }

    @Test
    fun `stray whitespace is collapsed`() {
        assertEquals("board meeting", ImportTitle.from("  board   meeting  .wav"))
    }

    @Test
    fun `a very long name is cut rather than allowed to fill the row`() {
        val title = ImportTitle.from("x".repeat(200) + ".m4a")

        assertEquals(ImportTitle.MAX_LENGTH, title?.length)
    }

    @Test
    fun `a name that is nothing but an extension falls back to the derived name`() {
        // Returning null is what hands the Library back its part-of-day derivation.
        assertNull(ImportTitle.from(".opus"))
    }

    @Test
    fun `a blank name falls back to the derived name`() {
        assertNull(ImportTitle.from("   .wav"))
        assertNull(ImportTitle.from(""))
    }

    @Test
    fun `an unnamed file falls back to the derived name`() {
        // A content Uri does not have to carry a display name.
        assertNull(ImportTitle.from(null))
    }
}
