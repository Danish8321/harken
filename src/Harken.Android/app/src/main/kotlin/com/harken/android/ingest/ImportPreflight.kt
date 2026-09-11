package com.harken.android.ingest

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.StatFs
import com.harken.android.audio.WavFormat
import java.io.File

/** What the size check has to say before any byte of an import is written. */
sealed interface PreflightOutcome {
    /** Go ahead without asking. */
    object Fits : PreflightOutcome

    /** It will fit, but it is big enough that the user should be told the price first. */
    data class NeedsConfirmation(
        val recordingBytes: Long,
    ) : PreflightOutcome

    /** Refuse. [requiredBytes] is what it would take, [freeBytes] what there is. */
    data class WontFit(
        val requiredBytes: Long,
        val freeBytes: Long,
    ) : PreflightOutcome
}

/**
 * Decides whether an import can be afforded, before it starts.
 *
 * An import is the one place in this app where the user cannot see what something costs.
 * Recording shows its price as it goes — the elapsed time is on screen, and 115 MB an hour
 * is stated in Settings. A 40-minute m4a is 5 MB in the share sheet and 75 MB once it
 * lands, and there is nothing on screen to suggest that (ADR-0016).
 *
 * The Session Cap is deliberately not consulted here. It bounds a capture, which is
 * open-ended and running on a battery; an import's length is known before it starts and
 * costs nothing to know. A four-hour lecture is a legitimate import and an impossible
 * recording.
 *
 * [assess] is pure so it can be tested; the two readers below are the parts that need a
 * device.
 */
object ImportPreflight {
    /**
     * The size the finished Recording will be.
     *
     * Reads the rate from [WavFormat] rather than restating 32000 — the same constant
     * having been written out by hand in three places is what ARC-054 was.
     */
    fun recordingBytes(durationUs: Long): Long = durationUs.coerceAtLeast(0) * WavFormat.BYTES_PER_SECOND / MICROS_PER_SECOND

    /**
     * Everything the import needs free at its peak: the staged source and the decoded
     * Recording exist at the same time, because the source is only released once the
     * decode that reads it has finished.
     */
    fun requiredBytes(
        durationUs: Long,
        sourceBytes: Long,
    ): Long = recordingBytes(durationUs) + sourceBytes.coerceAtLeast(0)

    /**
     * The verdict.
     *
     * A [durationUs] of zero means the container did not say — some streams genuinely do
     * not carry a duration. That is allowed through rather than refused: the decode stages
     * into the cache directory, so a file that turns out not to fit fails there and Android
     * reclaims it, which is a worse experience than a refusal but a better one than
     * refusing every file whose container is merely quiet about its length.
     */
    fun assess(
        durationUs: Long,
        sourceBytes: Long,
        freeBytes: Long,
    ): PreflightOutcome {
        val required = requiredBytes(durationUs, sourceBytes)
        if (required + FREE_SPACE_MARGIN_BYTES > freeBytes) {
            return PreflightOutcome.WontFit(required, freeBytes)
        }
        val recording = recordingBytes(durationUs)
        if (recording >= CONFIRM_ABOVE_BYTES) {
            return PreflightOutcome.NeedsConfirmation(recording)
        }
        return PreflightOutcome.Fits
    }

    /** How long [file] holds, in microseconds, or 0 if it has no audio track or does not say. */
    fun durationUs(file: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var longest = 0L
            for (track in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(track)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!mime.startsWith("audio/")) continue
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    longest = maxOf(longest, format.getLong(MediaFormat.KEY_DURATION))
                }
            }
            longest
        } catch (_: Exception) {
            0L
        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Free space on the volume [dir] lives on. */
    fun freeBytes(dir: File): Long = runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(0L)

    private const val MICROS_PER_SECOND = 1_000_000L

    /**
     * Where an import stops being something to just do and becomes something to agree to.
     *
     * 500 MB is about four and a half hours of audio — past any voice note and past most
     * meetings, so an ordinary import is never interrupted by a dialog, and a lecture
     * series arriving as one file never lands unannounced.
     */
    const val CONFIRM_ABOVE_BYTES = 500L * 1024 * 1024

    /**
     * How much has to be left over afterwards.
     *
     * Android starts behaving badly of its own accord when a device runs near empty, and
     * an import that technically fits but leaves the phone wedged is not a success. This is
     * a floor under the free space an import may consume down to, not a guess at what else
     * the user needs.
     */
    const val FREE_SPACE_MARGIN_BYTES = 256L * 1024 * 1024
}
