package com.harken.android.ingest

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
import androidx.annotation.StringRes
import com.harken.android.MainActivity
import com.harken.android.R
import com.harken.android.container
import com.harken.android.data.PartOfDay
import com.harken.android.recording.LiveUpdateNotification
import com.harken.android.recordingTitle
import com.harken.android.speech.TranscriptionService
import com.harken.android.telemetry.Telemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.UUID
import kotlin.math.roundToInt

private const val TAG = "ImportService"

/**
 * Holds the process up for the length of an import.
 *
 * Decoding an hour of audio is minutes of CPU, and the user has no reason to watch it. Run
 * from a ViewModel it would die the moment they left the screen — and unlike a transcription
 * there would be no recovery pass to notice, because a half-decoded import has no Session
 * row to be recovered into. So it runs here, for the same reason a decode and an export do
 * ([TranscriptionService], [com.harken.android.export.ExportService], ARC-003).
 *
 * The service owns the sequence — preflight, decode, Session — and nothing else. Who is
 * allowed to import stays in [ImportCoordinator], so starting this twice cannot start two
 * decodes, and what a decode actually does stays in [AudioImporter].
 *
 * **The source is a plain file, not a Uri.** A share-sheet grant is one-shot and scoped to
 * the activity that received it; this outlives that activity by minutes. The caller copies
 * the bytes into `cacheDir` while the grant is still good and hands over a path, which this
 * deletes when it is done with it, whichever way it ended.
 */
class ImportService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** Rate limit for the progress notification. See [publish]. */
    private var lastPublishedAtMs = 0L

    /** Last percentage drawn, so a refused start can redraw the running import unchanged. */
    private var lastPercent = INDETERMINATE

    /** True when the container never said how long it is, so progress cannot be a fraction. */
    private var indeterminate = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (intent?.action == ACTION_CANCEL) {
            ImportCoordinator.cancel()
            // Not stopSelf() here: the decode's own finally releases the slot and stops the
            // service. One exit path, whether it finished, failed or was cancelled.
            return START_NOT_STICKY
        }

        val source = intent?.getStringExtra(EXTRA_SOURCE_PATH)?.let(::File)
        if (source == null || !source.exists()) {
            // A null intent is the system recreating a service it killed. Nothing can be
            // resumed: the staged copy went with the process's cache directory, and a
            // partial decode was never in the recordings directory to begin with.
            Log.w(TAG, "Started with no source; stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)

        val importId = UUID.randomUUID()
        val admission = ImportCoordinator.begin(importId)

        createChannelIfNeeded()
        // Every startForegroundService gets five seconds to produce a notification,
        // including one that is about to be refused. Same id as the running import, so a
        // refusal redraws that import's notification rather than adding a second.
        startForeground(NOTIFICATION_ID, importingNotification(lastPercent))

        if (admission !is ImportAdmission.Admitted) {
            notifyFailed(refusalMessage(admission))
            source.delete()
            Telemetry.event("import_refused", "reason" to admission::class.simpleName.orEmpty())
            // Only tear the foreground down if it is ours to tear down. An AlreadyImporting
            // refusal arrives while another decode is running on this same instance, and
            // stopping here would demote the service out from under it.
            if (job?.isActive != true) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return START_NOT_STICKY
        }

        lastPercent = INDETERMINATE
        lastPublishedAtMs = 0L
        job =
            scope.launch {
                val startNanos = System.nanoTime()
                val sessionId = UUID.randomUUID()
                val target = File(filesDir, "$sessionId.wav")
                try {
                    val outcome =
                        withContext(Dispatchers.IO) {
                            decode(source, target, admission)
                        }
                    when (outcome) {
                        is ImportOutcome.Imported -> settle(sessionId, outcome, displayName)
                        // Cancelling left nothing behind and the user is the one who did it;
                        // a notification saying so would be the app reporting their own action
                        // back to them.
                        ImportOutcome.Cancelled -> Unit
                        else -> notifyFailed(getString(outcome.messageRes()))
                    }
                    Telemetry.event(
                        "import_finished",
                        "outcome" to outcome::class.simpleName.orEmpty(),
                        "sourceBytes" to source.length(),
                        "elapsedMs" to Telemetry.elapsedMsSince(startNanos),
                    )
                } catch (e: Exception) {
                    // The Session row is the last step, so an exception here means a whole
                    // canonical Recording sits in filesDir with nothing pointing at it —
                    // which is exactly what RecordingRecovery adopts at the next launch.
                    Log.e(TAG, "Import failed", e)
                    notifyFailed(getString(R.string.import_failed_unknown))
                    Telemetry.event(
                        "import_failed",
                        "reason" to Telemetry.describe(e),
                        "elapsedMs" to Telemetry.elapsedMsSince(startNanos),
                    )
                } finally {
                    source.delete()
                    ImportCoordinator.end(importId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Preflight, then decode. Runs off the main thread.
     *
     * Only [PreflightOutcome.WontFit] is enforced here. A [PreflightOutcome.NeedsConfirmation]
     * is a question, and by the time a service is running the user has already been asked —
     * asking again from a notification would be a dialog with nobody in front of it.
     */
    private fun decode(
        source: File,
        target: File,
        admission: ImportAdmission.Admitted,
    ): ImportOutcome {
        val durationUs = ImportPreflight.durationUs(source)
        indeterminate = durationUs <= 0L
        val verdict = ImportPreflight.assess(durationUs, source.length(), ImportPreflight.freeBytes(filesDir))
        if (verdict is PreflightOutcome.WontFit) {
            Log.w(TAG, "Needs ${verdict.requiredBytes} bytes, ${verdict.freeBytes} free")
            return ImportOutcome.StorageFailed
        }
        return AudioImporter(cacheDir).import(source, target, admission.cancelled, ::publish)
    }

    /** Writes the Session row and offers the transcription. Runs on the main thread. */
    private suspend fun settle(
        sessionId: UUID,
        outcome: ImportOutcome.Imported,
        displayName: String?,
    ) {
        val localTitle = ImportTitle.from(displayName)
        val endedAt = Instant.now()
        application.container.repository.createLocalSession(
            id = sessionId,
            // An import has no wall-clock start of its own — the file may be years old, and
            // nothing records where it came from (ADR-0016). Ending now and starting its own
            // length ago puts it at the top of the Library, which is where the user just put it.
            startedAt = endedAt.minusSeconds(outcome.durationSeconds.toLong()).toString(),
            endedAt = endedAt.toString(),
            filePath = outcome.file.absolutePath,
            durationSeconds = outcome.durationSeconds,
            localTitle = localTitle,
        )
        notifyImported(sessionId, outcome, recordingTitle(localTitle, PartOfDay.now()))
    }

    /**
     * Posts progress, at most once a second.
     *
     * Called from the decode thread once per percent; a two-minute file crosses a percent
     * several times a second, and the shade throttles an app that posts faster than that —
     * the bar then stops moving, which reads as a stall.
     */
    private fun publish(fraction: Float) {
        ImportCoordinator.publishProgress(fraction)
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishedAtMs < MIN_NOTIFICATION_GAP_MS) return
        lastPublishedAtMs = now
        lastPercent = if (indeterminate) INDETERMINATE else (fraction * 100).roundToInt().coerceIn(0, 100)
        notificationManager()?.notify(NOTIFICATION_ID, importingNotification(lastPercent))
    }

    private fun importingNotification(percent: Int): Notification =
        LiveUpdateNotification.importing(
            context = this,
            channelId = CHANNEL_ID,
            percent = percent,
            cancelIntent =
                PendingIntent.getService(
                    this,
                    0,
                    Intent(this, ImportService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            contentIntent = openApp(),
        )

    private fun notifyImported(
        sessionId: UUID,
        outcome: ImportOutcome.Imported,
        title: String,
    ) {
        val transcribe =
            PendingIntent.getForegroundService(
                this,
                // Per session, so a second import's action cannot quietly replace a first
                // one's — that action is the only route back for a user who arrived through
                // the share sheet and is not in the app.
                sessionId.hashCode(),
                TranscriptionService.intent(this, sessionId, outcome.file.absolutePath, title),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        notificationManager()?.notify(
            completionIdFor(sessionId),
            LiveUpdateNotification.imported(this, CHANNEL_ID, title, transcribe, openApp()),
        )
    }

    private fun notifyFailed(message: String) {
        notificationManager()?.notify(
            FAILURE_NOTIFICATION_ID,
            LiveUpdateNotification.importFailed(this, CHANNEL_ID, message, openApp()),
        )
    }

    private fun refusalMessage(admission: ImportAdmission): String =
        when (admission) {
            ImportAdmission.RecordingInProgress -> getString(R.string.import_refused_recording)
            else -> getString(R.string.import_refused_busy)
        }

    private fun openApp(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

    private fun notificationManager(): NotificationManager? = getSystemService(NotificationManager::class.java)

    private fun createChannelIfNeeded() {
        val manager = notificationManager() ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_importing_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_ID = "importing"

        /** 1002 is transcribing and 1003 is exporting; this is the next free one. */
        const val NOTIFICATION_ID = 1004
        const val ACTION_CANCEL = "com.harken.android.action.CANCEL_IMPORT"

        private const val EXTRA_SOURCE_PATH = "sourcePath"
        private const val EXTRA_DISPLAY_NAME = "displayName"
        private const val MIN_NOTIFICATION_GAP_MS = 1_000L
        private const val INDETERMINATE = -1
        private const val FAILURE_NOTIFICATION_ID = 1005

        /** Well clear of the fixed ids above, so no completion can displace a live one. */
        private fun completionIdFor(sessionId: UUID): Int = 2000 + (sessionId.hashCode() and 0xFFFF)

        /**
         * Starts an import of [sourcePath], a copy of the user's file already staged in the
         * cache directory. This service deletes it when it is done with it.
         *
         * [displayName] is the name the file arrived with, which becomes the Session's title
         * (ADR-0016). Null when the Uri did not carry one, and the Session is then named from
         * the clock like a capture.
         */
        fun start(
            context: Context,
            sourcePath: String,
            displayName: String?,
        ) {
            val intent =
                Intent(context, ImportService::class.java)
                    .putExtra(EXTRA_SOURCE_PATH, sourcePath)
                    .putExtra(EXTRA_DISPLAY_NAME, displayName)
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ImportService::class.java).setAction(ACTION_CANCEL))
        }
    }
}

/** What to tell the user about an import that did not happen. */
@StringRes
fun ImportOutcome.messageRes(): Int =
    when (this) {
        ImportOutcome.NoAudioTrack -> R.string.import_failed_no_audio
        ImportOutcome.UnsupportedFormat -> R.string.import_failed_unsupported
        ImportOutcome.DecodeFailed -> R.string.import_failed_decode
        ImportOutcome.StorageFailed -> R.string.import_failed_storage
        // Neither ends in a message: one succeeded, and the other the user asked for.
        else -> R.string.import_failed_unknown
    }
