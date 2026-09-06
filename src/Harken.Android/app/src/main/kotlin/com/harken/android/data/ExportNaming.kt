package com.harken.android.data

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * What an exported recording is called on the user's own disk.
 *
 * `<date> <time> <title>` — the timestamp first so a directory of a year's recordings
 * sorts chronologically by name alone, which is the order anyone looking for one of them
 * is thinking in. The session id, which is what the file is called inside the app, is
 * meaningless outside it.
 */
object ExportNaming {

    /** Long enough for a real title, short enough to survive every filesystem's path limit. */
    const val MaxTitleChars = 60

    /**
     * Characters no common filesystem accepts in a name.
     *
     * The union of the Windows set and POSIX's separator, not the current platform's:
     * these files are exported precisely so they can be copied somewhere else.
     */
    private val Forbidden = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')

    private val Stamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HHmm", Locale.ROOT)

    fun baseName(startedAtIso: String, title: String, zone: ZoneId = ZoneId.systemDefault()): String {
        val stamp = runCatching { Instant.parse(startedAtIso).atZone(zone).format(Stamp) }.getOrNull()
        val name = sanitize(title)
        return listOfNotNull(stamp, name.ifEmpty { null }).joinToString(" ").ifEmpty { "Recording" }
    }

    /**
     * A title reduced to something a filesystem will take.
     *
     * Trailing dots and spaces go too: Windows silently strips them, which turns two
     * exports that differ only there into one file that overwrites the other.
     */
    fun sanitize(title: String): String = title
        .map { if (it in Forbidden || it.code < 0x20) ' ' else it }
        .joinToString("")
        .replace(Regex(" +"), " ")
        .trim()
        .take(MaxTitleChars)
        .trimEnd(' ', '.')

    /**
     * [base], or `base (2)`, `base (3)` … if that name is already spoken for.
     *
     * Two recordings made in the same minute with the same title are not a mistake to
     * report — they are two recordings — so the export renames rather than skipping or
     * overwriting. [taken] is updated, so a caller loops over its sessions with one set.
     */
    fun unique(base: String, taken: MutableSet<String>): String {
        val key = base.lowercase(Locale.ROOT)
        if (taken.add(key)) return base
        var suffix = 2
        while (!taken.add("$key ($suffix)")) suffix++
        return "$base ($suffix)"
    }
}
