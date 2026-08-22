package com.harken.android.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
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
        startForeground(NotificationId, buildNotification(recordingId))

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
        RecordingState.publishAmplitude(pcm16Rms(chunk))
        if (stopReason != RecordingStopReason.None) {
            stopRecording(stopReason)
        }
    }

    /** RMS of a little-endian 16-bit PCM chunk, normalized to [0, 1] against full scale. */
    private fun pcm16Rms(chunk: ByteArray): Float {
        if (chunk.size < 2) return 0f
        var sumSquares = 0.0
        var sampleCount = 0
        var i = 0
        while (i + 1 < chunk.size) {
            val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort().toInt()
            sumSquares += (sample * sample).toDouble()
            sampleCount += 1
            i += 2
        }
        if (sampleCount == 0) return 0f
        val rms = kotlin.math.sqrt(sumSquares / sampleCount)
        return (rms / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
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

    private fun buildNotification(recordingId: UUID): Notification = LiveUpdateNotification.recording(
        context = this,
        channelId = ChannelId,
        startedAtWallClockMs = System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAtElapsedMs),
        title = recordingId.toString().take(8),
    )

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
