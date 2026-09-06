package com.harken.android.speech

import android.util.Log
import com.harken.android.audio.WavFormat
import com.harken.android.data.TranscriptionSink
import com.harken.android.telemetry.Telemetry
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "TranscriptionCoordinator"

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
 *
 * Living outside a ViewModel is necessary and was never sufficient: nothing here holds the
 * *process* up. With no foreground component the app becomes a cached process still
 * holding whisper's ~610 MB working set (ADR-0014), which is the first thing Android
 * reclaims — so a decode died whenever the user left the app, on every device, and
 * failInterruptedTranscriptions tidied up afterwards. [TranscriptionService] is what
 * starts a transcription now; it holds a dataSync foreground service open for the length
 * of the decode and this stays the thing that runs it. See ARC-003.
 */
object TranscriptionCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val active = AtomicReference<UUID?>(null)

    /**
     * The decode in flight, so [cancel] has something to cancel. Written only under the
     * [active] compare-and-set that guarantees one at a time, so a plain field is enough.
     */
    @Volatile
    private var running: Job? = null

    /**
     * Used when a caller does not supply its own. Every caller on the device does — the
     * message the user reads belongs in strings.xml, and this object has no Context to
     * read one with — so this is only ever seen by the JVM tests.
     */
    const val DefaultCancelledMessage = "Transcription cancelled."

    private val _activeSessionId = MutableStateFlow<UUID?>(null)
    val activeSessionId: StateFlow<UUID?> = _activeSessionId.asStateFlow()

    /**
     * Starts transcribing [sessionId] if, and only if, nothing else is transcribing right
     * now. Returns false (no-op) if another session is already in flight — the caller
     * (Library UI) is expected to disable the action for every row but the active one, so
     * this is a safety net, not the primary guard.
     */
    fun transcribe(
        repository: TranscriptionSink,
        modelDownloadManager: ModelProvider,
        onDeviceTranscriber: Transcriber,
        sessionId: UUID,
        filePath: String,
        cancelledMessage: String = DefaultCancelledMessage,
        onProgress: (fraction: Float) -> Unit = {},
    ): Boolean {
        if (!active.compareAndSet(null, sessionId)) return false
        _activeSessionId.value = sessionId
        running = scope.launch {
            val startNs = System.nanoTime()
            val audioSeconds = wavDurationSeconds(filePath)
            Telemetry.event(
                "transcribe_started",
                "session" to Telemetry.shortId(sessionId),
                "audioSeconds" to audioSeconds,
            )
            try {
                repository.startLocalTranscription(sessionId)
                val modelPath = modelDownloadManager.ensureModel().getOrThrow()
                val segments = onDeviceTranscriber.transcribe(filePath, modelPath, onProgress)
                repository.completeLocal(sessionId, segments, audioSeconds)
                // The event the whisper-on-silence defect needed and did not have: a
                // successful transcription that reports its own magnitudes. Eleven segments
                // for five minutes of silence is only obviously wrong if something says so.
                Telemetry.event(
                    "transcribe_finished",
                    "session" to Telemetry.shortId(sessionId),
                    "outcome" to "succeeded",
                    "audioSeconds" to audioSeconds,
                    "segments" to segments.size,
                    "elapsedMs" to Telemetry.elapsedMsSince(startNs),
                )
            } catch (e: CancellationException) {
                // The user pressed Cancel on the notification. Not a failure of the
                // decoder, so it does not report one: the row goes back to being a
                // recording the user can transcribe again, with a message that says who
                // stopped it. Rethrown after, because swallowing a cancellation leaves
                // this coroutine looking successful to its own scope.
                Telemetry.event(
                    "transcribe_finished",
                    "session" to Telemetry.shortId(sessionId),
                    "outcome" to "cancelled",
                    "audioSeconds" to audioSeconds,
                    "elapsedMs" to Telemetry.elapsedMsSince(startNs),
                )
                // NonCancellable because this coroutine is already cancelled: a suspending
                // write from inside it would be refused before it reached the database,
                // and the row would sit at "Running" forever.
                withContext(NonCancellable) { repository.failLocal(sessionId, cancelledMessage) }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "On-device transcription failed for session $sessionId", e)
                Telemetry.event(
                    "transcribe_finished",
                    "session" to Telemetry.shortId(sessionId),
                    "outcome" to "failed",
                    "audioSeconds" to audioSeconds,
                    "error" to Telemetry.describe(e),
                    "elapsedMs" to Telemetry.elapsedMsSince(startNs),
                )
                repository.failLocal(sessionId, e.message ?: "On-device transcription failed")
            } finally {
                onDeviceTranscriber.release()
                active.set(null)
                _activeSessionId.value = null
            }
        }
        return true
    }

    /**
     * Stops the transcription in flight, if there is one. Safe to call when there is not.
     *
     * The `finally` in [transcribe] still runs on cancellation, so the native handle is
     * released and the single-flight slot is cleared exactly as it is on a normal finish.
     */
    fun cancel() {
        running?.cancel()
    }

    // Segment offsets only mark where speech was detected, not the recording's actual
    // length — the last segment's offset undercounts trailing silence.
    private fun wavDurationSeconds(filePath: String): Int = WavFormat.durationSeconds(File(filePath))
}
