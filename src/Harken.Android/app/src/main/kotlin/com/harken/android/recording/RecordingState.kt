package com.harken.android.recording

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import com.harken.android.audio.RecordingStopReason
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

data class RecordingCompleted(
    val recordingId: UUID,
    val filePath: String,
    val stopReason: RecordingStopReason,
    /** How long the capture ran, from the WAV's own byte count. */
    val durationSeconds: Int,
    /**
     * Why saving the session row failed, or null when it was written.
     *
     * The recorder owns that write now (ARC-016) — this flow is a notification to
     * whatever screen happens to be on top, not the path the recording is saved by.
     */
    val saveError: String? = null,
)

/** A recording failed to start or was aborted mid-capture by something the user has no lever over. */
data class RecordingError(val message: String)

private data class InProgress(
    val recordingId: UUID,
    val filePath: String,
    val startedAtElapsedMs: Long,
    /** How long this recording has spent paused, summed over every completed pause. */
    val pausedTotalMs: Long = 0,
    /** When the current pause began, or null while audio is being written. */
    val pausedAtElapsedMs: Long? = null,
)

// Ports src/Harken.Mobile/Services/RecordingState.cs — a process-wide singleton (the
// foreground service and the Compose UI run in the same process, so a bound-service
// connection would be ceremony around a field read). Guarded with an AtomicReference
// instead of a lock: the writer is the capture thread, the reader is the UI thread.
object RecordingState {
    private val current = AtomicReference<InProgress?>(null)

    private val _completed = MutableSharedFlow<RecordingCompleted>(extraBufferCapacity = 1)
    val completed = _completed.asSharedFlow()

    private val _error = MutableSharedFlow<RecordingError>(extraBufferCapacity = 1)
    val error = _error.asSharedFlow()

    fun publishError(message: String) {
        _error.tryEmit(RecordingError(message))
    }

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    // Paused is a state OF a recording, not a third state beside recording and idle: the
    // service still holds the microphone and the notification is still ongoing. isRecording
    // stays true throughout, and the UI reads both.
    private val _isPaused = MutableStateFlow(false)
    val isPaused = _isPaused.asStateFlow()

    // The capture thread publishes one normalized RMS sample per chunk; RecordScreen's
    // waveform reads it directly instead of drawing a decorative sine — see
    // AudioRecordCapture's chunk callback in RecordingForegroundService.
    private val _amplitude = MutableStateFlow(0f)
    val amplitude = _amplitude.asStateFlow()

    fun publishAmplitude(value: Float) {
        _amplitude.value = value
    }

    val recordingId: UUID?
        get() = current.get()?.recordingId

    // The one seam in this object. SystemClock is an Android static, and JVM unit tests run
    // against the stub, which returns 0 forever — so the pause arithmetic below, the only
    // arithmetic here, would be untestable off-device. Production never assigns this.
    @VisibleForTesting
    internal var elapsedRealtime: () -> Long = SystemClock::elapsedRealtime

    // elapsedRealtime, not currentTimeMillis: the wall clock is settable, and an NTP
    // correction or a DST rollover mid-capture would make the on-screen timer jump or run
    // backwards (ARC-009). This is the same clock RecordingForegroundService times itself
    // with, so the two no longer disagree about one recording.
    fun elapsedMs(): Long {
        val progress = current.get() ?: return 0
        // While paused, time is measured to the moment the pause began: the counter on
        // screen has to agree with the length of the WAV, and no audio is being written.
        val until = progress.pausedAtElapsedMs ?: elapsedRealtime()
        return until - progress.startedAtElapsedMs - progress.pausedTotalMs
    }

    fun markStarted(recordingId: UUID, filePath: String) {
        current.set(InProgress(recordingId, filePath, elapsedRealtime()))
        _isPaused.value = false
        _isRecording.value = true
    }

    /** No-op if there is no recording, or if it is already paused. */
    fun markPaused() {
        val now = elapsedRealtime()
        val updated = current.updateAndGet { progress ->
            if (progress == null || progress.pausedAtElapsedMs != null) progress
            else progress.copy(pausedAtElapsedMs = now)
        }
        _isPaused.value = updated?.pausedAtElapsedMs != null
    }

    /** No-op if there is no recording, or if it is not paused. */
    fun markResumed() {
        val now = elapsedRealtime()
        val updated = current.updateAndGet { progress ->
            val since = progress?.pausedAtElapsedMs ?: return@updateAndGet progress
            progress.copy(pausedTotalMs = progress.pausedTotalMs + (now - since), pausedAtElapsedMs = null)
        }
        _isPaused.value = updated?.pausedAtElapsedMs != null
    }

    // Only promotes a path that was actually being recorded — MarkStopped can arrive
    // twice (a Stop tap racing an auto-stop), and the second must not resurrect a stale
    // result. That check is what makes `completed` fire exactly once per recording.
    fun markStopped(
        stopReason: RecordingStopReason = RecordingStopReason.None,
        durationSeconds: Int = 0,
        saveError: String? = null,
    ) {
        val finished = current.getAndSet(null) ?: return
        _isPaused.value = false
        _isRecording.value = false
        _completed.tryEmit(
            RecordingCompleted(finished.recordingId, finished.filePath, stopReason, durationSeconds, saveError),
        )
    }
}
