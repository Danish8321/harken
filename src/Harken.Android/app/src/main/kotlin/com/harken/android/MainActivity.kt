package com.harken.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.harken.android.data.AppSettings
import com.harken.android.data.SessionRepository
import com.harken.android.data.local.HarkenDatabase
import com.harken.android.recording.RecordingRecovery
import com.harken.android.speech.ModelDownloadManager
import com.harken.android.speech.NativeDecodeBreadcrumb
import kotlinx.coroutines.launch
import com.harken.android.ui.AppNav
import com.harken.android.ui.ThemeMode
import com.harken.android.ui.theme.HarkenTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        recoverOrphanedRecordings()
        // Same reconciliation, one file over: a model download killed with the process
        // leaves a partial file no future attempt will resume from. It is skipped while a
        // download is actually running, so re-entering this activity mid-download is safe.
        ModelDownloadManager(this).discardPartialDownload()
        // And one more: a decode that never returned means whisper.cpp took the process
        // down. Reported here because a native crash gets no chance to report itself.
        NativeDecodeBreadcrumb(filesDir).reportCrashIfAny()
        setContent {
            val settings = remember { AppSettings(this) }
            val themeMode by settings.themeMode.collectAsState(initial = ThemeMode.System)
            val dynamicColor by settings.dynamicColor.collectAsState(initial = false)
            val darkTheme = when (themeMode) {
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
    private fun recoverOrphanedRecordings() {
        val repository = SessionRepository(db = HarkenDatabase.get(application))
        lifecycleScope.launch {
            RecordingRecovery(filesDir, repository, repository::sessionIds).recover()
        }
    }
}
