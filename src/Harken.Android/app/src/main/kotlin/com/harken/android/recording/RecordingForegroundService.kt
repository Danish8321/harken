package com.harken.android.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.harken.android.audio.AudioRecordCapture
import com.harken.android.audio.RecordingStopReason
import com.harken.android.audio.SilenceDetector
import com.harken.android.audio.WavFormat
import com.harken.android.audio.WavWriter
import com.harken.android.container
import com.harken.android.data.SessionRepository
import com.harken.android.telemetry.Telemetry
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
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

    // What is being recorded, kept here because the stop path needs it after
    // RecordingState has already been cleared.
    private var activeRecordingId: UUID? = null
    private var activeFilePath: String? = null

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

    // Pausing is not writing chunks. AudioRecord keeps running and the microphone is never
    // given up, so resuming costs nothing and there is no re-acquisition to fail (ARC-034).
    private val paused = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // The recorder owns the recording, so the recorder owns the write. It used to be done
    // by whichever ViewModel happened to be collecting `RecordingState.completed` when the
    // capture ended — a durable record that existed only if a screen was alive to make it
    // (ARC-016).
    private lateinit var repository: SessionRepository

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        repository = application.container.repository
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionStop -> {
                stopRecording(RecordingStopReason.None)
                return START_NOT_STICKY
            }
            ActionPause, ActionResume -> {
                // Nothing to pause means this process was started only to deliver the
                // action — most likely a notification button tapped after the recording
                // already ended. Let go of it rather than sitting alive with no foreground.
                if (activeRecordingId == null) stopSelf(startId) else setPaused(intent.action == ActionPause)
                return START_NOT_STICKY
            }
        }

        val recordingId = intent?.getStringExtra(RecordingIdExtra)?.let(UUID::fromString)
        val filePath = intent?.getStringExtra(FilePathExtra)

        if (recordingId == null || filePath == null) {
            // A null intent is Android redelivering a start for a service it killed, not a
            // bug the user caused. Telling them "couldn't start recording" about a capture
            // they never asked for — hours later, with the real one already recovered — is
            // worse than saying nothing (ARC-008).
            Log.w(TAG, "Started with no recording; stopping")
            stopSelf(startId)
            return START_NOT_STICKY
        }

        createNotificationChannelIfNeeded()
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

        activeRecordingId = recordingId
        activeFilePath = filePath
        RecordingState.markStarted(recordingId, filePath)

        sessionTag = Telemetry.shortId(recordingId)
        chunkCount = 0
        byteCount = 0
        maxChunkWriteMs = 0
        slowChunks = 0
        stopping.set(false)
        paused.set(false)
        Telemetry.event("recording_started", "session" to sessionTag)

        capture = AudioRecordCapture(onChunk = ::writeChunk, onError = ::onCaptureError, scope = scope)
        capture?.start()

        // NOT_STICKY: restarting a microphone capture without the user's knowledge is worse
        // than not restarting it. The audio between the kill and the restart is gone either
        // way, and what comes back is a second recording of a moment nobody chose to
        // record. RecordingRecovery adopts the interrupted file at the next launch.
        return START_NOT_STICKY
    }

    private fun writeChunk(chunk: ByteArray) {
        if (paused.get()) {
            // Not written, not counted, and not shown to the silence detector — so the
            // five-minute auto-stop does not run down over a break, and the WAV contains
            // no trace of it. At most one 160ms chunk already in flight when the pause
            // lands is written, which is below the resolution of anything downstream.
            RecordingState.publishAmplitude(0f)
            return
        }
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

    /**
     * Freezes or resumes the write side of the capture.
     *
     * Deliberately does not touch [AudioRecordCapture]: stopping and restarting it means
     * giving up the microphone and asking for it back, which can fail, can be taken by
     * another app in between, and drops the pipeline's first buffers on the way back.
     */
    private fun setPaused(wantPaused: Boolean) {
        if (activeRecordingId == null) return
        if (!paused.compareAndSet(!wantPaused, wantPaused)) return

        if (wantPaused) RecordingState.markPaused() else RecordingState.markResumed()
        Telemetry.event(
            if (wantPaused) "recording_paused" else "recording_resumed",
            "session" to sessionTag,
            "elapsedMs" to RecordingState.elapsedMs(),
            "chunks" to chunkCount,
        )
        refreshNotification()
    }

    private fun refreshNotification() {
        val recordingId = activeRecordingId ?: return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.notify(NotificationId, buildNotification(recordingId)) }
            .onFailure { Log.w(TAG, "Could not update the recording notification", it) }
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

    /**
     * Writes the session row, and returns the message to show if it could not be written.
     *
     * The audio survives either way — [RecordingRecovery] adopts a WAV with no row at the
     * next launch — so a failure here costs the user a restart, not the recording.
     */
    private suspend fun saveSession(recordingId: UUID, filePath: String, durationSeconds: Int): String? = try {
        // "Now" is the end of the capture, not its start: stamping startedAt with it dated
        // a 40-minute recording to when it finished, and handed DerivedTitle the wrong
        // part of the day.
        val endedAt = Instant.now()
        repository.createLocalSession(
            id = recordingId,
            startedAt = endedAt.minusSeconds(durationSeconds.toLong()).toString(),
            endedAt = endedAt.toString(),
            source = "Microphone",
            filePath = filePath,
            durationSeconds = durationSeconds,
        )
        null
    } catch (e: Exception) {
        Log.e(TAG, "Failed saving local session $recordingId", e)
        Telemetry.event("recording_save_failed", "session" to sessionTag, "error" to Telemetry.describe(e))
        e.message ?: "Couldn't save this recording"
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
                // Capture time, not wall time: a recording paused for ten minutes is not
                // ten minutes long, and every other number in this event is about bytes.
                "elapsedMs" to RecordingState.elapsedMs(),
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
            val recordingId = activeRecordingId
            val filePath = activeFilePath
            var durationSeconds = 0
            var saveError: String? = null
            if (recordingId != null && filePath != null) {
                durationSeconds = WavFormat.durationSeconds(File(filePath))
                saveError = saveSession(recordingId, filePath, durationSeconds)
            }
            RecordingState.markStopped(stopReason, durationSeconds, saveError)
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
        // The chronometer counts from here, so it is shifted forward by whatever this
        // recording has spent paused — otherwise the notification counts the break and the
        // record screen does not.
        startedAtWallClockMs = System.currentTimeMillis() - RecordingState.elapsedMs(),
        title = recordingId.toString().take(8),
        paused = paused.get(),
        elapsedMs = RecordingState.elapsedMs(),
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
        const val ActionPause = "harken.action.PAUSE"
        const val ActionResume = "harken.action.RESUME"
        const val RecordingIdExtra = "harken.recordingId"
        const val FilePathExtra = "harken.filePath"

        fun start(context: Context, recordingId: UUID, filePath: String) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                putExtra(RecordingIdExtra, recordingId.toString())
                putExtra(FilePathExtra, filePath)
            }
            context.startForegroundService(intent)
        }

        fun setPaused(context: Context, paused: Boolean) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = if (paused) ActionPause else ActionResume
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, RecordingForegroundService::class.java).apply {
                action = ActionStop
            }
            context.startService(intent)
        }
    }
}
