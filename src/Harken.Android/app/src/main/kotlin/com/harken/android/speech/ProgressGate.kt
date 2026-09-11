package com.harken.android.speech

/**
 * A one-way gate around a progress notification.
 *
 * [TranscriptionService] renders progress from the decode thread and removes the
 * notification from the main thread when the coordinator goes idle. Nothing ordered those
 * two: a span callback that landed after the removal re-posted the notification, this time
 * with no foreground service attached and nothing left alive to cancel it — "Transcribing…"
 * on screen, with a progress bar, for a decode that had already finished (ARC-056).
 *
 * A flag alone does not close that window, because the losing thread can read it before the
 * winner writes it and still post afterwards. Both sides take the same lock instead, so a
 * render either completes before the close or never runs at all.
 *
 * The gate is one-way on purpose: a service instance renders one transcription and then
 * stops, so re-opening would mean the wrong session's progress under the same id.
 */
internal class ProgressGate {
    private val lock = Any()
    private var open = true

    /** Runs [render] unless the gate has been closed. */
    fun render(render: () -> Unit) {
        synchronized(lock) {
            if (open) render()
        }
    }

    /**
     * Closes the gate and runs [remove] while holding the lock, so no render can interleave
     * between the decision to stop and the notification actually going away.
     */
    fun close(remove: () -> Unit) {
        synchronized(lock) {
            open = false
            remove()
        }
    }
}
