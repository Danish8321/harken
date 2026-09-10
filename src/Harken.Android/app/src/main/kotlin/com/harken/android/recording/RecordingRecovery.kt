package com.harken.android.recording

import android.util.Log
import com.harken.android.audio.WavFormat
import com.harken.android.audio.WavWriter
import com.harken.android.data.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID

private const val TAG = "RecordingRecovery"

/**
 * Reunites recordings on disk with the database.
 *
 * The session row is written when the recording stops, so a process death mid-capture —
 * the foreground service killed for memory, or a crash — left the WAV on disk with no row
 * pointing at it and its length header still a placeholder. The audio was intact and
 * completely unreachable: nothing in the app ever listed it, and [WavWriter.repairHeader],
 * written for exactly this, was never called from anywhere.
 *
 * Runs on every launch. It is cheap (a directory listing against a list of ids) and safe
 * to repeat: a file that already has a session is left alone, and nothing is ever deleted.
 * The one file it must not touch is the one being written this moment — see [inProgressId].
 */
class RecordingRecovery(
    private val filesDir: File,
    private val repository: SessionRepository,
    private val knownIds: suspend () -> List<UUID>,
    /**
     * The capture running right now, if there is one. Its WAV has no session row yet and
     * never looks any different from a WAV left by a process death — the row is written
     * when the recording stops (ARC-016) — so without this it is adopted mid-capture.
     *
     * Reachable in normal use since the share target: an `ACTION_SEND` arriving while the
     * microphone is open starts a second `MainActivity` in its own task, and every launch
     * runs recovery. What it cost on the test phone was a Session dated to the moment the
     * share arrived and 30 seconds short, and then the recorder's own save failing on the
     * primary key it had already been given.
     */
    private val inProgressId: () -> UUID? = { RecordingState.recordingId },
) {
    suspend fun recover(): List<UUID> =
        withContext(Dispatchers.IO) {
            val known =
                runCatching { knownIds().toSet() }.getOrElse {
                    Log.e(TAG, "Could not read known session ids; skipping recovery", it)
                    return@withContext emptyList()
                }
            val orphans = orphanRecordings(filesDir.listFiles()?.toList().orEmpty(), known, inProgressId())

            orphans.mapNotNull { orphan ->
                runCatching {
                    val repaired = WavWriter.repairHeader(orphan.file.path)
                    repository.createLocalSession(
                        id = orphan.id,
                        startedAt = orphan.startedAt.toString(),
                        endedAt = orphan.endedAt.toString(),
                        filePath = orphan.file.path,
                        durationSeconds = orphan.durationSeconds,
                    )
                    Log.i(TAG, "Recovered ${orphan.id} (${orphan.durationSeconds}s, header repaired=$repaired)")
                    orphan.id
                }.getOrElse {
                    Log.e(TAG, "Could not recover ${orphan.file.name}", it)
                    null
                }
            }
        }

    data class Orphan(
        val id: UUID,
        val file: File,
        val durationSeconds: Int,
        val startedAt: Instant,
        val endedAt: Instant,
    )

    companion object {
        /**
         * The WAVs in [files] that hold audio but no session, newest first.
         *
         * A recording is identified by its own filename — the service names each file for
         * the recording id — so this survives the file being re-listed in any order and
         * never invents an id that a row could not be matched to later.
         *
         * The clock is the file's own mtime, which is when the last chunk was written, so
         * the capture is dated to when it happened rather than to when the app next opened.
         */
        fun orphanRecordings(
            files: List<File>,
            knownIds: Set<UUID>,
            inProgressId: UUID? = null,
        ): List<Orphan> =
            files
                .filter { it.isFile && it.extension.equals("wav", ignoreCase = true) }
                .mapNotNull { file ->
                    val id = runCatching { UUID.fromString(file.nameWithoutExtension) }.getOrNull() ?: return@mapNotNull null
                    if (id in knownIds || id == inProgressId) return@mapNotNull null

                    // Header-only files are a start that captured nothing. Left on disk rather
                    // than deleted — recovery's job is to lose nothing, not to tidy up.
                    val audioBytes = file.length() - WavFormat.HEADER_LENGTH
                    if (audioBytes <= 0) return@mapNotNull null

                    val duration = WavFormat.durationSeconds(file)
                    val endedAt = Instant.ofEpochMilli(file.lastModified())
                    Orphan(
                        id = id,
                        file = file,
                        durationSeconds = duration,
                        startedAt = endedAt.minusSeconds(duration.toLong()),
                        endedAt = endedAt,
                    )
                }.sortedByDescending { it.endedAt }
    }
}
