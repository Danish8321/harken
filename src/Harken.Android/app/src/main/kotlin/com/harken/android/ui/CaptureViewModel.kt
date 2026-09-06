package com.harken.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.harken.android.audio.RecordingStopReason
import com.harken.android.container
import com.harken.android.data.SessionRepository
import com.harken.android.recording.RecordingCompleted
import com.harken.android.recording.RecordingController
import com.harken.android.recording.RecordingState
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// No Uploading state: saveLocal() below is a synchronous Room write, never a network
// call (ADR-0011) — there is nothing between "idle" and "done" to show a spinner for.
enum class SaveStatus { Idle, Succeeded, Failed }

data class CaptureUiState(
    val isRecording: Boolean = false,
    /** True while a recording is running but not writing. [isRecording] stays true. */
    val isPaused: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.Idle,
    val lastError: String? = null,
    val lastSessionId: java.util.UUID? = null,
    /**
     * Why the last recording ended. A recording that stopped itself has to say so: the
     * save card read the same "Saved" whether the user tapped Stop or the recorder ended
     * a session they had forgotten about (ADR-0007), which is the one case where they did
     * not already know what happened.
     */
    val stopReason: RecordingStopReason = RecordingStopReason.None,
)

// Every recording is on-device only (ADR-0011). The recorder writes the session row
// itself (ARC-016); this screen only reports what happened, so a capture that ends while
// the user is on another tab is saved exactly the same way as one they watched. An
// auto-stop and a Stop tap arrive by the same path (ADR-0007), and transcription is a
// separate, explicit action taken later from the Library.
class CaptureViewModel(
    application: Application,
    private val repository: SessionRepository,
) : AndroidViewModel(application) {
    private var lastRecordingId: java.util.UUID? = null
    private var lastFilePath: String? = null
    private var lastDurationSeconds: Int = 0
    private var lastStopReason: RecordingStopReason = RecordingStopReason.None

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            RecordingState.isRecording.collect { recording ->
                _uiState.value = _uiState.value.copy(isRecording = recording)
            }
        }
        viewModelScope.launch {
            RecordingState.isPaused.collect { paused ->
                _uiState.value = _uiState.value.copy(isPaused = paused)
            }
        }
        viewModelScope.launch {
            RecordingState.completed.collect(::report)
        }
    }

    fun startRecording() {
        // The card for the previous recording outlived it: starting a new capture left
        // "Stopped after 5 minutes of silence" on screen next to a live meter, describing
        // a recording that had already been saved. A Failed card stays, because it is the
        // only way back to a recording whose audio is on disk with no row to open.
        if (_uiState.value.saveStatus == SaveStatus.Succeeded) {
            _uiState.value = _uiState.value.copy(
                saveStatus = SaveStatus.Idle,
                lastSessionId = null,
                stopReason = RecordingStopReason.None,
            )
        }
        RecordingController.startRecording(getApplication())
    }

    /** Pause a running recording, or resume a paused one. */
    fun togglePause() {
        RecordingController.setPaused(getApplication(), !_uiState.value.isPaused)
    }

    fun stopRecording() {
        RecordingController.stopRecording(getApplication())
    }

    /**
     * Re-attempts a save the recorder could not make. The audio is still on disk, so this
     * is a shortcut past waiting for the next launch, where RecordingRecovery would adopt
     * it anyway.
     */
    fun retrySave() {
        val recordingId = lastRecordingId ?: return
        val filePath = lastFilePath ?: return
        viewModelScope.launch {
            try {
                val endedAt = Instant.now()
                repository.createLocalSession(
                    id = recordingId,
                    startedAt = endedAt.minusSeconds(lastDurationSeconds.toLong()).toString(),
                    endedAt = endedAt.toString(),
                    source = "Microphone",
                    filePath = filePath,
                    durationSeconds = lastDurationSeconds,
                )
                _uiState.value = _uiState.value.copy(
                    saveStatus = SaveStatus.Succeeded,
                    lastSessionId = recordingId,
                    lastError = null,
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving local session $recordingId", e)
                _uiState.value = _uiState.value.copy(saveStatus = SaveStatus.Failed, lastError = e.message)
            }
        }
    }

    private fun report(completed: RecordingCompleted) {
        lastRecordingId = completed.recordingId
        lastFilePath = completed.filePath
        lastDurationSeconds = completed.durationSeconds
        lastStopReason = completed.stopReason
        _uiState.value = _uiState.value.copy(
            stopReason = completed.stopReason,
            lastError = completed.saveError,
            saveStatus = if (completed.saveError == null) SaveStatus.Succeeded else SaveStatus.Failed,
            lastSessionId = completed.recordingId.takeIf { completed.saveError == null },
        )
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                CaptureViewModel(
                    application = checkNotNull(this[APPLICATION_KEY]),
                    repository = container.repository,
                )
            }
        }
    }
}

private const val TAG = "CaptureViewModel"
