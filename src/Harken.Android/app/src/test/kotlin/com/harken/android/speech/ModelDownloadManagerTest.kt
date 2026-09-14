package com.harken.android.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
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
        return File(models, "${ModelDownloadManager.MODEL_FILE_NAME}.tmp").apply {
            writeBytes(ByteArray(bytes))
        }
    }

    private fun model(): File {
        val models = File(temp.root, "models").apply { mkdirs() }
        return File(models, ModelDownloadManager.MODEL_FILE_NAME).apply { writeBytes(ByteArray(8)) }
    }

    @Test
    fun `a stale partial download is discarded`() {
        val tmp = partial(bytes = 2048)
        tmp.setLastModified(NOW - ModelDownloadManager.STALE_PARTIAL_AGE_MS - 1)

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
        partial(bytes = 16).setLastModified(NOW - ModelDownloadManager.STALE_PARTIAL_AGE_MS - 1)

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
    private fun clientServing(
        bytes: ByteArray,
        requests: AtomicInteger = AtomicInteger(0),
        delayMs: Long = 0,
    ) = OkHttpClient
        .Builder()
        .addInterceptor { chain ->
            requests.incrementAndGet()
            if (delayMs > 0) Thread.sleep(delayMs)
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
                .build()
        }.build()

    private fun sha256Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `a download of the right length but the wrong bytes is rejected`() =
        runBlocking {
            val served = ByteArray(4096) { it.toByte() }
            val result = ModelDownloadManager(temp.root, clientServing(served)).ensureModel()

            assertTrue("a file that is not the model must not install", result.isFailure)
            assertTrue(
                "expected an integrity failure, got ${result.exceptionOrNull()}",
                result.exceptionOrNull() is ModelIntegrityException,
            )
            assertFalse(
                "the model path must not hold unverified bytes",
                File(File(temp.root, "models"), ModelDownloadManager.MODEL_FILE_NAME).exists(),
            )
            assertFalse(
                "bytes known to be wrong must not be left as a resume point",
                File(File(temp.root, "models"), "${ModelDownloadManager.MODEL_FILE_NAME}.tmp").exists(),
            )
        }

    @Test
    fun `a download whose hash matches is installed`() =
        runBlocking {
            val served = ByteArray(4096) { it.toByte() }
            val manager = ModelDownloadManager(temp.root, clientServing(served), sha256Of(served))

            val result = manager.ensureModel()

            assertTrue(result.isSuccess)
            assertTrue(manager.isModelPresent())
            assertEquals(4096, File(result.getOrThrow()).length().toInt())
        }

    /**
     * Settings' "update" action. The installed model is not deleted first — an update
     * interrupted by a dropped connection must leave the previous one working — so what says
     * the update happened is a second request, not a missing file.
     */
    @Test
    fun `an update re-fetches a model that is already installed`() =
        runBlocking {
            val served = ByteArray(4096) { it.toByte() }
            val requests = AtomicInteger(0)
            val manager = ModelDownloadManager(temp.root, clientServing(served, requests), sha256Of(served))
            manager.ensureModel()
            assertEquals(1, requests.get())

            val percentages = manager.downloadProgress(replaceExisting = true).toList()

            assertEquals("the update must go to the network, model present or not", 2, requests.get())
            assertTrue(manager.isModelPresent())
            // One emission per 64 KB chunk, and this fixture is 4 KB — the percentages look
            // the same as the no-op case below, so the request count is what tells them apart.
            assertEquals(listOf(100), percentages)
        }

    @Test
    fun `a model already installed is reported complete without a request`() =
        runBlocking {
            val served = ByteArray(4096) { it.toByte() }
            val requests = AtomicInteger(0)
            val manager = ModelDownloadManager(temp.root, clientServing(served, requests), sha256Of(served))
            manager.ensureModel()

            val percentages = manager.downloadProgress().toList()

            assertEquals(listOf(100), percentages)
            assertEquals("the model was there; nothing should have been fetched", 1, requests.get())
        }

    @Test
    fun `a corrupt model is reported as corrupt, not as a broken server`() {
        assertEquals(
            ModelDownloadFailure.Corrupt,
            ModelDownloadFailure.of(ModelIntegrityException("Downloaded model did not match the expected checksum")),
        )
    }

    @Test
    fun `two callers share one download instead of interleaving into one file`() =
        runBlocking {
            val served = ByteArray(4096) { it.toByte() }
            val requests = AtomicInteger(0)
            // Two managers, as Onboarding and Settings really are (ARC-014); the delay makes the
            // second caller arrive while the first is still streaming.
            val client = clientServing(served, requests, delayMs = 200)
            val first = ModelDownloadManager(temp.root, client, sha256Of(served))
            val second = ModelDownloadManager(temp.root, client, sha256Of(served))

            val results =
                listOf(
                    async(Dispatchers.Default) { first.ensureModel() },
                    async(Dispatchers.Default) { second.ensureModel() },
                ).map { it.await() }

            assertTrue("both callers must get the model", results.all { it.isSuccess })
            assertEquals("the second caller started its own download", 1, requests.get())
            assertEquals(4096, File(results.first().getOrThrow()).length().toInt())
        }

    // --- cancellation (ARC-063) ---

    /**
     * Serves [served] a chunk at a time with a pause between chunks, honouring `Range`, and
     * records how many bytes it has handed over and what ranges it was asked for.
     *
     * The pause is what makes the test about cancellation at all: a body delivered in one
     * go completes before a collector could ever be cancelled, which is precisely why this
     * defect survived — every existing test downloads 4 KB instantly.
     */
    private fun clientStreamingSlowly(
        served: ByteArray,
        delivered: AtomicInteger,
        ranges: MutableList<String?> = mutableListOf(),
        chunkDelayMs: Long = 20,
    ) = OkHttpClient
        .Builder()
        .addInterceptor { chain ->
            val range = chain.request().header("Range")
            ranges += range
            val from = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val rest = served.copyOfRange(from, served.size)
            Response
                .Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(if (from > 0) HttpURLConnection.HTTP_PARTIAL else 200)
                .message("OK")
                .body(slowBody(rest, delivered, chunkDelayMs))
                .build()
        }.build()

    private fun slowBody(
        bytes: ByteArray,
        delivered: AtomicInteger,
        chunkDelayMs: Long,
    ): ResponseBody {
        val stream =
            object : InputStream() {
                private var position = 0

                override fun read(): Int {
                    val one = ByteArray(1)
                    return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
                }

                override fun read(
                    destination: ByteArray,
                    offset: Int,
                    length: Int,
                ): Int {
                    if (position >= bytes.size) return -1
                    Thread.sleep(chunkDelayMs)
                    val count = minOf(length, CHUNK_BYTES, bytes.size - position)
                    System.arraycopy(bytes, position, destination, offset, count)
                    position += count
                    delivered.addAndGet(count)
                    return count
                }
            }
        return object : ResponseBody() {
            override fun contentType() = "application/octet-stream".toMediaType()

            override fun contentLength() = bytes.size.toLong()

            override fun source(): BufferedSource = stream.source().buffer()
        }
    }

    /** Starts a download and cancels the collector once [afterBytes] have arrived. */
    private suspend fun cancelMidDownload(
        manager: ModelDownloadManager,
        delivered: AtomicInteger,
        afterBytes: Int,
    ) = coroutineScope {
        val job = launch(Dispatchers.Default) { manager.downloadProgress().collect { } }
        while (delivered.get() < afterBytes) delay(5)
        job.cancelAndJoin()
    }

    @Test
    fun `cancelling the collector stops the transfer instead of finishing it`() =
        runBlocking {
            val served = ByteArray(CHUNK_BYTES * 40) { it.toByte() }
            val delivered = AtomicInteger(0)
            val manager = ModelDownloadManager(temp.root, clientStreamingSlowly(served, delivered), sha256Of(served))

            cancelMidDownload(manager, delivered, afterBytes = CHUNK_BYTES * 2)
            val atCancel = delivered.get()
            // Long enough for several more chunks, had anything still been reading.
            delay(300)

            assertEquals("the transfer kept running after the collector went away", atCancel, delivered.get())
            assertTrue("the whole file was fetched anyway", atCancel < served.size)
        }

    @Test
    fun `a cancelled download is not treated as a failed one`() =
        runBlocking {
            val served = ByteArray(CHUNK_BYTES * 40) { it.toByte() }
            val delivered = AtomicInteger(0)
            val manager = ModelDownloadManager(temp.root, clientStreamingSlowly(served, delivered), sha256Of(served))

            val thrown =
                runCatching { cancelMidDownload(manager, delivered, afterBytes = CHUNK_BYTES * 2) }
                    .exceptionOrNull()

            // Anything other than cancellation reaches the user as "the download failed",
            // which is what leaving a screen must never look like.
            assertTrue(
                "expected cancellation, got $thrown",
                thrown == null || thrown is CancellationException,
            )
            assertFalse("a half-transferred file was installed as the model", manager.isModelPresent())
        }

    /**
     * The progress path is stopped by `emit` itself, which suspends. [ModelDownloadManager.ensureModel]
     * emits nothing — it is what [TranscriptionCoordinator] calls — so the only thing that
     * can stop it is the check inside the read loop. Tested separately for exactly that
     * reason: without it this test downloads all 320 KB after the caller has gone.
     */
    @Test
    fun `cancelling ensureModel stops the transfer, with no progress emission to catch it`() =
        runBlocking {
            val served = ByteArray(CHUNK_BYTES * 40) { it.toByte() }
            val delivered = AtomicInteger(0)
            val manager = ModelDownloadManager(temp.root, clientStreamingSlowly(served, delivered), sha256Of(served))

            val job = launch(Dispatchers.Default) { manager.ensureModel() }
            while (delivered.get() < CHUNK_BYTES * 2) delay(5)
            job.cancelAndJoin()
            val atCancel = delivered.get()
            delay(300)

            assertEquals("the transfer kept running after its caller was cancelled", atCancel, delivered.get())
            assertTrue("the whole file was fetched anyway", atCancel < served.size)
            assertFalse(manager.isModelPresent())
        }

    @Test
    fun `the attempt after a cancel resumes from the bytes already on disk`() =
        runBlocking {
            val served = ByteArray(CHUNK_BYTES * 40) { it.toByte() }
            val delivered = AtomicInteger(0)
            val ranges = mutableListOf<String?>()
            val manager =
                ModelDownloadManager(temp.root, clientStreamingSlowly(served, delivered, ranges), sha256Of(served))

            cancelMidDownload(manager, delivered, afterBytes = CHUNK_BYTES * 2)

            val tmp = File(File(temp.root, "models"), "${ModelDownloadManager.MODEL_FILE_NAME}.tmp")
            val keptBytes = tmp.length()
            assertTrue("a cancel threw away the resume point", keptBytes > 0)
            assertTrue("the partial holds more than was ever transferred", keptBytes < served.size)

            val result = manager.ensureModel()

            assertTrue("the resumed download did not complete: ${result.exceptionOrNull()}", result.isSuccess)
            assertEquals(listOf(null, "bytes=$keptBytes-"), ranges)
            assertEquals(served.size.toLong(), File(result.getOrThrow()).length())
        }
}

/** One read from the fake body — small enough that several land before a cancel is noticed. */
private const val CHUNK_BYTES = 8 * 1024

private const val NOW = 1_756_900_000_000L
