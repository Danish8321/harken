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
 *   2. TRANSCRIBING — determinate progress on the sage "done" accent, no action
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
            .setContentTitle("Recording — $title")
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
            .addAction(0, "Stop", stop)
            .build()
    }

    fun transcribing(context: Context, channelId: String, title: String, percent: Int, etaMinutes: Int?): Notification =
        NotificationCompat.Builder(context, channelId)
            .setContentTitle(title)
            .setContentText(
                if (etaMinutes != null) "About $etaMinutes min left · on-device, nothing leaves the phone"
                else "Transcribing locally · nothing leaves the phone",
            )
            .setSmallIcon(R.drawable.ic_notification_mic)
            .setOngoing(true)
            .setColorized(true)
            .setColor(DONE_ACCENT)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .also { builder ->
                if (android.os.Build.VERSION.SDK_INT >= 36) {
                    builder.extras.putBoolean("android.requestPromotedOngoing", true)
                }
            }
            .build()

    // Organic.Accent500 and Accent2_500 as ARGB ints — the notification API predates
    // Compose Color, so these are the one place a literal is unavoidable. Keep in step
    // with ui/theme/Color.kt.
    private const val RECORDING_ACCENT = 0xFFC67139.toInt()
    private const val DONE_ACCENT = 0xFF7A8A5E.toInt()
}
