package com.harken.android.data

/**
 * The two pure pieces of transcript search: what SQLite is asked, and what the result row
 * shows.
 *
 * Both live here rather than in the DAO or the composable because both are easy to get
 * wrong in ways only a test catches — an unescaped `%` turns a literal search into a
 * wildcard, and a snippet that clips its own match shows the user a line with no visible
 * reason for being in the results.
 */
object SearchQuery {
    /**
     * The shortest term that is worth a query. One character matches most of a transcript,
     * which is a slow query whose result nobody can read.
     */
    const val MIN_LENGTH = 2

    /** How many segment rows a single search reads, over every session on the device. */
    const val SEGMENT_MATCH_LIMIT = 400

    private const val ELLIPSIS = "\u2026"

    /**
     * Escapes a user's term for `LIKE ... ESCAPE '\'`.
     *
     * `%` and `_` are wildcards in LIKE, so searching for "50%" without this returns every
     * segment containing "50". The backslash has to go first, or it escapes the escapes
     * this adds.
     */
    fun likePattern(term: String): String =
        term
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    /** A window of [text] around the first occurrence of [term], and where the match sits in it. */
    data class Snippet(
        val text: String,
        val matchStart: Int,
        val matchEnd: Int,
    ) {
        val hasMatch: Boolean get() = matchStart >= 0
    }

    /**
     * Cuts a readable line out of a transcript segment, centred on the match.
     *
     * Segments run to a couple of hundred characters and the match can be anywhere in one;
     * showing the head of the segment often shows nothing of why it matched.
     */
    fun snippet(
        text: String,
        term: String,
        before: Int = 32,
        after: Int = 120,
    ): Snippet {
        val hit = if (term.isEmpty()) -1 else text.indexOf(term, ignoreCase = true)
        if (hit < 0) {
            val clipped = text.take(before + after)
            val tail = if (clipped.length < text.length) ELLIPSIS else ""
            return Snippet(clipped.trimEnd() + tail, -1, -1)
        }
        val start = (hit - before).coerceAtLeast(0)
        val end = (hit + term.length + after).coerceAtMost(text.length)
        val head = if (start > 0) ELLIPSIS else ""
        val tail = if (end < text.length) ELLIPSIS else ""
        val matchStart = head.length + (hit - start)
        return Snippet(head + text.substring(start, end) + tail, matchStart, matchStart + term.length)
    }
}
