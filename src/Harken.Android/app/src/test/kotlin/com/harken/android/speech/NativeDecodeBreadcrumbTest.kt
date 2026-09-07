package com.harken.android.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NativeDecodeBreadcrumbTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun breadcrumb() = NativeDecodeBreadcrumb(temp.root)

    private val note: File get() = File(temp.root, NativeDecodeBreadcrumb.FILE_NAME)

    @Test
    fun `a decode that returns leaves nothing behind`() {
        val crumb = breadcrumb()

        crumb.enter(spanIndex = 0, startSecond = 0, spanSeconds = 30)
        crumb.leave()

        assertFalse("a completed decode was reported as a crash", crumb.reportCrashIfAny())
        assertFalse(note.exists())
    }

    @Test
    fun `a decode that never returned is found at the next launch`() {
        // What process death looks like from the next launch: enter with no matching leave.
        breadcrumb().enter(spanIndex = 3, startSecond = 900, spanSeconds = 300)

        assertTrue(breadcrumb().reportCrashIfAny())
    }

    @Test
    fun `a crash is reported once, not on every launch after it`() {
        breadcrumb().enter(spanIndex = 1, startSecond = 60, spanSeconds = 120)

        assertTrue(breadcrumb().reportCrashIfAny())
        assertFalse("the same crash was reported twice", breadcrumb().reportCrashIfAny())
    }

    @Test
    fun `an unreadable note is still treated as a crash rather than dropped`() {
        note.writeText("not the shape this writes")

        assertTrue(breadcrumb().reportCrashIfAny())
        assertFalse(note.exists())
    }

    @Test
    fun `the note carries no audio, path or transcript`() {
        // Everything the app records stays on the phone (ADR-0011). The breadcrumb is read
        // out through logcat, so it must never carry anything but numbers.
        breadcrumb().enter(spanIndex = 2, startSecond = 42, spanSeconds = 300)

        assertTrue(note.readText().matches(Regex("""\d+,\d+,\d+""")))
    }

    @Test
    fun `a launch with no decode in flight does nothing`() {
        assertFalse(breadcrumb().reportCrashIfAny())
    }
}
