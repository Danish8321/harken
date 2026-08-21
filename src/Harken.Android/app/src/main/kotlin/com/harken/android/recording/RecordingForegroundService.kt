package com.harken.android.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.harken.android.audio.AudioRecordCapture
import com.harken.android.audio.RecordingStopReason
import com.harken.android.audio.SilenceDetector
import com.harken.android.audio.WavWriter
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

// Ports src/Harken.Mobile/Platforms/Android/RecordingForegroundService.cs — direct
// android.app.Service + NotificationCompat, no MAUI wrapper layer.
class RecordingForegroundService : Service() {

    private val writerGate = Any()
    private var writer: WavWriter? = null
    private var silenceDetector: SilenceDetector? = null
    private var capture: AudioRecordCapture? = null
    private var startedAtElapsedMs: Long = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ActionStop) {
            stopRecording(RecordingStopReason.None)
            return START_NOT_STICKY
        }

        val recordingId = intent?.getStringExtra(RecordingIdExtra)?.let(UUID::fromString)
        val filePath = intent?.getStringExtra(FilePathExtra)

        if (recordingId == null || filePath == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannelIfNeeded()
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        startForeground(NotificationId, buildNotification())

        synchronized(writerGate) {
            writer = WavWriter(RandomAccessFile(filePath, "rw"))
            silenceDetector = SilenceDetector(
                silenceTimeoutMs = TimeUnit.MINUTES.toMillis(5),
                sessionCapMs = TimeUnit.HOURS.toMillis(3),
            )
        }

        RecordingState.markStarted(recordingId, filePath)

        capture = AudioRecordCapture(onChunk = ::writeChunk, scope = scope)
        capture?.start()

        return START_STICKY
    }

    private fun writeChunk(chunk: ByteArray) {
        var stopReason = RecordingStopReason.None
        synchronized(writerGate) {
            writer?.write(chunk, 0, chunk.size)
            stopReason = silenceDetector?.add(chunk, 0, chunk.size) ?: RecordingStopReason.None
        }
        if (stopReason != RecordingStopReason.None) {
            stopRecording(stopReason)
        }
    }

    private fun stopRecording(stopReason: RecordingStopReason) {
        scope.launch {
            capture?.stop()
            synchronized(writerGate) {
                writer?.close()
                writer = null
                silenceDetector = null
            }
            RecordingState.markStopped(stopReason)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RecordingForegroundService::class.java).apply { action = ActionStop }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, ChannelId)
            .setContentTitle("Harken is recording")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setUsesChronometer(true)
            .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAtElapsedMs))
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(ChannelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(ChannelId, "Recording", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val ChannelId = "recording"
        const val NotificationId = 1001
        const val ActionStop = "harken.action.STOP"
        const val RecordingIdExtra = "harken.recordingId"
        const val FilePathExtra = "harken.filePath"

        fun start(context: Context, recordingId: UUID, filePath: String) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                putExtra(RecordingIdExtra, recordingId.toString())
                putExtra(FilePathExtra, filePath)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ActionStop
            }
            context.startService(intent)
        }
    }
}
