package com.harken.android.speech

import com.harken.android.telemetry.Telemetry
import java.io.File

/**
 * Records which span whisper.cpp was decoding, so a native crash leaves evidence.
 *
 * A SIGSEGV inside `ggml_vec_dot_f16` takes the whole process down: no Kotlin `catch` runs,
 * no telemetry line is emitted, and the tombstone names a function in a vendored library
 * rather than the audio that reached it. In the field — where nobody is holding an `adb`
 * cable — the crash is currently indistinguishable from the user force-quitting.
 *
 * So the shape of the work is written to disk *before* the native call and removed after it
 * returns. A file still present at the next launch means the process died inside that
 * decode, and says how long the span was.
 *
 * Deliberately only numbers: span index, offset and length. No audio, no path, no
 * transcript — a crash breadcrumb is not a reason to put a recording's content anywhere it
 * would not otherwise be (ADR-0011).
 */
class NativeDecodeBreadcrumb(
    filesDir: File,
) {
    private val file = File(filesDir, FILE_NAME)

    /** Notes that a decode is starting. Overwrites any previous note. */
    fun enter(
        spanIndex: Int,
        startSecond: Int,
        spanSeconds: Int,
    ) {
        runCatching { file.writeText("$spanIndex,$startSecond,$spanSeconds") }
    }

    /** Notes that the decode returned — whether it produced segments or threw. */
    fun leave() {
        runCatching { file.delete() }
    }

    /**
     * Reports a decode that never returned, and clears the note so it is reported once.
     * Called at launch; a no-op on the overwhelming majority of launches.
     *
     * @return true if a crashed decode was found.
     */
    fun reportCrashIfAny(): Boolean {
        if (!file.exists()) return false

        val parts = runCatching { file.readText().split(",") }.getOrDefault(emptyList())
        file.delete()

        // A note that cannot be parsed is still evidence the process died mid-decode; it is
        // reported with unknown fields rather than dropped.
        Telemetry.event(
            "native_decode_crash",
            "spanIndex" to (parts.getOrNull(0)?.toIntOrNull() ?: -1),
            "startSecond" to (parts.getOrNull(1)?.toIntOrNull() ?: -1),
            "spanSeconds" to (parts.getOrNull(2)?.toIntOrNull() ?: -1),
        )
        return true
    }

    companion object {
        const val FILE_NAME = "decode-in-flight"
    }
}
