package com.harken.android.telemetry

import android.util.Log

/**
 * Writes an uncaught exception to [FileLogSink] before the process dies, then hands off
 * to whatever handler was already installed (the platform's own, which shows the
 * "Harken keeps stopping" dialog and writes the tombstone).
 *
 * [NativeDecodeBreadcrumb][com.harken.android.speech.NativeDecodeBreadcrumb] already covers
 * a SIGSEGV inside whisper.cpp, where no Kotlin `catch` runs at all. This covers everything
 * else — a genuine Kotlin exception nothing on the call stack caught — which today leaves
 * nothing in the durable log, only a logcat line an unattended multi-day run has already
 * overwritten by the time anyone looks.
 *
 * The full stack trace, not just [Telemetry.describe]'s one-line summary: a crash report
 * with no trace is a crash report that names the wrong line as often as the right one once
 * R8 has renamed everything around it, and a stack trace carries no user data to withhold.
 */
object CrashLogger {
    private const val TAG = "HarkenTelemetry"

    fun install(fileSink: FileLogSink) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                Telemetry.event(
                    "uncaught_exception",
                    "thread" to thread.name,
                    "error" to Telemetry.describe(throwable),
                )
                fileSink.append(Log.getStackTraceString(throwable))
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
