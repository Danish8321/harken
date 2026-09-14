package com.harken.android

import android.app.Application
import com.harken.android.telemetry.CrashLogger
import com.harken.android.telemetry.FileLogSink
import com.harken.android.telemetry.Telemetry
import java.io.File

/**
 * Owns the app's single [AppContainer].
 *
 * Nothing is built here: the container's members are lazy, so process start still does no
 * database or JNI work. This class exists to give the graph one owner with the right
 * lifetime — the process — rather than five ViewModels each building their own (ARC-014).
 */
class HarkenApplication : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    /**
     * Not `by lazy`, unlike everything in [AppContainer]: a crash before anything else
     * touches [Telemetry] should still be caught, so this has to exist before the rest of
     * `onCreate` runs, not on first use. The single instance Settings' "Export logs" action
     * reads from too — two [FileLogSink]s on the same directory would rotate independently
     * and could each overwrite what the other just wrote.
     */
    lateinit var logSink: FileLogSink
        private set

    override fun onCreate() {
        super.onCreate()
        logSink = FileLogSink(File(filesDir, "logs"))
        Telemetry.attachFileSink(logSink)
        CrashLogger.install(logSink)
    }
}
