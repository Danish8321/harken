package com.harken.android.export

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.harken.android.MainActivity
import com.harken.android.R
import com.harken.android.container
import com.harken.android.recordingTitle
import com.harken.android.recording.LiveUpdateNotification
import com.harken.android.telemetry.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ExportService"

/**
 * Holds the process up for the length of an export.
 *
 * Copying a library is minutes of I/O over gigabytes, and the user has no reason to sit
 * and watch it. Run from a ViewModel it would die the moment they left Settings, leaving
 * a directory of half-written recordings that looks like a backup — which is worse than
 * no backup, because they would not know. So it runs here, for the same reason a decode
 * does ([com.harken.android.speech.TranscriptionService]).
 *
 * `dataSync`, which is what the type means: finite work the user asked for that must
 * finish. Android 15 caps cumulative dataSync runtime at six hours a day, and an export
 * is minutes.
 */
class ExportService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    /** Rate limit for the progress notification. See [publish]. */
    private var lastPublishedAtMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionCancel) {
            job?.cancel()
            return START_NOT_STICKY
        }

        val tree = intent?.getStringExtra(ExtraTreeUri)?.let(Uri::parse)
        if (tree == null) {
            // A null intent is the system recreating a service it killed. Nothing can be
            // resumed — the destination was a one-time grant — and half a backup is not
            // something to quietly continue.
            Log.w(TAG, "Started with no destination; stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (job?.isActive == true) {
            // One export at a time: two writing the same names into the same directory
            // would interleave into files neither of them finished.
            Log.w(TAG, "An export is already running")
            return START_NOT_STICKY
        }

        createChannelIfNeeded()
        ExportStatus.set(ExportState.Preparing)
        startForeground(NotificationId, notification(done = 0, total = 0))

        job = scope.launch {
            val startNanos = System.nanoTime()
            val repository = application.container.repository
            try {
                val items = withContext(Dispatchers.IO) {
                    // The Service is the Context this resolves the names against.
                    repository.exportItems { localTitle, partOfDay ->
                        recordingTitle(localTitle, partOfDay)
                    }
                }
                ExportStatus.set(ExportState.Running(done = 0, total = items.size))
                publish(done = 0, total = items.size)

                val report = withContext(Dispatchers.IO) {
                    LibraryExporter(contentResolver).export(tree, items) { progress ->
                        ExportStatus.set(ExportState.Running(progress.done, progress.total))
                        publish(progress.done, progress.total)
                    }
                }
                ExportStatus.set(ExportState.Finished(report))
                Telemetry.event(
                    "export_finished",
                    "recordings" to report.recordings,
                    "audioFiles" to report.audioFiles,
                    "transcripts" to report.transcripts,
                    "missingAudio" to report.missingAudio,
                    "failed" to report.failed,
                    "bytes" to report.bytes,
                    "elapsedMs" to Telemetry.elapsedMsSince(startNanos),
                )
            } catch (e: CancellationException) {
                ExportStatus.set(ExportState.Cancelled)
                Telemetry.event("export_cancelled", "elapsedMs" to Telemetry.elapsedMsSince(startNanos))
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Export failed", e)
                ExportStatus.set(ExportState.Failed(e.message))
                Telemetry.event(
                    "export_failed",
                    "reason" to Telemetry.describe(e),
                    "elapsedMs" to Telemetry.elapsedMsSince(startNanos),
                )
            } finally {
                // The grant was taken so this service could outlive the picker; it has no
                // reason to survive the copy, and a backup destination the app can still
                // write to a week later is not what the user agreed to.
                runCatching {
                    contentResolver.releasePersistableUriPermission(
                        tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                }
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
     * Posts progress, at most once a second.
     *
     * An export of short recordings finishes several a second, and the shade throttles an
     * app that posts faster than that — the bar then stops moving, which reads as a stall.
     * The last file is always posted, so the notification never ends mid-count.
     */
    private fun publish(done: Int, total: Int) {
        val now = SystemClock.elapsedRealtime()
        if (done < total && now - lastPublishedAtMs < MinNotificationGapMs) return
        lastPublishedAtMs = now
        getSystemService(NotificationManager::class.java)?.notify(NotificationId, notification(done, total))
    }

    private fun notification(done: Int, total: Int) = LiveUpdateNotification.exporting(
        context = this,
        channelId = ChannelId,
        done = done,
        total = total,
        cancelIntent = PendingIntent.getService(
            this,
            0,
            Intent(this, ExportService::class.java).setAction(ActionCancel),
            PendingIntent.FLAG_IMMUTABLE,
        ),
        contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        ),
    )

    private fun createChannelIfNeeded() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(ChannelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                ChannelId,
                getString(R.string.notification_exporting_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val ChannelId = "exporting"
        const val NotificationId = 1003
        const val ActionCancel = "com.harken.android.action.CANCEL_EXPORT"

        private const val ExtraTreeUri = "treeUri"
        private const val MinNotificationGapMs = 1_000L

        /**
         * Starts an export into [treeUri], a directory the user has just picked.
         *
         * The caller must have taken a persistable grant on it: the picker hands the
         * permission to the activity, and this outlives the activity.
         */
        fun start(context: Context, treeUri: Uri) {
            val intent = Intent(context, ExportService::class.java)
                .putExtra(ExtraTreeUri, treeUri.toString())
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, ExportService::class.java).setAction(ActionCancel))
        }
    }
}
