package com.harken.android.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The transcript as plain text, for every copy of it that leaves the app: the clipboard,
 * a share, an exported `.txt`.
 *
 * One formatter rather than three. The session sheet used to build its own string with
 * raw second counts (`[742s] ...`), which is a number no reader can place in a recording
 * — and it would have disagreed with whatever the export wrote.
 */
object TranscriptText {
    data class Line(val offsetSeconds: Int, val text: String)

    /** `4:09`, or `1:04:09` once a recording passes an hour. */
    fun timestamp(offsetSeconds: Int): String {
        val total = offsetSeconds.coerceAtLeast(0)
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(Locale.ROOT, hours, minutes, seconds)
        } else {
            "%d:%02d".format(Locale.ROOT, minutes, seconds)
        }
    }

    fun body(lines: List<Line>): String = lines.joinToString("\n") { "[${timestamp(it.offsetSeconds)}] ${it.text}" }

    /**
     * The whole file: a short header naming the recording, then the transcript.
     *
     * The header exists because an exported directory is read months later, by which
     * point the file name is all the context there is — and a file name is not allowed to
     * carry a colon, so the time in it is unpunctuated. Says where the transcript came
     * from too: these files are the user's own backup, read on a machine that has never
     * heard of this app.
     */
    fun file(
        title: String,
        startedAtIso: String,
        lines: List<Line>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String =
        buildString {
            appendLine(title)
            appendLine(readableTimestamp(startedAtIso, zone))
            appendLine("Recorded and transcribed on the device with Harken.")
            appendLine()
            append(body(lines))
            if (lines.isNotEmpty()) appendLine()
        }

    private fun readableTimestamp(
        startedAtIso: String,
        zone: ZoneId,
    ): String =
        runCatching { Instant.parse(startedAtIso).atZone(zone).format(readable()) }
            .getOrElse { startedAtIso }

    // Built per export, not held in a field: a field captures the locale at class load, so
    // an export taken after the user changed language kept the old month names.
    private fun readable(): DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.getDefault())
}
