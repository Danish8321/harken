package com.harken.android.recording

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.harken.android.R

/**
 * Android 16 Live Updates.
 *
 * Per ADR-0003 this notification is not decoration: with the screen locked it is the only
 * surface the user can see or act on. Android 16 gives that its own treatment — a
 * promoted ongoing notification with a status-bar chip — so the two states below opt into
 * it explicitly rather than relying on setOngoing alone.
 *
 * Two distinct Live Updates, because they are two different jobs:
 *   1. RECORDING   — chronometer + Stop, on the terracotta "live" accent
 *   2. TRANSCRIBING — progress on the sage "done" accent, with Cancel
 *
 * ProgressStyle is API 36; below that the same information degrades to a plain ongoing
 * notification with a progress bar, which is what the previous build always showed.
 */
object LiveUpdateNotification {

    /**
     * [title] is the name the recording will be saved under, not its id: this is the
     * app's most persistent surface, visible on the lock screen for the whole recording,
     * and it read as eight hex characters until ARC-018.
     *
     * [startedAtWallClockMs] is what the chronometer counts up from, so a resumed
     * recording passes a time shifted forward by however long it was paused — the
     * notification then reads the same elapsed as the app, rather than counting the break.
     * While paused the chronometer is off entirely and the frozen figure is text: a
     * counter still running under a "Paused" label is the notification contradicting
     * itself.
     */
    fun recording(
        context: Context,
        channelId: String,
        startedAtWallClockMs: Long,
        title: String,
        paused: Boolean = false,
        elapsedMs: Long = 0,
    ): Notification {
        val stopIntent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ActionStop
        }
        val stop = PendingIntent.getService(context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        val toggleIntent = Intent(context, RecordingForegroundService::class.java).apply {
            action = if (paused) RecordingForegroundService.ActionResume else RecordingForegroundService.ActionPause
        }
        // Distinct request code, or this PendingIntent and the stop one are the same
        // object to the system and the second addAction silently reuses the first's.
        val toggle = PendingIntent.getService(context, 1, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        return NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(
                if (paused) {
                    context.getString(R.string.notification_recording_paused, formatElapsed(elapsedMs))
                } else {
                    context.getString(R.string.notification_recording_body)
                },
            )
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setUsesChronometer(!paused)
            .setWhen(startedAtWallClockMs)
            .addAction(
                0,
                context.getString(if (paused) R.string.notification_recording_resume else R.string.notification_recording_pause),
                toggle,
            )
            .setColorized(true)
            .setColor(RECORDING_ACCENT)
            .setCategory(Notification.CATEGORY_PROGRESS)
            // Live Update: asks the system for the status-bar chip and the promoted
            // treatment on the lock screen and always-on display.
            .also { builder ->
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    builder.extras.putBoolean("android.requestPromotedOngoing", true)
                }
            }
            .addAction(0, context.getString(R.string.notification_recording_stop), stop)
            .build()
    }

    /**
     * [percent] below zero means "not known yet" and draws an indeterminate bar. A decode
     * only learns its own size after it has scanned the WAV for speech, and a bar sitting
     * at 0% for the first few seconds reads as a stalled job rather than a starting one.
     */
    fun transcribing(
        context: Context,
        channelId: String,
        title: String,
        percent: Int,
        etaMinutes: Int?,
        cancelIntent: PendingIntent,
        contentIntent: PendingIntent,
    ): Notification =
        NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.notification_transcribing_title, title))
            .setContentText(
                if (etaMinutes != null) {
                    context.getString(R.string.notification_transcribing_body_eta, etaMinutes)
                } else {
                    context.getString(R.string.notification_transcribing_body)
                },
            )
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setColorized(true)
            .setColor(DONE_ACCENT)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notification_transcribing_cancel), cancelIntent)
            .also { builder ->
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    builder.extras.putBoolean("android.requestPromotedOngoing", true)
                }
            }
            .build()

    /**
     * An export in flight. Determinate from the first file: unlike a decode, the job knows
     * exactly how many recordings it has to write before it writes any of them.
     */
    fun exporting(
        context: Context,
        channelId: String,
        done: Int,
        total: Int,
        cancelIntent: PendingIntent,
        contentIntent: PendingIntent,
    ): Notification =
        NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.notification_exporting_title))
            .setContentText(context.getString(R.string.notification_exporting_body, done, total))
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setColorized(true)
            .setColor(DONE_ACCENT)
            .setProgress(total.coerceAtLeast(1), done, total <= 0)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.notification_exporting_cancel), cancelIntent)
            .also { builder ->
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    builder.extras.putBoolean("android.requestPromotedOngoing", true)
                }
            }
            .build()

    // ProtoDarkColors.accent and .success as ARGB ints — the notification API predates
    // Compose Color, so these are the one place a literal is unavoidable. Keep in step
    // with ui/theme/ProtoColors.kt; they named the deleted Organic palette until UI-028
    // and were still painting the notification terracotta and sage after the re-palette.
    // The dark values are used in both themes: the shade renders on its own ground, not
    // the app's, and these read on either.
    /** "12:04" — the same shape the record screen shows, so the two never disagree. */
    private fun formatElapsed(ms: Long): String {
        val totalSeconds = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(java.util.Locale.ROOT, totalSeconds / 60, totalSeconds % 60)
    }

    private const val RECORDING_ACCENT = 0xFFBFA789.toInt()
    private const val DONE_ACCENT = 0xFF8FBF9A.toInt()
}
