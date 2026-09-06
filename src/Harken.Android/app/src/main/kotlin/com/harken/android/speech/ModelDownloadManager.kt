package com.harken.android.speech

import android.content.Context
import android.util.Log
import com.harken.android.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.coroutines.cancellation.CancellationException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLException

private const val TAG = "ModelDownloadManager"

/**
 * Seam so TranscriptionCoordinator can be unit-tested with a hand-written fake instead of
 * the real [ModelDownloadManager] (whose Context/OkHttp dependencies aren't available on
 * the JVM test runner without Robolectric).
 */
interface ModelProvider {
    suspend fun ensureModel(): Result<String>
}

/**
 * The transferred file is not the model. Its own type because it is the one download
 * failure that is neither a network fault nor worth resuming from: the bytes already on
 * disk are wrong, so the retry has to start over.
 */
class ModelIntegrityException(message: String) : IOException(message)

/**
 * Why a model download failed, in terms a person can act on.
 *
 * The UI used to show `Throwable.message` directly, which put *"Software caused connection
 * abort"* on screen when the network dropped mid-download — a socket's words, not an
 * instruction. Classified here rather than in either view model because both of them showed
 * the same raw text, and a second copy of this logic is how they would drift apart.
 */
enum class ModelDownloadFailure {
    /** The phone could not reach the network at all, or lost it mid-stream. */
    NoConnection,

    /** The network worked; the server refused or broke the transfer. */
    ServerUnavailable,

    /** The model did not fit. */
    OutOfSpace,

    /** The transfer completed and delivered something that is not the model. */
    Corrupt,

    Unknown,

    ;

    companion object {
        fun of(error: Throwable): ModelDownloadFailure = when (error) {
            // UnknownHostException is DNS with no network; SocketException covers the
            // connection dying underneath a transfer already in progress, which is what
            // switching off wifi mid-download actually produces.
            is UnknownHostException, is SocketException, is SocketTimeoutException -> NoConnection
            is SSLException -> NoConnection
            // Ahead of the general IOException branch, which this is a subclass of.
            is ModelIntegrityException -> Corrupt
            is IOException -> if (error.isOutOfSpace()) OutOfSpace else ServerUnavailable
            else -> Unknown
        }

        private fun IOException.isOutOfSpace(): Boolean =
            message?.contains("ENOSPC", ignoreCase = true) == true ||
                message?.contains("No space left", ignoreCase = true) == true
    }
}

/**
 * Ensures the on-device whisper.cpp model file is present, downloading it on first use
 * (ADR-0011: no bundled model, lazy fetch on first recording). A killed or failed
 * download must never leave a corrupt file mistaken for a real model, so the download is
 * written to a `.tmp` sibling and only renamed to the final path after the stream
 * completes successfully.
 */
class ModelDownloadManager(
    private val filesDir: File,
    private val client: OkHttpClient = OkHttpClient(),
    /** Overridden only by tests, which cannot produce 148 MB that hashes to the real model. */
    private val expectedSha256: String = ModelSha256,
) : ModelProvider {
    constructor(context: Context) : this(context.filesDir)

    private val modelsDir: File
        get() = File(filesDir, "models")

    private val modelFile: File
        get() = File(modelsDir, ModelFileName)

    private val partialFile: File
        get() = File(modelsDir, "$ModelFileName.tmp")

    /** True if the model has already been downloaded and is ready to load. */
    fun isModelPresent(): Boolean = modelFile.exists()


    /**
     * Deletes a partial download that is too old to be worth resuming, and reports how many
     * bytes that freed. A download killed mid-stream (swipe-away, low-memory kill, crash)
     * leaves up to 148 MB of the user's storage held by a file the app never mentions —
     * measured on a Nothing Phone 2 at 86 MB.
     *
     * Only *stale* partials go. A recent one is the resume point for the retry the user is
     * about to make: [streamTo] sends a `Range` header for whatever is already on disk, so
     * deleting it at launch would cost them the whole 148 MB again. After [StalePartialAge]
     * the user has plainly moved on, and the server may no longer serve a matching range
     * anyway.
     *
     * Called at launch, alongside the recording orphan sweep, and deliberately skipped while
     * a download is in flight: re-entering the activity during a download (a rotation is
     * enough) must not delete the file the download is still writing.
     */
    fun discardPartialDownload(now: Long = System.currentTimeMillis()): Long {
        if (downloadInFlight.get()) return 0L

        val bytes = partialFile.length()
        if (bytes == 0L) return 0L

        val ageMs = now - partialFile.lastModified()
        if (ageMs < StalePartialAgeMs) {
            Telemetry.event("model_partial_kept", "bytes" to bytes, "ageMs" to ageMs)
            return 0L
        }
        if (!partialFile.delete()) return 0L

        Telemetry.event("model_partial_discarded", "bytes" to bytes, "ageMs" to ageMs)
        return bytes
    }

    /**
     * Returns the absolute path to the model file, downloading it first if missing.
     * Safe to call repeatedly — a no-op once the model is present.
     */
    override suspend fun ensureModel(): Result<String> = withContext(Dispatchers.IO) {
        if (modelFile.exists()) {
            return@withContext Result.success(modelFile.absolutePath)
        }

        runCatchingDownload {
            // The lock, rather than the AtomicBoolean that only ever guarded the cleanup
            // (ARC-019). Without it two callers open FileOutputStream(.tmp, append = true)
            // on the same file and interleave their writes into it — and since both append
            // toward the same Content-Length, the result is often exactly the right length.
            // Held for the whole transfer, so waiting here can mean minutes: a suspended
            // coroutine on the IO dispatcher, not a blocked thread.
            downloadLock.withLock {
                // Re-checked inside the lock. A caller that waited was waiting for this
                // very file, and fetching it again is the work the lock exists to avoid.
                if (!modelFile.exists()) {
                    modelsDir.mkdirs()
                    downloadTo(partialFile)
                    installPartial()
                }
            }
            modelFile.absolutePath
        }.onFailure { e ->
            Log.e(TAG, "ensureModel download failed", e)
        }
    }

    /**
     * Verifies the completed partial, then moves it over the installed model, replacing it
     * atomically so a failure can never leave the user with neither file.
     *
     * Verified before the move rather than after, so the installed path is never briefly
     * the wrong file: an update that fetches a bad object leaves the working model exactly
     * where it was.
     */
    private fun installPartial() {
        verifyPartial()
        try {
            Files.move(
                partialFile.toPath(),
                modelFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            throw IOException("Failed to move downloaded model into place at ${modelFile.absolutePath}", e)
        }
    }

    /**
     * Fails the download unless the bytes on disk are the model.
     *
     * The length check in [streamTo] is a truncation check, not an integrity one. A `.tmp`
     * written against one release asset and resumed against another — a re-pointed tag, a
     * stale CDN object, a proxy in between — is exactly the right length and the wrong
     * file, and what loads it is `nativeLoadModel`, in C++, in this process. So the bytes
     * are hashed before anything else is allowed to see them (ARC-005). One streamed read
     * of 148 MB: no extra memory, 126 ms on the reference device against a 21-second
     * download, once per download.
     *
     * A file that fails is deleted rather than kept as a resume point — resuming would
     * append to bytes already known to be wrong.
     */
    private fun verifyPartial() {
        val startNanos = System.nanoTime()
        val actual = sha256(partialFile)
        val elapsedMs = Telemetry.elapsedMsSince(startNanos)
        if (actual == expectedSha256) {
            Telemetry.event("model_verified", "bytes" to partialFile.length(), "elapsedMs" to elapsedMs)
            return
        }

        val bytes = partialFile.length()
        partialFile.delete()
        Telemetry.event(
            "model_integrity_failed",
            "bytes" to bytes,
            "sha256" to actual,
            "elapsedMs" to elapsedMs,
        )
        throw ModelIntegrityException("Downloaded model did not match the expected checksum")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * [runCatching] without swallowing cancellation. Plain `runCatching` catches
     * `CancellationException` too, which turns "the user left the screen" into "the
     * download failed" and breaks structured concurrency at this boundary.
     *
     * A cancelled download keeps its partial file, exactly as a failed one does — the user
     * who backs out of onboarding and returns is the case resume exists for.
     */
    private inline fun <T> runCatchingDownload(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    /**
     * Emits download progress as a percentage (0-100) while [ensureModel] would perform a
     * download, then completes. If the model is already present, emits 100 immediately —
     * unless [replaceExisting], the Settings "update" action, which re-fetches regardless.
     *
     * An update downloads to the `.tmp` sibling and only replaces the installed model once
     * the transfer has completed, exactly as a first-run download does. Settings used to
     * delete the model *before* starting, so an update interrupted by a dropped connection
     * left the user with no model at all and transcription unavailable until a later
     * attempt happened to succeed — verified on a Nothing Phone 2 before this changed.
     */
    fun downloadProgress(replaceExisting: Boolean = false): Flow<Int> = callbackFlow {
        if (modelFile.exists() && !replaceExisting) {
            trySend(100)
            close()
            return@callbackFlow
        }

        withContext(Dispatchers.IO) {
            runCatchingDownload {
                downloadLock.withLock {
                    // An update that starts while a first-run download is still running
                    // waits for it, and then finds the model already installed.
                    if (modelFile.exists() && !replaceExisting) return@withLock
                    modelsDir.mkdirs()
                    downloadTo(partialFile) { percent -> trySend(percent) }
                    installPartial()
                }
            }.onFailure { e ->
                Log.e(TAG, "downloadProgress failed", e)
                close(e)
                return@withContext
            }
        }

        close()
        awaitClose { }
    }

    private fun downloadTo(destination: File, onProgress: ((Int) -> Unit)? = null) {
        downloadInFlight.set(true)
        val startNanos = System.nanoTime()
        Telemetry.event("model_download_started")
        try {
            streamTo(destination, onProgress)
            Telemetry.event(
                "model_download_finished",
                "outcome" to "succeeded",
                "bytes" to destination.length(),
                "elapsedMs" to Telemetry.elapsedMsSince(startNanos),
            )
        } catch (e: Throwable) {
            // Reported here rather than at the call sites because both of them wrap the
            // download in runCatching, which cannot tell a cancelled download from a
            // failed one by the time it sees the Result.
            Telemetry.event(
                "model_download_finished",
                "outcome" to "failed",
                "error" to Telemetry.describe(e),
                "bytes" to destination.length(),
                "elapsedMs" to Telemetry.elapsedMsSince(startNanos),
            )
            throw e
        } finally {
            downloadInFlight.set(false)
        }
    }

    private fun streamTo(destination: File, onProgress: ((Int) -> Unit)?) {
        // Resume where a previous attempt stopped. The model is ~148 MB, and restarting
        // from zero on every dropped connection is how a download on a flaky mobile link
        // never finishes — each attempt costs the user the full 148 MB of data again.
        val alreadyHave = destination.length()
        val request = Request.Builder()
            .url(MODEL_DOWNLOAD_URL)
            .apply { if (alreadyHave > 0) header("Range", "bytes=$alreadyHave-") }
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Model download failed: HTTP ${response.code}")
            }

            // 206 means the server honoured the Range and is sending the rest. Anything
            // else — a server with no range support, or one that ignored the header — is
            // the whole file again, so the partial has to go rather than be appended to.
            val resuming = response.code == HttpURLConnection.HTTP_PARTIAL && alreadyHave > 0
            val startAt = if (resuming) alreadyHave else 0L

            val body = response.body ?: throw IOException("Model download response had no body")
            val expectedTotal = if (body.contentLength() > 0) body.contentLength() + startAt else -1L

            Telemetry.event(
                "model_download_stream",
                "resumedFromBytes" to startAt,
                "httpCode" to response.code,
                "expectedTotalBytes" to expectedTotal,
            )

            FileOutputStream(destination, resuming).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var bytesWritten = startAt
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesWritten += read
                        if (onProgress != null && expectedTotal > 0) {
                            onProgress(((bytesWritten * 100) / expectedTotal).toInt())
                        }
                    }
                }
            }

            // Nothing downstream can tell a short file from a whole one — whisper.cpp
            // reports a truncated model as a failed load, long after the download claimed
            // success. Checked here, where the expected size is still known.
            if (expectedTotal > 0 && destination.length() != expectedTotal) {
                throw IOException(
                    "Model download truncated: got ${destination.length()} of $expectedTotal bytes",
                )
            }
        }
    }

    companion object {
        const val ModelFileName = "ggml-base.en.bin"

        /**
         * How long a partial download stays resumable. A day covers "I lost signal on the
         * train and finished the download that evening" while still bounding how long the
         * user's storage can be held by a file they cannot see.
         */
        const val StalePartialAgeMs = 24 * 60 * 60 * 1000L

        /**
         * Whether this process is currently writing the partial file. Process-wide rather
         * than per-instance because every caller constructs its own manager, and the one
         * asking whether a partial download is stale is never the one that started it.
         * A dead process leaves it false, which is exactly the case worth cleaning.
         */
        private val downloadInFlight = AtomicBoolean(false)

        /**
         * Serialises the transfer itself. Process-wide for the same reason
         * [downloadInFlight] is: Onboarding and Settings each build their own manager
         * (ARC-014), so a per-instance lock would guard nothing.
         */
        private val downloadLock = Mutex()

        /**
         * SHA-256 of the release asset at [MODEL_DOWNLOAD_URL] — upstream
         * `ggml-base.en.bin`, 147,964,211 bytes, unchanged. Bump this in the same commit
         * that re-points the URL: a mismatch reaches the user as a corrupt download, so a
         * stale constant here looks to them like a broken server.
         */
        const val ModelSha256 = "a03779c86df3323075f5e796cb2ce5029f00ec8869eee3fdfb897afe36c6d002"

        const val MODEL_DOWNLOAD_URL =
            "https://github.com/Danish8321/harken/releases/download/models-v1/ggml-base.en.bin"
    }
}
