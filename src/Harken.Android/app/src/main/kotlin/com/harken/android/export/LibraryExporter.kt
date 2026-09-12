package com.harken.android.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.harken.android.data.ExportItem
import com.harken.android.data.ExportNaming
import com.harken.android.data.TranscriptText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.Locale

private const val TAG = "LibraryExporter"

/**
 * One file being written into the destination folder.
 *
 * [discard] exists because a document is created before it is filled, so every way a copy
 * can end early — the user cancelling, the card being pulled, the destination filling up —
 * leaves a real file in the user's backup folder holding part of a recording (ARC-060).
 */
interface ExportDocument : Closeable {
    val stream: OutputStream

    /** Removes the document. For a write that started and did not finish. */
    fun discard()
}

/**
 * Where an export writes. The seam that keeps [LibraryExporter] off `DocumentsContract`,
 * whose statics are unmocked stubs on the JVM — without it the copy loop, which is the whole
 * of the app's backup story, could only be exercised on a device behind a folder picker.
 */
interface ExportDestination {
    /** Creates [displayName] and opens it for writing. Throws if the folder refuses. */
    fun create(
        mimeType: String,
        displayName: String,
    ): ExportDocument
}

/** The directory the user picked, through the Storage Access Framework. */
class SafDestination(
    private val resolver: ContentResolver,
    treeUri: Uri,
) : ExportDestination {
    private val parent =
        DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )

    override fun create(
        mimeType: String,
        displayName: String,
    ): ExportDocument {
        val uri =
            DocumentsContract.createDocument(resolver, parent, mimeType, displayName)
                ?: error("The chosen folder would not accept $displayName")
        val stream =
            resolver.openOutputStream(uri) ?: run {
                // Created but unopenable: remove it rather than leave a zero-byte file
                // standing in for a recording.
                delete(resolver, uri)
                error("No output stream for $displayName")
            }
        return SafDocument(resolver, uri, stream)
    }

    private class SafDocument(
        private val resolver: ContentResolver,
        private val uri: Uri,
        override val stream: OutputStream,
    ) : ExportDocument {
        override fun discard() {
            stream.close()
            delete(resolver, uri)
        }

        override fun close() = stream.close()
    }

    private companion object {
        fun delete(
            resolver: ContentResolver,
            uri: Uri,
        ) {
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
                .onFailure { Log.w(TAG, "Could not remove the unfinished $uri", it) }
        }
    }
}

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
 * Knows nothing about services, notifications or ViewModels: it is given a destination and
 * a list, and it reports what it wrote. That is what makes the naming, the counting and the
 * handling of a half-written file testable without a device.
 */
class LibraryExporter(
    private val destination: ExportDestination,
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
     * Writes every item, calling [onProgress] once per recording.
     *
     * A file that cannot be written is counted and skipped rather than ending the export:
     * one unreadable recording must not cost the user the other ninety-nine. Cancellation
     * is honoured between files and inside a copy, so stopping a multi-gigabyte export
     * does not mean waiting for the current one to finish.
     */
    suspend fun export(
        items: List<ExportItem>,
        onProgress: (Progress) -> Unit,
    ): Report {
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
                // Not runCatching: it catches Throwable, so a cancellation was counted as
                // this recording's copy failing and the loop carried on to write its
                // transcript — a Cancel tap left one more file in the folder than the user
                // had watched the count reach, and the report called it a failure (ARC-060).
                val written =
                    try {
                        copy(audio, "$name.wav")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not export audio for item $index", e)
                        null
                    }
                if (written == null) {
                    failed++
                } else {
                    audioFiles++
                    bytes += written
                }
            }

            // The bytes that reach the folder, not the characters: a transcript with an
            // accent in it is longer as UTF-8 than as a String, and this number is what the
            // user is told they backed up.
            val text = TranscriptText.file(item.title, item.startedAt, item.lines).toByteArray()
            val ok =
                try {
                    write("$name.txt", "text/plain", text)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Could not export transcript for item $index", e)
                    false
                }
            if (ok) {
                transcripts++
                bytes += text.size.toLong()
            } else {
                failed++
            }

            onProgress(Progress(done = index + 1, total = items.size))
        }

        return Report(items.size, audioFiles, transcripts, missingAudio, failed, bytes)
    }

    private suspend fun copy(
        source: File,
        displayName: String,
    ): Long {
        val document = destination.create("audio/x-wav", displayName)
        var copied = 0L
        var finished = false
        try {
            // Hand-rolled rather than copyTo, so a cancelled export stops inside a
            // three-hour recording instead of after it.
            source.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    document.stream.write(buffer, 0, read)
                    copied += read
                }
            }
            document.close()
            finished = true
        } finally {
            // The one thing this export must never do is leave something in the folder that
            // looks like a backup of a recording and holds part of one. Cancelling is the
            // reachable way here — it is a button on the notification — and an I/O failure
            // mid-copy is the other (ARC-060).
            if (!finished) document.discard()
        }
        return copied
    }

    private fun write(
        displayName: String,
        mimeType: String,
        content: ByteArray,
    ) {
        val document = destination.create(mimeType, displayName)
        var finished = false
        try {
            document.stream.write(content)
            document.close()
            finished = true
        } finally {
            if (!finished) document.discard()
        }
    }

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
