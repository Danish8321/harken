package com.harken.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.data.SessionRepository
import com.harken.android.data.local.HarkenDatabase
import com.harken.android.data.local.AudioSource
import com.harken.android.recording.RecordingController
import com.harken.android.recording.RecordingState
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class UploadStatus { Idle, Uploading, Succeeded, Failed }

data class CaptureUiState(
    val isRecording: Boolean = false,
    val uploadStatus: UploadStatus = UploadStatus.Idle,
    val lastError: String? = null,
    val lastSessionId: java.util.UUID? = null,
    // True when the last Succeeded save was on-device (WhisperLocal): nothing was
    // uploaded and nothing is transcribing yet, unlike the backend path.
    val lastSavedLocally: Boolean = false,
)

// Ports the upload half of src/Harken.Mobile's CapturePageViewModel: every stop routes
// through RecordingState.completed (manual Stop tap, silence timeout, session cap alike)
// so an auto-stop uploads the same way a manual one does (ADR-0007).
class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SessionRepository(
        db = HarkenDatabase.get(application),
    )
    private var lastRecordingId: java.util.UUID? = null
    private var lastFilePath: String? = null

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            RecordingState.isRecording.collect { recording ->
                _uiState.value = _uiState.value.copy(isRecording = recording)
            }
        }
        viewModelScope.launch {
            RecordingState.completed.collect { completed ->
                upload(completed.recordingId, completed.filePath)
            }
        }
    }

    fun startRecording() {
        RecordingController.startRecording(getApplication())
    }

    fun stopRecording() {
        RecordingController.stopRecording(getApplication())
    }

    fun retryUpload() {
        val recordingId = lastRecordingId ?: return
        val filePath = lastFilePath ?: return
        upload(recordingId, filePath)
    }

    private fun upload(recordingId: java.util.UUID, filePath: String) {
        lastRecordingId = recordingId
        lastFilePath = filePath
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(lastError = null)
            saveLocal(recordingId, filePath)
        }
    }

    // WhisperLocal (ADR-0011): saves the recording as a local-only session with no
    // network call. Transcription itself is a separate, explicit action the user takes
    // from the Library (see TranscriptionCoordinator) — recording no longer auto-triggers
    // native inference, and at most one session transcribes at a time app-wide.
    private suspend fun saveLocal(recordingId: java.util.UUID, filePath: String) {
        try {
            repository.createLocalSession(
                id = recordingId,
                startedAt = Instant.now().toString(),
                source = AudioSource.Microphone.name,
                filePath = filePath,
            )
            _uiState.value = _uiState.value.copy(
                uploadStatus = UploadStatus.Succeeded,
                lastSessionId = recordingId,
                lastSavedLocally = true,
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(uploadStatus = UploadStatus.Failed, lastError = e.message)
        }
    }
}
