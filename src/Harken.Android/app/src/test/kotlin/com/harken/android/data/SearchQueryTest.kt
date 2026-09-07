package com.harken.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryTest {
    @Test
    fun `a percent in the term is escaped, not treated as a wildcard`() {
        assertEquals("50\\%", SearchQuery.likePattern("50%"))
    }

    @Test
    fun `an underscore is escaped`() {
        assertEquals("a\\_b", SearchQuery.likePattern("a_b"))
    }

    @Test
    fun `the backslash is escaped first, so it does not escape the escapes`() {
        assertEquals("\\\\\\%", SearchQuery.likePattern("\\%"))
    }

    @Test
    fun `an ordinary term is passed through unchanged`() {
        assertEquals("quarterly review", SearchQuery.likePattern("quarterly review"))
    }

    @Test
    fun `a short segment is shown whole, with the match located`() {
        val snippet = SearchQuery.snippet("The budget is approved", "budget")
        assertEquals("The budget is approved", snippet.text)
        assertTrue(snippet.hasMatch)
        assertEquals("budget", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
    }

    @Test
    fun `a match late in a long segment is still visible in the window`() {
        val text = "x".repeat(400) + " budget " + "y".repeat(400)
        val snippet = SearchQuery.snippet(text, "budget")
        assertTrue(snippet.hasMatch)
        assertEquals("budget", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
        assertTrue("window opening mid-segment is marked", snippet.text.startsWith("\u2026"))
        assertTrue("window ending mid-segment is marked", snippet.text.endsWith("\u2026"))
    }

    @Test
    fun `the match is found regardless of case, as LIKE finds it`() {
        val snippet = SearchQuery.snippet("Budget approved", "budget")
        assertTrue(snippet.hasMatch)
        assertEquals("Budget", snippet.text.substring(snippet.matchStart, snippet.matchEnd))
    }

    @Test
    fun `a segment with no occurrence yields a snippet with no highlight`() {
        val snippet = SearchQuery.snippet("nothing relevant here", "budget")
        assertFalse(snippet.hasMatch)
        assertEquals("nothing relevant here", snippet.text)
    }

    @Test
    fun `the highlight range is inside the returned text for a match at the very start`() {
        val snippet = SearchQuery.snippet("budget " + "z".repeat(500), "budget")
        assertEquals(0, snippet.matchStart)
        assertTrue(snippet.matchEnd <= snippet.text.length)
    }
}
