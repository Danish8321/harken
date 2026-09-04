package com.harken.android.speech

import android.content.Context
import android.util.Log
import com.harken.android.telemetry.Telemetry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

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
 * Ensures the on-device whisper.cpp model file is present, downloading it on first use
 * (ADR-0011: no bundled model, lazy fetch on first recording). A killed or failed
 * download must never leave a corrupt file mistaken for a real model, so the download is
 * written to a `.tmp` sibling and only renamed to the final path after the stream
 * completes successfully.
 */
class ModelDownloadManager(
    private val filesDir: File,
    private val client: OkHttpClient = OkHttpClient(),
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
     * Deletes the current model file so the next [ensureModel]/[downloadProgress] call
     * re-downloads it. Used by the Settings "update model" action — same model URL today,
     * but this is also the path a future model-version bump would use.
     */
    fun deleteModel() {
        modelFile.delete()
    }

    /**
     * Deletes a partial download left behind by a *dead* process, and reports how many
     * bytes that freed. A download that fails cleans up after itself; one killed mid-stream
     * (swipe-away, low-memory kill, crash) cannot, and measured on a Nothing Phone 2 that
     * leaves up to 148 MB of the user's storage held by a file nothing will ever read —
     * the next attempt truncates it rather than resuming, so it is dead weight from the
     * moment the process dies.
     *
     * Called at launch, alongside the recording orphan sweep, and deliberately skipped
     * while a download is in flight: re-entering the activity during a download (a rotation
     * is enough) must not delete the file the download is still writing.
     */
    fun discardPartialDownload(): Long {
        if (downloadInFlight.get()) return 0L

        val bytes = partialFile.length()
        if (!partialFile.delete()) return 0L

        Telemetry.event("model_partial_discarded", "bytes" to bytes)
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

        runCatching {
            modelsDir.mkdirs()
            downloadTo(partialFile)

            if (!partialFile.renameTo(modelFile)) {
                throw IOException("Failed to move downloaded model into place at ${modelFile.absolutePath}")
            }

            modelFile.absolutePath
        }.onFailure { e ->
            Log.e(TAG, "ensureModel download failed", e)
            // Never leave a partial file behind to be mistaken for a real model.
            partialFile.delete()
        }
    }

    /**
     * Emits download progress as a percentage (0-100) while [ensureModel] would perform a
     * download, then completes. If the model is already present, emits 100 immediately.
     * Emits -1 if the server did not report a Content-Length (progress unknown).
     */
    fun downloadProgress(): Flow<Int> = callbackFlow {
        if (modelFile.exists()) {
            trySend(100)
            close()
            return@callbackFlow
        }

        withContext(Dispatchers.IO) {
            runCatching {
                modelsDir.mkdirs()
                downloadTo(partialFile) { percent -> trySend(percent) }

                if (!partialFile.renameTo(modelFile)) {
                    throw IOException("Failed to move downloaded model into place at ${modelFile.absolutePath}")
                }
            }.onFailure { e ->
                Log.e(TAG, "downloadProgress failed", e)
                partialFile.delete()
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
                "error" to e.javaClass.simpleName,
                "bytes" to destination.length(),
                "elapsedMs" to Telemetry.elapsedMsSince(startNanos),
            )
            throw e
        } finally {
            downloadInFlight.set(false)
        }
    }

    private fun streamTo(destination: File, onProgress: ((Int) -> Unit)?) {
        val request = Request.Builder().url(MODEL_DOWNLOAD_URL).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Model download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Model download response had no body")
            val contentLength = body.contentLength()

            destination.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Long = 0
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesRead += read
                        if (onProgress != null && contentLength > 0) {
                            onProgress(((bytesRead * 100) / contentLength).toInt())
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val ModelFileName = "ggml-base.en.bin"

        /**
         * Whether this process is currently writing the partial file. Process-wide rather
         * than per-instance because every caller constructs its own manager, and the one
         * asking whether a partial download is stale is never the one that started it.
         * A dead process leaves it false, which is exactly the case worth cleaning.
         */
        private val downloadInFlight = AtomicBoolean(false)

        const val MODEL_DOWNLOAD_URL =
            "https://github.com/Danish8321/harken/releases/download/models-v1/ggml-base.en.bin"
    }
}
