package com.harken.android.telemetry

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rotating file mirror of every [Telemetry.event] line, so a multi-day unattended run
 * survives a reboot and an unattached device — logcat's ring buffer does neither.
 *
 * Three files of [MAX_FILE_BYTES] each: `current.log` is appended to until it crosses the
 * size, then it becomes `current.log.1` (pushing that to `.2`, dropping whatever was in
 * `.2`) and a fresh `current.log` starts. Bounds total disk use to ~6 MB regardless of how
 * long the run goes. Plain text, append-only, one line per call — same shape-only,
 * no-speech content as [Telemetry] itself (ADR-0011); this sink adds durability, not a new
 * category of thing that gets logged.
 */
class FileLogSink(
    private val dir: File,
) {
    private val current = File(dir, "current.log")

    init {
        dir.mkdirs()
    }

    @Synchronized
    fun append(line: String) {
        runCatching {
            current.appendText(TIME_FORMAT.format(Date()) + " " + line + "\n")
            if (current.length() > MAX_FILE_BYTES) rotate()
        }
    }

    /** The rotated files, oldest first, for [LogExport] to bundle. */
    fun files(): List<File> =
        listOf(File(dir, "current.log.2"), File(dir, "current.log.1"), current)
            .filter { it.exists() }

    private fun rotate() {
        val oldest = File(dir, "current.log.2")
        val middle = File(dir, "current.log.1")
        oldest.delete()
        if (middle.exists()) middle.renameTo(oldest)
        current.renameTo(middle)
    }

    companion object {
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
        private val TIME_FORMAT = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT)
    }
}
