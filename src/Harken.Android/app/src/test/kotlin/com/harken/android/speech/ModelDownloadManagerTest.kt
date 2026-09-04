package com.harken.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelDownloadManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun manager() = ModelDownloadManager(temp.root)

    private fun partial(bytes: Int): File {
        val models = File(temp.root, "models").apply { mkdirs() }
        return File(models, "${ModelDownloadManager.ModelFileName}.tmp").apply {
            writeBytes(ByteArray(bytes))
        }
    }

    private fun model(): File {
        val models = File(temp.root, "models").apply { mkdirs() }
        return File(models, ModelDownloadManager.ModelFileName).apply { writeBytes(ByteArray(8)) }
    }

    @Test
    fun `a partial download left by a dead process is discarded`() {
        val tmp = partial(bytes = 2048)

        val freed = manager().discardPartialDownload()

        assertEquals(2048L, freed)
        assertFalse("partial file still on disk", tmp.exists())
    }

    @Test
    fun `discarding reports nothing when there is no partial download`() {
        assertEquals(0L, manager().discardPartialDownload())
    }

    @Test
    fun `discarding never touches the real model`() {
        val model = model()
        partial(bytes = 16)

        manager().discardPartialDownload()

        assertTrue("model was deleted", model.exists())
        assertTrue(manager().isModelPresent())
    }

    @Test
    fun `a partial download is invisible to isModelPresent`() {
        partial(bytes = 1024)

        // The whole point of the .tmp name: a half-downloaded model must never be loaded
        // as if it were whole.
        assertFalse(manager().isModelPresent())
    }
}
