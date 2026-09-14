package com.harken.android.speech

import com.harken.android.audio.WavFormat
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decode side of a native transcription: the JSON whisper's JNI bridge hands back, and
 * the arithmetic that puts each span's segments where they belong in the recording.
 *
 * The parse runs against a real org.json here — the unit-test android.jar ships stubs that
 * return defaults, so without the test-only dependency every one of these would see an
 * empty array and pass. See the note on `json` in libs.versions.toml.
 */
class NativeSegmentsTest {
    @Test
    fun `both fields are read, text exactly as whisper wrote it`() {
        val segments = parseNativeSegments("""[{"offsetMs": 0, "text": " Hello."}, {"offsetMs": 1500, "text": " World."}]""")

        assertEquals(
            listOf(
                NativeSegment(offsetMs = 0, text = " Hello."),
                NativeSegment(offsetMs = 1500, text = " World."),
            ),
            segments,
        )
    }

    @Test
    fun `an empty array is a span whisper heard nothing in`() {
        assertEquals(emptyList<NativeSegment>(), parseNativeSegments("[]"))
    }

    @Test
    fun `text that is not JSON is raised, not read as silence`() {
        // A decode that failed already throws before this point (ARC-058). This is the
        // other half of that rule: nothing malformed may be quietly turned into no speech.
        val thrown = runCatching { parseNativeSegments("not json at all") }.exceptionOrNull()

        assertTrue("expected a JSONException, got $thrown", thrown is JSONException)
    }

    @Test
    fun `a span's offsets move into the recording's own timeline`() {
        // The span starts two seconds in, so a segment 1.5s into the span is at 3.5s of the
        // recording — reported in whole seconds, so 3.
        val startSample = WavFormat.SAMPLE_RATE * 2
        val segments =
            listOf(
                NativeSegment(offsetMs = 0, text = " First."),
                NativeSegment(offsetMs = 1500, text = " Second."),
            )

        assertEquals(
            listOf(
                LocalTranscribedSegment(offsetSeconds = 2, text = " First."),
                LocalTranscribedSegment(offsetSeconds = 3, text = " Second."),
            ),
            segments.atSpan(startSample),
        )
    }

    @Test
    fun `the first span leaves its offsets where they are`() {
        val segments = listOf(NativeSegment(offsetMs = 4000, text = " Opening."))

        assertEquals(
            listOf(LocalTranscribedSegment(offsetSeconds = 4, text = " Opening.")),
            segments.atSpan(startSample = 0),
        )
    }

    @Test
    fun `a span an hour in is not truncated to an int of milliseconds`() {
        // 3600 seconds of 16 kHz audio is 57.6 million samples; times 1000 that overflows
        // Int and would land the segment before the start of the recording.
        val startSample = WavFormat.SAMPLE_RATE * 3600

        val moved = listOf(NativeSegment(offsetMs = 0, text = " Late.")).atSpan(startSample)

        assertEquals(listOf(LocalTranscribedSegment(offsetSeconds = 3600, text = " Late.")), moved)
    }
}
