package com.harken.android.speech

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

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
 * written to a `.tmp` sibling and only renamed to the final path once the bytes received
 * match the length the server declared.
 */
class ModelDownloadManager(
    private val modelsDir: File,
    private val client: OkHttpClient = OkHttpClient(),
) : ModelProvider {
    /**
     * The app's constructor. The primary one takes a plain directory instead of a
     * Context so the download path can be exercised on the JVM test runner, where
     * Context.filesDir does not exist.
     */
    constructor(context: Context, client: OkHttpClient = OkHttpClient()) :
        this(File(context.filesDir, "models"), client)

    private val modelFile: File
        get() = File(modelsDir, ModelFileName)

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
     * Returns the absolute path to the model file, downloading it first if missing.
     * Safe to call repeatedly — a no-op once the model is present.
     */
    override suspend fun ensureModel(): Result<String> = withContext(Dispatchers.IO) {
        if (modelFile.exists()) {
            return@withContext Result.success(modelFile.absolutePath)
        }

        runCatching {
            modelsDir.mkdirs()
            val tmpFile = File(modelsDir, "$ModelFileName.tmp")
            downloadTo(tmpFile)

            if (!tmpFile.renameTo(modelFile)) {
                throw IOException("Failed to move downloaded model into place at ${modelFile.absolutePath}")
            }

            modelFile.absolutePath
        }.onFailure { e ->
            Log.e(TAG, "ensureModel download failed", e)
            // Never leave a partial file behind to be mistaken for a real model.
            File(modelsDir, "$ModelFileName.tmp").delete()
        }
    }

    /**
     * Emits download progress as a percentage (0-100) while [ensureModel] would perform a
     * download, then completes. If the model is already present, emits 100 immediately.
     * Fails the flow if the download is truncated or its length cannot be verified.
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
                val tmpFile = File(modelsDir, "$ModelFileName.tmp")
                downloadTo(tmpFile) { percent -> trySend(percent) }

                if (!tmpFile.renameTo(modelFile)) {
                    throw IOException("Failed to move downloaded model into place at ${modelFile.absolutePath}")
                }
            }.onFailure { e ->
                Log.e(TAG, "downloadProgress failed", e)
                File(modelsDir, "$ModelFileName.tmp").delete()
                close(e)
                return@withContext
            }
        }

        close()
        awaitClose { }
    }

    private fun downloadTo(destination: File, onProgress: ((Int) -> Unit)? = null) {
        val request = Request.Builder().url(MODEL_DOWNLOAD_URL).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Model download failed: HTTP ${response.code}")
            }

            val body = response.body ?: throw IOException("Model download response had no body")
            val contentLength = body.contentLength()

            var bytesRead: Long = 0
            destination.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8 * 1024)
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

            // A dropped connection ends the read loop normally — the stream just stops.
            // Without this check the caller renames a truncated file into place and the
            // user gets a model that fails to load, with no reason to suspect the
            // download. There is no published checksum for the model, so declared length
            // is the strongest check available; a server that reports no length at all
            // leaves the download unverifiable and is refused rather than trusted.
            if (contentLength <= 0) {
                throw IOException("Model download response declared no length; cannot verify it completed")
            }
            if (bytesRead != contentLength) {
                throw IOException("Model download truncated: got $bytesRead bytes, expected $contentLength")
            }
        }
    }

    companion object {
        const val ModelFileName = "ggml-base.en.bin"

        const val MODEL_DOWNLOAD_URL =
            "https://github.com/Danish8321/harken/releases/download/models-v1/ggml-base.en.bin"
    }
}
