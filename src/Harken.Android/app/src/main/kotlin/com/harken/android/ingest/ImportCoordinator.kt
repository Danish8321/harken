package com.harken.android.ingest

import androidx.annotation.VisibleForTesting
import com.harken.android.recording.RecordingState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Whether an import may start, and if so the flag that stops it. */
sealed interface ImportAdmission {
    /** Go. [cancelled] is what the user's Cancel sets and the decode watches. */
    data class Admitted(
        val cancelled: AtomicBoolean,
    ) : ImportAdmission

    /** The microphone is open. Decoding would compete with a capture that cannot be redone. */
    object RecordingInProgress : ImportAdmission

    /** Another import is already running. */
    object AlreadyImporting : ImportAdmission
}

/**
 * Admits one import at a time, app-wide, and never while the microphone is open.
 *
 * The same shape as [com.harken.android.speech.TranscriptionCoordinator]: a
 * compare-and-set, not a lock, because the writer is a service and the reader is the UI,
 * both in one process. It differs in owning no coroutine — [ImportService] runs the work
 * and holds the process up while it does. This only decides who is allowed to.
 *
 * **Why refuse while recording.** A decode saturates the CPU, and capture is the one path
 * in this app that must never stall — a dropped buffer is audio that cannot be recovered,
 * where a refused import is a file the user still has and can import a minute later. The
 * asymmetry is the whole argument.
 */
object ImportCoordinator {
    private val active = AtomicReference<UUID?>(null)

    @Volatile
    private var cancelled = AtomicBoolean(false)

    private val _activeImportId = MutableStateFlow<UUID?>(null)

    /** The import in flight, for a UI that wants to say so. */
    val activeImportId: StateFlow<UUID?> = _activeImportId.asStateFlow()

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    /**
     * Whether the microphone is open. A seam for the same reason [RecordingState] has one:
     * the production answer reads a singleton the JVM tests cannot drive. Production never
     * assigns this.
     */
    @VisibleForTesting
    internal var isRecording: () -> Boolean = { RecordingState.isRecording.value }

    /**
     * Why an import started right now would be refused, or null if it would be admitted.
     *
     * Claims nothing — it is the same question [begin] asks, for a caller that wants the
     * answer before doing expensive work rather than after. The answer can go stale between
     * the two, which is why [begin] asks again and is the one that decides.
     */
    fun refusalNow(): ImportAdmission? =
        when {
            isRecording() -> ImportAdmission.RecordingInProgress
            _activeImportId.value != null -> ImportAdmission.AlreadyImporting
            else -> null
        }

    /** Claims the one import slot for [importId], or says why it cannot be had. */
    fun begin(importId: UUID): ImportAdmission {
        // Checked before the claim, so a refusal does not have to hand the slot back.
        if (isRecording()) return ImportAdmission.RecordingInProgress
        if (!active.compareAndSet(null, importId)) return ImportAdmission.AlreadyImporting
        cancelled = AtomicBoolean(false)
        _progress.value = 0f
        _activeImportId.value = importId
        return ImportAdmission.Admitted(cancelled)
    }

    /**
     * Releases the slot, if [importId] is the import that holds it.
     *
     * The ownership check is what keeps a late finisher from clearing the state of the
     * import that replaced it — the same reason `RecordingState.markStopped` only promotes
     * a capture that was actually running.
     */
    fun end(importId: UUID) {
        if (!active.compareAndSet(importId, null)) return
        _activeImportId.value = null
        _progress.value = 0f
    }

    /** Asks the running import to stop. No-op when nothing is running. */
    fun cancel() {
        cancelled.set(true)
    }

    fun publishProgress(fraction: Float) {
        _progress.value = fraction.coerceIn(0f, 1f)
    }

    @VisibleForTesting
    internal fun reset() {
        active.set(null)
        cancelled = AtomicBoolean(false)
        _activeImportId.value = null
        _progress.value = 0f
        isRecording = { RecordingState.isRecording.value }
    }
}
