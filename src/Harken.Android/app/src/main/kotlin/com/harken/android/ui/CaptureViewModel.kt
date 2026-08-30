package com.harken.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.data.SessionRepository
import com.harken.android.data.local.HarkenDatabase
import com.harken.android.recording.RecordingController
import com.harken.android.recording.RecordingState
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// No in-progress state: saveLocal() below is a Room write, never a network call
// (ADR-0011, ADR-0014) — there is nothing between "idle" and "done" to show a spinner
// for. The name says save, not upload, because nothing here leaves the phone.
enum class SaveStatus { Idle, Succeeded, Failed }

data class CaptureUiState(
    val isRecording: Boolean = false,
    val saveStatus: SaveStatus = SaveStatus.Idle,
    val lastError: String? = null,
    val lastSessionId: java.util.UUID? = null,
)

// Every recording is on-device only (ADR-0011): every stop routes through
// RecordingState.completed (manual Stop tap, silence timeout, session cap alike) so an
// auto-stop saves the same way a manual one does (ADR-0007), and transcription is a
// separate, explicit action taken later from the Library.
class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SessionRepository(db = HarkenDatabase.get(application))
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
                saveLocal(completed.recordingId, completed.filePath)
            }
        }
    }

    fun startRecording() {
        RecordingController.startRecording(getApplication())
    }

    fun stopRecording() {
        RecordingController.stopRecording(getApplication())
    }

    fun retrySave() {
        val recordingId = lastRecordingId ?: return
        val filePath = lastFilePath ?: return
        viewModelScope.launch { saveLocal(recordingId, filePath) }
    }

    private suspend fun saveLocal(recordingId: java.util.UUID, filePath: String) {
        lastRecordingId = recordingId
        lastFilePath = filePath
        _uiState.value = _uiState.value.copy(lastError = null)
        try {
            repository.createLocalSession(
                id = recordingId,
                startedAt = Instant.now().toString(),
                source = "Microphone",
                filePath = filePath,
            )
            _uiState.value = _uiState.value.copy(
                saveStatus = SaveStatus.Succeeded,
                lastSessionId = recordingId,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed saving local session $recordingId", e)
            _uiState.value = _uiState.value.copy(saveStatus = SaveStatus.Failed, lastError = e.message)
        }
    }
}

private const val TAG = "CaptureViewModel"
