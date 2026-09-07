package com.harken.android.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.harken.android.data.ExportItem
import com.harken.android.data.ExportNaming
import com.harken.android.data.TranscriptText
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Locale

private const val TAG = "LibraryExporter"

/**
 * Copies the whole library into a directory the user picked.
 *
 * This is the app's only backup story and it has to be, deliberately: nothing is
 * uploaded, and since ARC-002 nothing is in Auto Backup either, so a lost phone is every
 * recording gone unless the user has taken their own copy (ARC-033). Sharing covers one
 * recording; this covers all of them.
 *
 * Written through the Storage Access Framework rather than a path, so the app never holds
 * a broad storage permission — the user grants one directory, once, for as long as the
 * copy takes.
 *
 * Knows nothing about services, notifications or ViewModels: it is given a resolver, a
 * destination and a list, and it reports what it wrote. That is what makes the naming and
 * the counting testable without a device.
 */
class LibraryExporter(
    private val resolver: ContentResolver,
) {
    data class Progress(
        val done: Int,
        val total: Int,
    )

    data class Report(
        val recordings: Int,
        val audioFiles: Int,
        val transcripts: Int,
        /** Recordings whose WAV is no longer on disk; their transcript was still written. */
        val missingAudio: Int,
        val failed: Int,
        val bytes: Long,
    )

    /**
     * Writes every item to [treeUri], calling [onProgress] once per recording.
     *
     * A file that cannot be written is counted and skipped rather than ending the export:
     * one unreadable recording must not cost the user the other ninety-nine. Cancellation
     * is honoured between files and inside a copy, so stopping a multi-gigabyte export
     * does not mean waiting for the current one to finish.
     */
    suspend fun export(
        treeUri: Uri,
        items: List<ExportItem>,
        onProgress: (Progress) -> Unit,
    ): Report {
        val parent =
            DocumentsContract.buildDocumentUriUsingTree(
                treeUri,
                DocumentsContract.getTreeDocumentId(treeUri),
            )
        val taken = mutableSetOf<String>()
        var audioFiles = 0
        var transcripts = 0
        var missingAudio = 0
        var failed = 0
        var bytes = 0L

        items.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val name = ExportNaming.unique(ExportNaming.baseName(item.startedAt, item.title), taken)

            val audio = item.audioPath?.let(::File)?.takeIf { it.isFile }
            if (audio == null) {
                missingAudio++
            } else {
                val written =
                    runCatching { copy(parent, audio, "$name.wav") }
                        .onFailure { Log.w(TAG, "Could not export audio for item $index", it) }
                        .getOrNull()
                if (written == null) {
                    failed++
                } else {
                    audioFiles++
                    bytes += written
                }
            }

            val text = TranscriptText.file(item.title, item.startedAt, item.lines)
            val ok =
                runCatching { write(parent, "$name.txt", "text/plain", text.toByteArray()) }
                    .onFailure { Log.w(TAG, "Could not export transcript for item $index", it) }
                    .isSuccess
            if (ok) {
                transcripts++
                bytes += text.length.toLong()
            } else {
                failed++
            }

            onProgress(Progress(done = index + 1, total = items.size))
        }

        return Report(items.size, audioFiles, transcripts, missingAudio, failed, bytes)
    }

    private suspend fun copy(
        parent: Uri,
        source: File,
        displayName: String,
    ): Long {
        val target = create(parent, "audio/x-wav", displayName)
        var copied = 0L
        // Hand-rolled rather than copyTo, so a cancelled export stops inside a
        // three-hour recording instead of after it.
        source.inputStream().use { input ->
            resolver.openOutputStream(target)?.use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    copied += read
                }
            } ?: error("No output stream for $displayName")
        }
        return copied
    }

    private fun write(
        parent: Uri,
        displayName: String,
        mimeType: String,
        content: ByteArray,
    ) {
        val target = create(parent, mimeType, displayName)
        resolver.openOutputStream(target)?.use { it.write(content) } ?: error("No output stream for $displayName")
    }

    private fun create(
        parent: Uri,
        mimeType: String,
        displayName: String,
    ): Uri =
        DocumentsContract.createDocument(resolver, parent, mimeType, displayName)
            ?: error("The chosen folder would not accept $displayName")

    companion object {
        private const val BUFFER_BYTES = 64 * 1024

        /** "2.1 GB" — for the line that tells the user how much they just backed up. */
        fun formatBytes(bytes: Long): String {
            val units = listOf("bytes", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024
                unit++
            }
            return when {
                unit == 0 -> "$bytes bytes"
                value >= 100 -> "%.0f %s".format(Locale.getDefault(), value, units[unit])
                else -> "%.1f %s".format(Locale.getDefault(), value, units[unit])
            }
        }
    }
}
