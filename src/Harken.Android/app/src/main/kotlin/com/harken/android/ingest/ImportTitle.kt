package com.harken.android.ingest

/**
 * The name an imported Session starts life with.
 *
 * A capture has nothing to be named after, so the Library derives one from the clock —
 * "Tuesday morning". An import does have something: the file arrives with a name its owner
 * chose, and "Team sync 2019-03-14" beats any part of day, particularly since an import's
 * `startedAt` is when it was imported and not when the audio happened (ADR-0016).
 *
 * Only the extension is removed. Underscores are left alone, capitalisation is left alone:
 * the filename is the user's own naming, and prettifying it would be this app inventing a
 * title rather than reading one.
 */
object ImportTitle {
    /**
     * The title for a file called [fileName], or null when nothing usable is left — in
     * which case the Library's existing part-of-day derivation applies, exactly as it does
     * for a recording.
     */
    fun from(fileName: String?): String? {
        if (fileName == null) return null
        val withoutExtension = fileName.substringBeforeLast('.', missingDelimiterValue = fileName)
        val collapsed = withoutExtension.replace(WHITESPACE, " ").trim()
        if (collapsed.isEmpty()) return null
        return collapsed.take(MAX_LENGTH).trim().ifEmpty { null }
    }

    private val WHITESPACE = Regex("\\s+")

    /**
     * Where a title stops being a title.
     *
     * Long enough for a descriptive filename to survive intact, short enough that a
     * pathological one cannot push everything else out of a Library row. A truncated title
     * is still editable: rename exists, and the audio is what matters.
     */
    const val MAX_LENGTH = 80
}
