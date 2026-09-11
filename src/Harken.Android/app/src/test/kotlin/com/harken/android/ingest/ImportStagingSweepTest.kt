package com.harken.android.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * ARC-055: the cache files an import leaves when its process dies.
 *
 * What matters as much as deleting them is what the sweep refuses to touch — the cache is
 * shared with everything else in the app, and a sweep that over-reaches deletes another
 * component's working file rather than an import's litter.
 */
class ImportStagingSweepTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun file(
        name: String,
        bytes: Int = 64,
    ): File = folder.newFile(name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `both files an import writes are leftovers`() {
        val staged = file("import-${UUID.randomUUID()}")
        val partial = file("${UUID.randomUUID()}${ImportStaging.PARTIAL_SUFFIX}")

        val leftovers = ImportStaging.leftovers(folder.root.listFiles()!!.toList())

        assertEquals(setOf(staged, partial), leftovers.toSet())
    }

    @Test
    fun `nothing else in the cache is`() {
        file("harken-local.db.lck")
        file("ggml-base.en.bin")
        file("image_manager_disk_cache")
        file("export-2026-09-11.txt")
        // Close, and not an import's: the recordings a capture writes are named for their
        // id and live in filesDir, never here.
        file("${UUID.randomUUID()}.wav")

        assertTrue(ImportStaging.leftovers(folder.root.listFiles()!!.toList()).isEmpty())
    }

    @Test
    fun `a directory is never swept, whatever it is called`() {
        // A listing returns directories too, and one named like a staged file would
        // otherwise be handed to delete().
        folder.newFolder("import-cache")

        assertTrue(ImportStaging.leftovers(folder.root.listFiles()!!.toList()).isEmpty())
    }

    @Test
    fun `sweeping deletes the leftovers and leaves the rest`() {
        val staged = file("import-${UUID.randomUUID()}", bytes = 4096)
        val partial = file("${UUID.randomUUID()}${ImportStaging.PARTIAL_SUFFIX}", bytes = 4096)
        val other = file("harken-local.db.lck")

        val deleted = ImportStaging.sweep(folder.root)

        assertEquals(2, deleted)
        assertTrue(!staged.exists() && !partial.exists())
        assertTrue(other.exists())
    }

    @Test
    fun `sweeping a cache with nothing to sweep is a no-op`() {
        file("harken-local.db.lck")

        assertEquals(0, ImportStaging.sweep(folder.root))
        assertEquals(1, folder.root.listFiles()!!.size)
    }
}
