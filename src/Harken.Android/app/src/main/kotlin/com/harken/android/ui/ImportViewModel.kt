package com.harken.android.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.R
import com.harken.android.ingest.ImportCoordinator
import com.harken.android.ingest.ImportPreflight
import com.harken.android.ingest.ImportService
import com.harken.android.ingest.ImportStaging
import com.harken.android.ingest.PreflightOutcome
import com.harken.android.recording.RecordingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the screen has to show about an import that has not started yet. */
sealed interface ImportUiState {
    /** Nothing to say. An import already running says so on its notification, not here. */
    object Idle : ImportUiState

    /** Copying the picked file in. Short for a voice note, seconds for a lecture. */
    object Staging : ImportUiState

    /** Big enough to be worth agreeing to first. [recordingBytes] is what it will cost. */
    data class Confirm(
        val path: String,
        val displayName: String?,
        val recordingBytes: Long,
    ) : ImportUiState

    /** It will not happen, and this is why. */
    data class Failed(
        @param:StringRes val titleRes: Int,
        @param:StringRes val messageRes: Int,
    ) : ImportUiState
}

/**
 * Everything between picking a file and [ImportService] taking over.
 *
 * Three things have to happen before a service is worth starting, and all three need a
 * Context and a coroutine: the bytes have to be copied somewhere the service can reach
 * ([ImportStaging]), the size has to be checked ([ImportPreflight]), and a large import has
 * to be agreed to. The confirmation in particular cannot live in the service — by then
 * there is no screen in front of the user to ask.
 *
 * Refusals are checked here as well as in [ImportCoordinator], not instead of it. The
 * coordinator is the invariant; this is only so the app can say "finish the recording
 * first" before copying a gigabyte that is about to be thrown away.
 */
class ImportViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _state = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val state: StateFlow<ImportUiState> = _state.asStateFlow()

    /** [uri] is null when the user backed out of the picker, which is not an event. */
    fun pick(uri: Uri?) {
        if (uri == null) return
        refusal()?.let {
            _state.value = ImportUiState.Failed(R.string.import_refused_title, it)
            return
        }
        _state.value = ImportUiState.Staging
        viewModelScope.launch {
            val context = getApplication<Application>()
            val staged = withContext(Dispatchers.IO) { ImportStaging.stage(context, uri) }
            if (staged == null) {
                _state.value = ImportUiState.Failed(R.string.notification_import_failed_title, R.string.import_failed_copy)
                return@launch
            }
            val verdict =
                withContext(Dispatchers.IO) {
                    ImportPreflight.assess(
                        durationUs = ImportPreflight.durationUs(staged.file),
                        sourceBytes = staged.file.length(),
                        freeBytes = ImportPreflight.freeBytes(context.filesDir),
                    )
                }
            when (verdict) {
                is PreflightOutcome.WontFit -> {
                    staged.file.delete()
                    _state.value = ImportUiState.Failed(R.string.notification_import_failed_title, R.string.import_failed_storage)
                }

                is PreflightOutcome.NeedsConfirmation ->
                    _state.value = ImportUiState.Confirm(staged.file.path, staged.displayName, verdict.recordingBytes)

                PreflightOutcome.Fits -> {
                    ImportService.start(context, staged.file.path, staged.displayName)
                    _state.value = ImportUiState.Idle
                }
            }
        }
    }

    /** The user agreed to the size. */
    fun confirm() {
        val pending = _state.value as? ImportUiState.Confirm ?: return
        ImportService.start(getApplication(), pending.path, pending.displayName)
        _state.value = ImportUiState.Idle
    }

    /**
     * Closes whatever is on screen. Declining the size question deletes the staged copy
     * with it — nothing else ever will, because no service was started to own it.
     */
    fun dismiss() {
        (_state.value as? ImportUiState.Confirm)?.let { File(it.path).delete() }
        _state.value = ImportUiState.Idle
    }

    @StringRes
    private fun refusal(): Int? =
        when {
            RecordingState.isRecording.value -> R.string.import_refused_recording
            ImportCoordinator.activeImportId.value != null -> R.string.import_refused_busy
            else -> null
        }
}
