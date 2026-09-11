package com.harken.android.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.harken.android.MainActivity
import com.harken.android.R
import com.harken.android.container
import com.harken.android.data.SessionRepository
import com.harken.android.recording.LiveUpdateNotification
import com.harken.android.telemetry.Telemetry
import com.harken.android.ui.messageRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

private const val TAG = "TranscriptionService"

/**
 * Holds the process up for the length of a transcription.
 *
 * [TranscriptionCoordinator] already ran the decode outside any ViewModel, so navigating
 * away did not cancel it. That was necessary and not sufficient: with no foreground
 * component the app is a cached process holding whisper's ~610 MB working set
 * ([ADR-0014](../../../../../../../docs/adr/0014-minimum-supported-device.md)), which is
 * the first thing Android reclaims. A decode of a 21-minute meeting takes 597 seconds on
 * the reference device, and ten minutes of the user looking at something else is the
 * normal case. So transcriptions died routinely, on every device, and the app answered
 * with `failInterruptedTranscriptions` at the next launch — a recovery path for its most
 * common failure instead of a fix for it. See ARC-003.
 *
 * The service is a lifetime holder, not a second copy of the logic: it starts the
 * coordinator, renders its progress, and stops itself when the coordinator goes idle. The
 * one-at-a-time invariant stays where it was, in the coordinator's compare-and-set, so
 * starting this service twice cannot start two decodes.
 *
 * `dataSync` rather than a bare service because that is what the type means — finite work
 * the user asked for that must finish — and because from Android 14 a foreground service
 * has to declare which. Android 15 caps cumulative dataSync runtime at six hours a day;
 * the app's own three-hour recording cap and a sub-realtime decoder keep a single job an
 * order of magnitude under that.
 */
class TranscriptionService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var repository: SessionRepository
    private lateinit var modelDownloadManager: ModelDownloadManager
    private lateinit var onDeviceTranscriber: OnDeviceTranscriber

    /**
     * Held between the decode thread's progress updates and the main thread's teardown, so
     * the notification cannot come back after it has been removed (ARC-056).
     */
    private val gate = ProgressGate()

    /** Wall-clock start of the decode, for the ETA. Monotonic, so a clock change cannot move it. */
    private var startedAtElapsedMs = 0L
    private var notificationTitle = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = application.container
        repository = container.repository
        modelDownloadManager = container.modelDownloadManager
        onDeviceTranscriber = container.transcriber
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_CANCEL) {
            TranscriptionCoordinator.cancel()
            // Not stopSelf() here: the coordinator's own finally clears activeSessionId,
            // and the observer below stops the service off that. One exit path, whether
            // the decode finished, failed or was cancelled.
            return START_NOT_STICKY
        }

        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        val filePath = intent?.getStringExtra(EXTRA_FILE_PATH)
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        if (sessionId == null || filePath == null) {
            // A null intent arrives when the system recreates a service it killed. There
            // is nothing to resume — the decode's memory went with the process — and
            // failInterruptedTranscriptions reports it at the next launch, so stop rather
            // than publish an error about work the user never restarted (cf. ARC-008).
            Log.w(TAG, "Started with no session; stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        notificationTitle = title
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        createChannelIfNeeded()
        // Indeterminate to begin with: the decode only learns its own size after scanning
        // the WAV for speech, and a bar pinned at 0% reads as stalled, not starting.
        startForeground(NOTIFICATION_ID, notification(percent = -1))

        val started =
            TranscriptionCoordinator.transcribe(
                repository = repository,
                modelDownloadManager = modelDownloadManager,
                onDeviceTranscriber = onDeviceTranscriber,
                sessionId = sessionId,
                filePath = filePath,
                messages =
                    TranscriptionMessages(
                        cancelled = getString(R.string.error_transcription_cancelled),
                        failed = getString(R.string.error_transcription_failed),
                        modelUnavailable = { getString(it.messageRes()) },
                    ),
                onProgress = ::publishProgress,
            )
        Telemetry.event(
            "transcribe_service_started",
            "session" to Telemetry.shortId(sessionId),
            "accepted" to started,
        )
        if (!started) {
            // Another decode already holds the single-flight slot; that one owns the
            // notification and this instance has nothing to show.
            stopSelf(startId)
            return START_NOT_STICKY
        }

        scope.launch {
            // "No longer ours", not "not the first value". drop(1) assumed this collector
            // attaches before the decode can finish; the decode runs on another
            // dispatcher, so a transcription that failed immediately — no model and no
            // network — could clear activeSessionId first, and drop(1) would then discard
            // the null it was waiting for and hold the foreground notification open
            // forever. Comparing against our own id is correct whichever order they run in.
            TranscriptionCoordinator.activeSessionId.collect { active ->
                if (active != sessionId) {
                    // Under the gate, so a span callback still in flight on the decode
                    // thread cannot re-post the notification behind this (ARC-056).
                    gate.close { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Called from the decode thread, once per span. `notify` is safe there, and spans run
     * 30 to 300 seconds, so this is a handful of updates over the whole job rather than
     * anything that needs rate limiting.
     *
     * Through the gate, because a cancelled decode can deliver one more span after the
     * coordinator has gone idle and the service has already taken its notification down.
     */
    private fun publishProgress(fraction: Float) {
        val percent = (fraction * 100).roundToInt().coerceIn(0, 100)
        gate.render {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(percent))
        }
    }

    private fun notification(percent: Int): Notification =
        LiveUpdateNotification.transcribing(
            context = this,
            channelId = CHANNEL_ID,
            title = notificationTitle,
            percent = percent,
            etaMinutes = etaMinutes(percent),
            cancelIntent =
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, TranscriptionService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            contentIntent =
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
        )

    /**
     * Remaining minutes, extrapolated from how long the finished fraction took.
     *
     * Null below 5%, because the first span carries the model load and the WAV scan and
     * so is not representative of the rest — an estimate taken there is wrong by a factor
     * rather than by a minute, and a wrong number is worse than no number. Rounded up, so
     * "about 1 min left" never means fifty seconds of watching zero.
     */
    private fun etaMinutes(percent: Int): Int? {
        if (percent < 5) return null
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtElapsedMs
        val remainingMs = elapsedMs * (100 - percent) / percent
        return ((remainingMs + 59_999) / 60_000).toInt().coerceAtLeast(1)
    }

    private fun createChannelIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_transcribing_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_ID = "transcribing"
        const val NOTIFICATION_ID = 1002
        const val ACTION_CANCEL = "com.harken.android.action.CANCEL_TRANSCRIPTION"

        private const val EXTRA_SESSION_ID = "sessionId"
        private const val EXTRA_FILE_PATH = "filePath"
        private const val EXTRA_TITLE = "title"

        /**
         * The intent that starts a transcription, for a caller that has to wrap it rather
         * than fire it — the Transcribe action on the finished-import notification builds a
         * PendingIntent out of this. Exists so the extra keys stay private to this file.
         */
        fun intent(
            context: Context,
            sessionId: UUID,
            filePath: String,
            title: String,
        ): Intent =
            Intent(context, TranscriptionService::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId.toString())
                .putExtra(EXTRA_FILE_PATH, filePath)
                .putExtra(EXTRA_TITLE, title)

        /**
         * Starts a transcription. [title] is what the notification calls the recording, so
         * it is the display title the Library row shows rather than the session id.
         */
        fun start(
            context: Context,
            sessionId: UUID,
            filePath: String,
            title: String,
        ) {
            context.startForegroundService(intent(context, sessionId, filePath, title))
        }
    }
}
