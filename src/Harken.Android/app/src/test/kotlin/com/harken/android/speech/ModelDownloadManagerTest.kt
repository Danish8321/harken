package com.harken.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException

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
    fun `a stale partial download is discarded`() {
        val tmp = partial(bytes = 2048)
        tmp.setLastModified(NOW - ModelDownloadManager.StalePartialAgeMs - 1)

        val freed = manager().discardPartialDownload(now = NOW)

        assertEquals(2048L, freed)
        assertFalse("partial file still on disk", tmp.exists())
    }

    @Test
    fun `a recent partial download is kept so the retry can resume from it`() {
        val tmp = partial(bytes = 2048)
        tmp.setLastModified(NOW - 60_000)

        val freed = manager().discardPartialDownload(now = NOW)

        assertEquals("a resumable partial must not be counted as reclaimed", 0L, freed)
        assertTrue("resume point was deleted", tmp.exists())
    }

    @Test
    fun `discarding reports nothing when there is no partial download`() {
        assertEquals(0L, manager().discardPartialDownload())
    }

    @Test
    fun `discarding never touches the real model`() {
        val model = model()
        partial(bytes = 16).setLastModified(NOW - ModelDownloadManager.StalePartialAgeMs - 1)

        manager().discardPartialDownload(now = NOW)

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

    @Test
    fun `a dropped connection is reported as a lost connection, not as socket text`() {
        // The exact type OkHttp raises when the network goes away mid-transfer. It used to
        // reach the screen verbatim as "Software caused connection abort".
        assertEquals(
            ModelDownloadFailure.NoConnection,
            ModelDownloadFailure.of(SocketException("Software caused connection abort")),
        )
        assertEquals(
            ModelDownloadFailure.NoConnection,
            ModelDownloadFailure.of(UnknownHostException("github.com")),
        )
    }

    @Test
    fun `a full disk is told apart from a broken server`() {
        assertEquals(
            ModelDownloadFailure.OutOfSpace,
            ModelDownloadFailure.of(IOException("write failed: ENOSPC (No space left on device)")),
        )
        assertEquals(
            ModelDownloadFailure.ServerUnavailable,
            ModelDownloadFailure.of(IOException("Model download failed: HTTP 503")),
        )
    }

    @Test
    fun `anything unrecognised falls back rather than leaking its message`() {
        assertEquals(ModelDownloadFailure.Unknown, ModelDownloadFailure.of(IllegalStateException("boom")))
    }
}

private const val NOW = 1_756_900_000_000L
