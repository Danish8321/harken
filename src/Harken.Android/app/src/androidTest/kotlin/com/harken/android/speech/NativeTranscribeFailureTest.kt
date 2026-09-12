package com.harken.android.speech

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The JNI bridge must raise rather than return an empty result when a decode fails
 * (ARC-058). An empty array is a real answer — a span whisper heard nothing in — so a
 * failure that returns one is recorded as silence, and the transcription completes and
 * reports success with a hole in it.
 *
 * Instrumented because there is no JNI on the JVM runner: `OnDeviceTranscriber`'s companion
 * loads `libharken_whisper_jni` in its initializer, so even touching these externals off a
 * device is an `UnsatisfiedLinkError`.
 *
 * The null-handle path is the one of the three that can be driven from a test without a
 * model or an out-of-memory device. It exercises the same `ThrowDecodeFailure` the
 * `whisper_full` failure uses, so what it proves is that the mechanism works end to end:
 * the class is findable under R8's naming, the exception crosses the boundary, and Kotlin
 * sees a throw rather than `"[]"`.
 */
class NativeTranscribeFailureTest {
    @Test
    fun aDecodeWithNoModelHandleThrowsRatherThanReturningAnEmptyTranscript() {
        val silence = ShortArray(1600)

        val thrown =
            assertThrows(IllegalStateException::class.java) {
                OnDeviceTranscriber.nativeTranscribe(handle = 0L, pcm16 = silence, sampleRate = 16_000)
            }

        // The message is for logcat and telemetry only — the coordinator never shows a
        // Throwable's own text to the user (ARC-042) — but asserting it pins which of the
        // failure paths raised, so a future change cannot satisfy this test by throwing
        // from somewhere else.
        assertTrue(
            "should be the null-handle path, but the message was: ${thrown.message}",
            thrown.message?.contains("null model handle") == true,
        )
    }
}
