package com.harken.android.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The only guard available on the seam between this type and the column behind it: Room
 * cannot run on this repo's JVM test runner, so what the DAO writes is not testable here.
 * What is testable is that every member survives the trip through [TranscriptionStatus.stored]
 * — which is what the DAO binds — and that nothing else in the column can be mistaken for one.
 */
class TranscriptionStatusTest {
    @Test
    fun `every member round-trips its stored value`() {
        for (status in TranscriptionStatus.entries) {
            assertEquals(status, TranscriptionStatus.of(status.stored))
        }
    }

    @Test
    fun `an unrecognised column value reads as Recorded`() {
        // Null is what the nullable column still permits; "Pending" and "Completed" are
        // strings the UI and its tests compared against and nothing ever wrote (ARC-064).
        // Recorded is the recoverable answer: it offers Transcribe. The fallback this
        // replaced was Transcribed, which claimed finished work about an unknown row.
        for (stored in listOf(null, "Pending", "Completed", "", "running")) {
            assertEquals("\"$stored\" was not read as Recorded", TranscriptionStatus.Recorded, TranscriptionStatus.of(stored))
        }
    }
}
