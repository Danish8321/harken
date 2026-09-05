package com.harken.android.telemetry

import android.util.Log

/**
 * One logcat line per significant event, shaped so a machine can read it:
 *
 * ```
 * event=transcribe_finished session=46619c3b audioSeconds=41 decodedSeconds=18 segments=3
 * ```
 *
 * The app previously logged only failures — 21 `Log.e` calls, one `Log.i`, nothing in
 * between. That is why the whisper-on-silence defect (five minutes of quiet decoded for
 * thirteen and a half, into eleven invented segments) survived to a device run: it threw
 * nothing, so nothing in the codebase had an opinion about it. Degradation needs events
 * about work that *succeeded*, carrying the magnitudes that make an absurd result look
 * absurd.
 *
 * Read a run back with:
 * ```
 * adb logcat -s HarkenTelemetry:I
 * ```
 *
 * ## What may be logged
 *
 * Shapes only — counts, durations, byte totals, which branch ran. Never transcript text,
 * segment text, recording titles, tags, or file contents. Everything this app records is
 * meant to stay on the phone (ADR-0011), and logcat is readable by `adb` and by anything
 * holding READ_LOGS. The questions worth asking are all about magnitude, and magnitudes
 * carry no speech.
 */
object Telemetry {
    const val Tag = "HarkenTelemetry"

    /**
     * Emits [name] with [fields] as `key=value` pairs. Values are rendered with
     * `toString()`; whitespace inside one is collapsed to `_` so a line stays parseable by
     * splitting on spaces.
     */
    fun event(name: String, vararg fields: Pair<String, Any?>) {
        val line = StringBuilder("event=").append(name)
        for ((key, value) in fields) {
            line.append(' ').append(key).append('=').append(render(value))
        }
        Log.i(Tag, line.toString())
    }

    /**
     * How a failure is named in an event.
     *
     * Not the class name: R8 renames it, so the release build that first ran minified
     * reported `error=e` for a Gson failure whose message named the cause exactly. The
     * message is what survives minification and what distinguishes two failures of the
     * same type, and it is already shown to the user on the Library card and stored on
     * the session, so logging it exposes nothing new. The class name is the fallback for
     * the exceptions that carry no message, where a minified name still beats `null`.
     */
    fun describe(e: Throwable): String = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.name

    /**
     * The short form of a session id used in every event, so capture, transcription and
     * playback lines for one recording can be joined without pasting 36 characters.
     */
    fun shortId(id: Any?): String = id?.toString()?.take(8) ?: "none"

    /**
     * Milliseconds since a [System.nanoTime] mark. Monotonic, so it survives the clock
     * being adjusted mid-recording — which matters for a session that may run three hours.
     */
    fun elapsedMsSince(startNanos: Long): Long = (System.nanoTime() - startNanos) / 1_000_000L

    private fun render(value: Any?): String =
        value?.toString()?.replace(WHITESPACE, "_") ?: "null"

    private val WHITESPACE = Regex("\\s+")
}
