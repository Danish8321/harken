package com.harken.android.ingest

import com.harken.android.audio.WavFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one check standing between a 5 MB share-sheet file and 460 MB of PCM (ADR-0016).
 *
 * The arithmetic is the point: everything here is free space and byte counts injected, so
 * none of it needs a device.
 */
class ImportPreflightTest {
    @Test
    fun `an hour of audio costs what an hour of recording costs`() {
        // 115 MB an hour is what Settings already tells the user recording costs. An import
        // that quoted a different number would mean one of the two was lying.
        assertEquals(
            WavFormat.BYTES_PER_SECOND.toLong() * 3600,
            ImportPreflight.recordingBytes(hours(1)),
        )
    }

    @Test
    fun `the staged source is counted alongside the recording it becomes`() {
        // Both exist at once: the decode cannot release the source until it has read it.
        val source = 5L * MB

        assertEquals(
            ImportPreflight.recordingBytes(hours(1)) + source,
            ImportPreflight.requiredBytes(hours(1), source),
        )
    }

    @Test
    fun `an import that will not fit is refused`() {
        val outcome = ImportPreflight.assess(hours(4), 60 * MB, freeBytes = 100 * MB)

        assertTrue("was $outcome", outcome is PreflightOutcome.WontFit)
    }

    @Test
    fun `an import that fits but wedges the device is refused too`() {
        // 100 MB of import into 300 MB of free space technically fits. What it leaves
        // behind is a phone in its own low-storage state, which is not a successful import.
        val duration = seconds(100L * MB / WavFormat.BYTES_PER_SECOND)

        val outcome = ImportPreflight.assess(duration, sourceBytes = 0, freeBytes = 300 * MB)

        assertTrue("was $outcome", outcome is PreflightOutcome.WontFit)
    }

    @Test
    fun `a large import is quoted before it is run`() {
        val outcome = ImportPreflight.assess(hours(5), 80 * MB, freeBytes = 8 * GB)

        assertTrue("was $outcome", outcome is PreflightOutcome.NeedsConfirmation)
        assertEquals(
            ImportPreflight.recordingBytes(hours(5)),
            (outcome as PreflightOutcome.NeedsConfirmation).recordingBytes,
        )
    }

    @Test
    fun `an ordinary voice note is not worth a dialog`() {
        val outcome = ImportPreflight.assess(seconds(90), 1 * MB, freeBytes = 8 * GB)

        assertEquals(PreflightOutcome.Fits, outcome)
    }

    @Test
    fun `a container that does not state its length is allowed through`() {
        // Some streams genuinely carry no duration. Refusing them all to catch the few that
        // would not have fitted trades a common case for a rare one — and the decode stages
        // in the cache directory, so the rare one fails somewhere Android reclaims.
        val outcome = ImportPreflight.assess(durationUs = 0, sourceBytes = 3 * MB, freeBytes = 8 * GB)

        assertEquals(PreflightOutcome.Fits, outcome)
    }

    @Test
    fun `a nonsense duration is zero, not negative space`() {
        assertEquals(0L, ImportPreflight.recordingBytes(-1))
        assertEquals(0L, ImportPreflight.requiredBytes(-1, -1))
    }

    private companion object {
        const val MB = 1024L * 1024
        const val GB = 1024L * MB

        fun seconds(count: Long): Long = count * 1_000_000

        fun hours(count: Long): Long = seconds(count * 3600)
    }
}
