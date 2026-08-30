package com.harken.android

import android.app.Application
import android.util.Log
import com.harken.android.data.SessionRepository
import com.harken.android.data.local.HarkenDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "HarkenApp"

/**
 * Exists for one job: settling transcriptions that were interrupted by the process dying.
 *
 * On-device transcription runs in a coroutine owned by TranscriptionCoordinator, inside
 * this process. A crash, a low-memory kill or a force stop takes that coroutine with it
 * and leaves the session row marked as still running, which the Library renders as a
 * spinner with no end and no action. Process start is the one moment where that state is
 * unambiguous: TranscriptionCoordinator has just been created and cannot yet be running
 * anything, so any row still marked as running belongs to a process that no longer exists.
 *
 * Deliberately not done in MainActivity: onCreate also runs on configuration changes,
 * which would settle a transcription that is genuinely still in flight.
 */
class HarkenApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            runCatching {
                SessionRepository(db = HarkenDatabase.get(this@HarkenApp))
                    .recoverInterruptedTranscriptions(getString(R.string.transcription_interrupted_reason))
            }.onSuccess { recovered ->
                if (recovered > 0) Log.i(TAG, "Recovered $recovered interrupted transcription(s)")
            }.onFailure { e ->
                // A failure here costs the user nothing beyond the stale row they already
                // have, so it must not take the app's startup with it.
                Log.e(TAG, "Could not recover interrupted transcriptions", e)
            }
        }
    }
}
