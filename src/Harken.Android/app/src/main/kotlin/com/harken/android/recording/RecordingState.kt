package com.harken.android.recording

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
)

private data class InProgress(val recordingId: UUID, val filePath: String, val startedAtMs: Long)

// Ports src/Harken.Mobile/Services/RecordingState.cs — a process-wide singleton (the
// foreground service and the Compose UI run in the same process, so a bound-service
// connection would be ceremony around a field read). Guarded with an AtomicReference
// instead of a lock: the writer is the capture thread, the reader is the UI thread.
object RecordingState {
    private val current = AtomicReference<InProgress?>(null)

    private val _completed = MutableSharedFlow<RecordingCompleted>(extraBufferCapacity = 1)
    val completed = _completed.asSharedFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording = _isRecording.asStateFlow()

    val recordingId: UUID?
        get() = current.get()?.recordingId

    fun elapsedMs(): Long {
        val started = current.get()?.startedAtMs ?: return 0
        return System.currentTimeMillis() - started
    }

    fun markStarted(recordingId: UUID, filePath: String) {
        current.set(InProgress(recordingId, filePath, System.currentTimeMillis()))
        _isRecording.value = true
    }

    // Only promotes a path that was actually being recorded — MarkStopped can arrive
    // twice (a Stop tap racing an auto-stop), and the second must not resurrect a stale
    // result. That check is what makes `completed` fire exactly once per recording.
    fun markStopped(stopReason: RecordingStopReason = RecordingStopReason.None) {
        val finished = current.getAndSet(null) ?: return
        _isRecording.value = false
        _completed.tryEmit(RecordingCompleted(finished.recordingId, finished.filePath, stopReason))
    }
}
