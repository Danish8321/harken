package com.harken.android.telemetry

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * [Telemetry.realtimeFactor] only — the rest of Telemetry ends in `Log.i`, which is a stub
 * on this runner.
 */
class TelemetryTest {
    private lateinit var original: Locale

    @Before
    fun rememberLocale() {
        original = Locale.getDefault()
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(original)
    }

    @Test
    fun `decode time is reported against the recording's own length`() {
        // Half a minute of work for a minute of audio: twice as fast as real time.
        assertEquals("0.50", Telemetry.realtimeFactor(decodeMs = 30_000, audioSeconds = 60))
        assertEquals("3.00", Telemetry.realtimeFactor(decodeMs = 180_000, audioSeconds = 60))
    }

    @Test
    fun `the number is a decimal point on a phone that writes decimal commas`() {
        // ARC-029: a device set to a comma locale emitted realtimeFactor=1,84 into a
        // key=value line, which every reader of those logs splits on the wrong thing.
        Locale.setDefault(Locale.GERMANY)

        assertEquals("1.84", Telemetry.realtimeFactor(decodeMs = 110_400, audioSeconds = 60))
    }

    @Test
    fun `a recording of no measurable length is reported, not divided by`() {
        // A real case: the file exists, the microphone gave nothing. It must not take the
        // transcription down with an arithmetic failure on the way out.
        assertEquals("0.00", Telemetry.realtimeFactor(decodeMs = 4_000, audioSeconds = 0))
    }
}
