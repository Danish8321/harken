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

    fun recording(context: Context, channelId: String, startedAtWallClockMs: Long, title: String): Notification {
        val stopIntent = Intent(context, RecordingForegroundService::class.java).apply {
            action = RecordingForegroundService.ActionStop
        }
        val stop = PendingIntent.getService(context, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(context, channelId)
            .setContentTitle(context.getString(R.string.notification_recording_title, title))
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(startedAtWallClockMs)
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

    // ProtoDarkColors.accent and .success as ARGB ints — the notification API predates
    // Compose Color, so these are the one place a literal is unavoidable. Keep in step
    // with ui/theme/ProtoColors.kt; they named the deleted Organic palette until UI-028
    // and were still painting the notification terracotta and sage after the re-palette.
    // The dark values are used in both themes: the shade renders on its own ground, not
    // the app's, and these read on either.
    private const val RECORDING_ACCENT = 0xFFBFA789.toInt()
    private const val DONE_ACCENT = 0xFF8FBF9A.toInt()
}
