package com.harken.android.speech

import com.harken.android.data.SessionRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs on-device (whisper.cpp) transcription for at most one session at a time,
 * app-wide, and only when the user explicitly asks for it.
 *
 * Previously a recording auto-transcribed the instant Stop was tapped, on whatever
 * thread pool kotlinx.coroutines.Dispatchers.Default happened to hand it. That's the
 * thread the SIGSEGV tombstone (ggml_vec_dot_f16) was seen on, and running several native
 * inferences concurrently only widens the exposure while that's being root-caused. Making
 * transcription an explicit, one-at-a-time user action removes the auto-trigger and the
 * concurrency, independent of whatever the eventual native fix turns out to be.
 *
 * Lives outside any ViewModel because the user may start a transcription from the Library
 * screen and navigate away before it finishes; a ViewModel-scoped coroutine would be
 * cancelled when its screen is left.
 */
object TranscriptionCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = AtomicReference<UUID?>(null)

    private val _activeSessionId = MutableStateFlow<UUID?>(null)
    val activeSessionId: StateFlow<UUID?> = _activeSessionId.asStateFlow()

    /**
     * Starts transcribing [sessionId] if, and only if, nothing else is transcribing right
     * now. Returns false (no-op) if another session is already in flight — the caller
     * (Library UI) is expected to disable the action for every row but the active one, so
     * this is a safety net, not the primary guard.
     */
    fun transcribe(
        repository: SessionRepository,
        modelDownloadManager: ModelDownloadManager,
        onDeviceTranscriber: OnDeviceTranscriber,
        sessionId: UUID,
        filePath: String,
    ): Boolean {
        if (!active.compareAndSet(null, sessionId)) return false
        _activeSessionId.value = sessionId
        scope.launch {
            try {
                repository.startLocalTranscription(sessionId)
                val modelPath = modelDownloadManager.ensureModel().getOrThrow()
                val segments = onDeviceTranscriber.transcribe(filePath, modelPath)
                val durationSeconds = segments.maxOfOrNull { it.offsetSeconds } ?: 0
                repository.completeLocal(sessionId, segments, durationSeconds)
            } catch (e: Exception) {
                repository.failLocal(sessionId, e.message ?: "On-device transcription failed")
            } finally {
                active.set(null)
                _activeSessionId.value = null
            }
        }
        return true
    }
}
