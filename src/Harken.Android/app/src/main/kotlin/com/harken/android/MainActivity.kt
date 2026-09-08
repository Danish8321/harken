package com.harken.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.harken.android.device.DeviceCapability
import com.harken.android.recording.RecordingRecovery
import com.harken.android.telemetry.Telemetry
import com.harken.android.ui.AppNav
import com.harken.android.ui.ThemeMode
import com.harken.android.ui.theme.HarkenTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val device = DeviceCapability.of(this)
        // Reported every launch so "my transcriptions keep disappearing" is answerable from
        // the log rather than by asking the user what phone they have. A magnitude, like
        // everything else here — it says nothing about what was recorded.
        Telemetry.event(
            "device_capability",
            "totalMemMb" to device.totalMemMb,
            "belowMinimum" to device.isBelowMinimum,
            // Which build this is. Every install used to report "1.0", so a log excerpt
            // could not be tied to the code that produced it (ARC-022).
            "versionName" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,
            "gitSha" to BuildConfig.GIT_SHA,
        )
        recoverOrphanedRecordings(device)
        // Same reconciliation, one file over: a model download killed with the process
        // leaves a partial file no future attempt will resume from. It is skipped while a
        // download is actually running, so re-entering this activity mid-download is safe.
        application.container.modelDownloadManager.discardPartialDownload()
        // And one more: a decode that never returned means whisper.cpp took the process
        // down. Reported here because a native crash gets no chance to report itself.
        application.container.decodeBreadcrumb.reportCrashIfAny()
        setContent {
            val settings = remember { application.container.settings }
            val themeMode by settings.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.System)
            val dynamicColor by settings.dynamicColor.collectAsStateWithLifecycle(initialValue = false)
            val darkTheme =
                when (themeMode) {
                    ThemeMode.System -> isSystemInDarkTheme()
                    ThemeMode.Light -> false
                    ThemeMode.Dark -> true
                }
            HarkenTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                AppNav()
            }
        }
    }

    /**
     * A capture killed mid-recording (process death, low memory) leaves its WAV on disk
     * with no session row. Reconciled here, on every launch, rather than left for the user
     * to notice audio they can no longer reach.
     */
    private fun recoverOrphanedRecordings(device: DeviceCapability) {
        val repository = application.container.repository
        lifecycleScope.launch {
            RecordingRecovery(filesDir, repository, repository::sessionIds).recover()
            // A transcription cannot outlive the process, so anything still marked running
            // died with it. Left alone the session shows "Transcribing" forever and offers
            // the user no way to start it again.
            // On a device below ADR-0014's bar the honest message is a different one: the
            // decode was very likely killed for memory, and "tap to try again" on its own
            // invites the user to lose the same twenty minutes a second time.
            repository.failInterruptedTranscriptions(
                getString(
                    if (device.isBelowMinimum) {
                        R.string.error_transcription_interrupted_low_memory
                    } else {
                        R.string.error_transcription_interrupted
                    },
                ),
            )
        }
    }
}
