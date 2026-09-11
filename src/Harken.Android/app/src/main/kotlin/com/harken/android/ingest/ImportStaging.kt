package com.harken.android.ingest

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.io.File
import java.util.UUID

private const val TAG = "ImportStaging"

/** A copy of the user's file that this app owns, and the name it arrived under. */
data class StagedImport(
    val file: File,
    val displayName: String?,
)

/**
 * Copies the bytes behind a `content://` Uri into the cache directory.
 *
 * Bytes only — no decode, no inspection. This exists because a Uri is not something an
 * import can hold on to: an `ACTION_SEND` grant is one-shot and dies with the activity that
 * received it, and even a picker grant is gone once the process is. [ImportService] runs for
 * minutes afterwards, so the handoff between them has to be a plain file it owns outright.
 *
 * The cache directory, not `filesDir`, for the same reason [AudioImporter] stages there:
 * nothing in `filesDir` may look like a recording until it is one, and Android reclaims a
 * cached file on its own if an import dies before deleting it.
 *
 * Both entry points — the picker and the share sheet — come through here, so there is one
 * import path rather than two that drift.
 */
object ImportStaging {
    fun stage(
        context: Context,
        uri: Uri,
    ): StagedImport? {
        val resolver = context.contentResolver
        val name = displayName(resolver, uri)
        val input = runCatching { resolver.openInputStream(uri) }.getOrNull()
        if (input == null) {
            Log.w(TAG, "Nothing readable behind $uri")
            return null
        }
        // No extension: the name is carried alongside rather than encoded in the path, and
        // guessing one from a mime type would be inventing a fact about the file.
        val target = File(context.cacheDir, "$STAGED_PREFIX${UUID.randomUUID()}")
        return try {
            input.use { source -> target.outputStream().use { out -> source.copyTo(out, BUFFER_BYTES) } }
            StagedImport(target, name)
        } catch (e: Exception) {
            Log.w(TAG, "Could not stage $uri", e)
            target.delete()
            null
        }
    }

    /**
     * Deletes the files an import owns in [cacheDir] and did not live to clean up.
     *
     * Every path an import controls deletes both of its own files — the staged copy and the
     * `.partial.wav` — but a process death controls nothing, and Android reclaims a cache
     * directory only when the device is already low on storage. A staged copy is the size of
     * the file the user picked, so what is left behind is not a stray kilobyte (ARC-055).
     *
     * The caller must know that no import is running: this cannot tell a file abandoned by a
     * dead process from one an import is writing this second, and both entry points can start
     * one before the first frame — a share arrives in its own task, so the launch path runs
     * again while an earlier import is still going. Skipping a launch costs nothing; the next
     * one sweeps.
     *
     * Only [cacheDir]. A WAV in `filesDir` is either a Session's audio or an orphan
     * [com.harken.android.recording.RecordingRecovery] is about to adopt, and deleting there
     * on a guess loses a recording.
     */
    fun sweep(cacheDir: File): Int {
        val leftovers = leftovers(cacheDir.listFiles()?.toList().orEmpty())
        val deleted = leftovers.count { it.delete() }
        if (leftovers.isNotEmpty()) {
            Log.i(TAG, "Swept $deleted of ${leftovers.size} import leftovers from the cache")
        }
        return deleted
    }

    /**
     * The files in [files] that an import wrote: the staged source copy and the decode's
     * partial output. Named by prefix and suffix because that is all an import leaves to
     * recognise them by — the cache also holds other components' files, and this must never
     * reach one of those.
     */
    @VisibleForTesting
    internal fun leftovers(files: List<File>): List<File> =
        files.filter {
            it.isFile && (it.name.startsWith(STAGED_PREFIX) || it.name.endsWith(PARTIAL_SUFFIX))
        }

    /**
     * The name the file arrived under, which becomes the Session's title (ADR-0016).
     *
     * A content Uri does not have to carry one. `lastPathSegment` is the fallback and is
     * often a document id rather than a name — [ImportTitle] is what decides whether what
     * comes back is usable, and returns null when it isn't.
     */
    fun displayName(
        resolver: ContentResolver,
        uri: Uri,
    ): String? {
        val queried =
            runCatching {
                resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
            }.getOrNull()
        return queried ?: uri.lastPathSegment
    }

    private const val STAGED_PREFIX = "import-"
    private const val BUFFER_BYTES = 64 * 1024

    /** [AudioImporter]'s half-written decode. Named here because [sweep] has to recognise it. */
    const val PARTIAL_SUFFIX = ".partial.wav"
}
