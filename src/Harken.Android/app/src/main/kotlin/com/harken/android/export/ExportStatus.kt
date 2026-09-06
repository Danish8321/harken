package com.harken.android.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where an export has got to, as the Settings screen needs to say it. */
sealed interface ExportState {

    /** No export has run since the app started, or the user dismissed the last result. */
    data object Idle : ExportState

    /** Reading the library. Separate from [Running] because it has no count to show yet. */
    data object Preparing : ExportState

    data class Running(val done: Int, val total: Int) : ExportState

    data class Finished(val report: LibraryExporter.Report) : ExportState

    data object Cancelled : ExportState

    /** [reason] is a message from the platform, or null when there was none worth showing. */
    data class Failed(val reason: String?) : ExportState
}

/**
 * The one place an export's progress lives.
 *
 * Process-wide for the same reason [com.harken.android.recording.RecordingState] is: the
 * work runs in a service so it survives navigation, and the screen that started it may be
 * gone by the time it finishes. A ViewModel that outlived the export would be the bug,
 * not the fix.
 */
object ExportStatus {
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    val state: StateFlow<ExportState> = _state.asStateFlow()

    internal fun set(next: ExportState) {
        _state.value = next
    }

    /** Clears a finished, failed or cancelled result. A running export is left alone. */
    fun acknowledge() {
        val current = _state.value
        if (current !is ExportState.Running && current !is ExportState.Preparing) {
            _state.value = ExportState.Idle
        }
    }
}
