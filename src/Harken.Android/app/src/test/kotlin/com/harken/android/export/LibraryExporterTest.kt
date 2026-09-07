package com.harken.android.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryExporterTest {
    @Test
    fun `small sizes are exact bytes`() {
        assertEquals("0 bytes", LibraryExporter.formatBytes(0))
        assertEquals("512 bytes", LibraryExporter.formatBytes(512))
        assertEquals("1023 bytes", LibraryExporter.formatBytes(1023))
    }

    @Test
    fun `larger sizes step up a unit`() {
        assertTrue(LibraryExporter.formatBytes(1024).endsWith(" KB"))
        assertTrue(LibraryExporter.formatBytes(5L * 1024 * 1024).endsWith(" MB"))
        assertTrue(LibraryExporter.formatBytes(3L * 1024 * 1024 * 1024).endsWith(" GB"))
    }

    @Test
    fun `a decimal is shown until it stops being informative`() {
        // "1.5 GB" is worth reading; "512.4 MB" is not, so the fraction is dropped there.
        assertTrue(LibraryExporter.formatBytes(1536L * 1024 * 1024).startsWith("1"))
        assertEquals(
            "the tenth should be dropped over 100",
            "512 MB",
            LibraryExporter.formatBytes(512L * 1024 * 1024),
        )
    }
}
