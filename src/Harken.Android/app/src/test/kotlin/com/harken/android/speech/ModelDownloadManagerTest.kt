package com.harken.android.speech

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A dropped connection ends the read loop normally — the stream just stops — so without a
 * length check the manager renames a truncated file into place and the user gets a model
 * that fails to load with no reason to suspect the download.
 */
class ModelDownloadManagerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun clientServing(payload: String, declaredLength: Long): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body: ResponseBody = Buffer().writeUtf8(payload)
                    .asResponseBody("application/octet-stream".toMediaType(), declaredLength)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body)
                    .build()
            }
            .build()

    private fun managerWriting(into: File, payload: String, declaredLength: Long) =
        ModelDownloadManager(into, clientServing(payload, declaredLength))

    @Test
    fun `a complete download is promoted to the real model file`() = runTest {
        val dir = temp.newFolder()
        val payload = "whisper weights"

        val result = managerWriting(dir, payload, payload.length.toLong()).ensureModel()

        assertTrue(result.isSuccess)
        val model = File(dir, ModelDownloadManager.ModelFileName)
        assertTrue(model.exists())
        assertEquals(payload, model.readText())
    }

    @Test
    fun `a truncated download never becomes the model file`() = runTest {
        val dir = temp.newFolder()

        val result = managerWriting(dir, "half a mod", declaredLength = 4096).ensureModel()

        assertTrue(result.isFailure)
        assertFalse(File(dir, ModelDownloadManager.ModelFileName).exists())
    }

    @Test
    fun `a truncated download leaves no partial file behind to be retried into place`() = runTest {
        val dir = temp.newFolder()

        managerWriting(dir, "half a model", declaredLength = 4096).ensureModel()

        assertFalse(File(dir, "${ModelDownloadManager.ModelFileName}.tmp").exists())
    }

    @Test
    fun `a response with no declared length is refused rather than trusted`() = runTest {
        val dir = temp.newFolder()

        val result = managerWriting(dir, "unverifiable", declaredLength = -1).ensureModel()

        assertTrue(result.isFailure)
        assertFalse(File(dir, ModelDownloadManager.ModelFileName).exists())
    }

    @Test
    fun `an already-present model is not re-downloaded`() = runTest {
        val dir = temp.newFolder()
        val existing = File(dir, ModelDownloadManager.ModelFileName)
        existing.writeText("already here")

        // Serving a truncated body would fail if a download were attempted at all.
        val result = managerWriting(dir, "", declaredLength = 4096).ensureModel()

        assertTrue(result.isSuccess)
        assertEquals("already here", existing.readText())
    }
}
