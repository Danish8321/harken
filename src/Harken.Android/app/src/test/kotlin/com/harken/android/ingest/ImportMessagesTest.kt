package com.harken.android.ingest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * That every failure has copy of its own.
 *
 * The exhaustive `when` in ImportMessages already makes a *missing* case a compile error.
 * What it cannot catch is a case added by copying the line above it and forgetting to change
 * the string — which reads as "there is no audio in that file" on a disk that is full. So
 * what is asserted here is distinctness, not the wording; the wording lives in strings.xml
 * and is the translator's to change.
 *
 * These are plain JVM tests, so the resources are ids rather than text. An id of zero would
 * mean the string was never declared.
 */
class ImportMessagesTest {
    private val failures =
        listOf(
            ImportOutcome.NoAudioTrack,
            ImportOutcome.UnsupportedFormat,
            ImportOutcome.DecodeFailed,
            ImportOutcome.StorageFailed,
        )

    @Test
    fun `every failure has a string`() {
        failures.forEach { assertNotEquals("$it has no message", 0, it.messageRes()) }
    }

    @Test
    fun `no two failures share a string`() {
        val distinct = failures.map { it.messageRes() }.toSet()

        assertEquals("Two failures say the same thing", failures.size, distinct.size)
    }

    @Test
    fun `the outcomes that are not failures still resolve`() {
        // Neither is ever shown. They answer rather than throw so that a caller which gets
        // the branch wrong is clumsy instead of fatal to an import that worked.
        assertNotEquals(0, ImportOutcome.Cancelled.messageRes())
        assertNotEquals(0, ImportOutcome.Imported(File("x.wav"), durationSeconds = 1).messageRes())
    }

    @Test
    fun `each refusal says why`() {
        val recording = ImportAdmission.RecordingInProgress.messageRes()
        val busy = ImportAdmission.AlreadyImporting.messageRes()

        assertNotEquals(0, recording)
        assertNotEquals(0, busy)
        assertNotEquals("Both refusals say the same thing", recording, busy)
    }

    @Test
    fun `an admitted import has nothing to say`() {
        assertNull(ImportAdmission.Admitted(AtomicBoolean(false)).messageRes())
    }
}
