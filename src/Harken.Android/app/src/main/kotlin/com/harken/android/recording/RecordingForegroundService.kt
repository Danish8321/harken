package com.harken.android.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.harken.android.audio.AudioRecordCapture
import com.harken.android.audio.RecordingStopReason
import com.harken.android.audio.SilenceDetector
import com.harken.android.audio.WavWriter
import com.harken.android.telemetry.Telemetry
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

private const val TAG = "RecordingForegroundService"

/**
 * A chunk write slower than this is worth counting. AudioRecordCapture hands over roughly
 * ten chunks a second, so anything near 100 ms means the writer is racing the microphone
 * and the internal buffer is the only thing preventing a gap in the recording.
 */
private const val SlowChunkMs = 50L

// Ports src/Harken.Mobile/Platforms/Android/RecordingForegroundService.cs — direct
// android.app.Service + NotificationCompat, no MAUI wrapper layer.
class RecordingForegroundService : Service() {

    private val writerGate = Any()
    private var writer: WavWriter? = null
    private var silenceDetector: SilenceDetector? = null
    private var capture: AudioRecordCapture? = null
    private var startedAtElapsedMs: Long = 0

    // Named so every line of this recording's life can be joined: capture, transcription,
    // playback. Without it a recording's chunk-write failures and its transcription timings
    // are two unrelated piles of logcat.
    private var sessionTag: String = Telemetry.shortId(null)

    // Capture-side counters, summed on the capture thread and reported once on stop. A
    // per-chunk event would emit ten lines a second for three hours; the aggregate answers
    // the same question — did writing to disk ever fall behind the microphone?
    private var chunkCount: Long = 0
    private var byteCount: Long = 0
    private var maxChunkWriteMs: Long = 0
    private var slowChunks: Long = 0

    // Once a recording is stopping it cannot start stopping again. The detector reports
    // the same reason on every chunk after the first, and chunks keep arriving at 160 ms
    // while the coroutine below is still getting started, so without this one stop was
    // reported several times over.
    private val stopping = AtomicBoolean(false)

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
            Log.e(TAG, "onStartCommand missing recordingId/filePath extras")
            RecordingState.publishError("Couldn't start recording — missing session details")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannelIfNeeded()
        startedAtElapsedMs = SystemClock.elapsedRealtime()
        try {
            startForeground(NotificationId, buildNotification(recordingId))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            RecordingState.publishError(e.message ?: "Couldn't start the recording notification")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            synchronized(writerGate) {
                writer = WavWriter(RandomAccessFile(filePath, "rw"))
                silenceDetector = SilenceDetector(
                    silenceTimeoutMs = TimeUnit.MINUTES.toMillis(5),
                    sessionCapMs = TimeUnit.HOURS.toMillis(3),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open recording file at $filePath", e)
            RecordingState.publishError(e.message ?: "Couldn't create the recording file")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        RecordingState.markStarted(recordingId, filePath)

        sessionTag = Telemetry.shortId(recordingId)
        chunkCount = 0
        byteCount = 0
        maxChunkWriteMs = 0
        slowChunks = 0
        stopping.set(false)
        Telemetry.event("recording_started", "session" to sessionTag)

        capture = AudioRecordCapture(onChunk = ::writeChunk, onError = ::onCaptureError, scope = scope)
        capture?.start()

        return START_STICKY
    }

    private fun writeChunk(chunk: ByteArray) {
        var stopReason = RecordingStopReason.None
        val writeStartNs = System.nanoTime()
        try {
            synchronized(writerGate) {
                writer?.write(chunk, 0, chunk.size)
                stopReason = silenceDetector?.add(chunk, 0, chunk.size) ?: RecordingStopReason.None
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing an audio chunk to disk", e)
            Telemetry.event(
                "chunk_write_failed",
                "session" to sessionTag,
                "afterChunks" to chunkCount,
                "error" to Telemetry.describe(e),
            )
            RecordingState.publishError(e.message ?: "Recording stopped — couldn't write to disk")
            stopRecording(RecordingStopReason.None)
            return
        }
        val writeMs = Telemetry.elapsedMsSince(writeStartNs)
        chunkCount += 1
        byteCount += chunk.size
        if (writeMs > maxChunkWriteMs) maxChunkWriteMs = writeMs
        if (writeMs > SlowChunkMs) slowChunks += 1

        RecordingState.publishAmplitude(pcm16Rms(chunk))
        if (stopReason != RecordingStopReason.None) {
            stopRecording(stopReason)
        }
    }

    private fun onCaptureError(message: String) {
        RecordingState.publishError(message)
        stopRecording(RecordingStopReason.None)
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
        if (!stopping.compareAndSet(false, true)) return
        scope.launch {
            capture?.stop()
            val summary = synchronized(writerGate) {
                try {
                    writer?.close()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed closing/patching the WAV file", e)
                    RecordingState.publishError(e.message ?: "Recording may be incomplete on disk")
                }
                writer = null
                // Read before the detector is dropped, and carried out as one value: the
                // event below is the only place these are ever reported, and it runs
                // outside this lock.
                silenceDetector?.summarize().also { silenceDetector = null }
            }
            Telemetry.event(
                "recording_stopped",
                "session" to sessionTag,
                "reason" to stopReason,
                "elapsedMs" to SystemClock.elapsedRealtime() - startedAtElapsedMs,
                "chunks" to chunkCount,
                "bytes" to byteCount,
                "maxChunkWriteMs" to maxChunkWriteMs,
                "slowChunks" to slowChunks,
                // What the auto-stop was working from. "It stopped in the middle of my
                // meeting" and "it recorded an empty room for three hours" are the same
                // event with peakSilentMs at opposite ends, and neither is answerable
                // from the stop reason alone — the threshold is read off the recording,
                // so it differs per recording.
                "noiseFloor" to (summary?.noiseFloor ?: 0),
                "speechAt" to (summary?.speechThreshold ?: 0),
                "peakSilentMs" to (summary?.peakSilentMs ?: 0L),
            )
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
