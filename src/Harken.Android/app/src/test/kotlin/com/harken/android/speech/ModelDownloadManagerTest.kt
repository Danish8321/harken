package com.harken.android.speech

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
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
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

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

    // --- integrity (ARC-005) and the concurrency guard (ARC-019) ---

    /**
     * Serves [bytes] to every request, counting the requests. An interceptor rather than a
     * web server because the point is what the manager does with the response, and a real
     * socket adds nothing to that.
     */
    private fun clientServing(bytes: ByteArray, requests: AtomicInteger = AtomicInteger(0), delayMs: Long = 0) =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                requests.incrementAndGet()
                if (delayMs > 0) Thread.sleep(delayMs)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            }
            .build()

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a download of the right length but the wrong bytes is rejected`() = runBlocking {
        val served = ByteArray(4096) { it.toByte() }
        val result = ModelDownloadManager(temp.root, clientServing(served)).ensureModel()

        assertTrue("a file that is not the model must not install", result.isFailure)
        assertTrue(
            "expected an integrity failure, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is ModelIntegrityException,
        )
        assertFalse(
            "the model path must not hold unverified bytes",
            File(File(temp.root, "models"), ModelDownloadManager.ModelFileName).exists(),
        )
        assertFalse(
            "bytes known to be wrong must not be left as a resume point",
            File(File(temp.root, "models"), "${ModelDownloadManager.ModelFileName}.tmp").exists(),
        )
    }

    @Test
    fun `a download whose hash matches is installed`() = runBlocking {
        val served = ByteArray(4096) { it.toByte() }
        val manager = ModelDownloadManager(temp.root, clientServing(served), sha256Of(served))

        val result = manager.ensureModel()

        assertTrue(result.isSuccess)
        assertTrue(manager.isModelPresent())
        assertEquals(4096, File(result.getOrThrow()).length().toInt())
    }

    @Test
    fun `a corrupt model is reported as corrupt, not as a broken server`() {
        assertEquals(
            ModelDownloadFailure.Corrupt,
            ModelDownloadFailure.of(ModelIntegrityException("Downloaded model did not match the expected checksum")),
        )
    }

    @Test
    fun `two callers share one download instead of interleaving into one file`() = runBlocking {
        val served = ByteArray(4096) { it.toByte() }
        val requests = AtomicInteger(0)
        // Two managers, as Onboarding and Settings really are (ARC-014); the delay makes the
        // second caller arrive while the first is still streaming.
        val client = clientServing(served, requests, delayMs = 200)
        val first = ModelDownloadManager(temp.root, client, sha256Of(served))
        val second = ModelDownloadManager(temp.root, client, sha256Of(served))

        val results = listOf(
            async(Dispatchers.Default) { first.ensureModel() },
            async(Dispatchers.Default) { second.ensureModel() },
        ).map { it.await() }

        assertTrue("both callers must get the model", results.all { it.isSuccess })
        assertEquals("the second caller started its own download", 1, requests.get())
        assertEquals(4096, File(results.first().getOrThrow()).length().toInt())
    }
}

private const val NOW = 1_756_900_000_000L
