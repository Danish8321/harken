package com.harken.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.data.AppSettings
import com.harken.android.network.AudioSource
import com.harken.android.network.HarkenApi
import com.harken.android.network.NetworkModule
import com.harken.android.recording.RecordingController
import com.harken.android.recording.RecordingState
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

enum class UploadStatus { Idle, Uploading, Succeeded, Failed }

data class CaptureUiState(
    val isRecording: Boolean = false,
    val uploadStatus: UploadStatus = UploadStatus.Idle,
    val lastError: String? = null,
    val lastSessionId: java.util.UUID? = null,
)

// Ports the upload half of src/Harken.Mobile's CapturePageViewModel: every stop routes
// through RecordingState.completed (manual Stop tap, silence timeout, session cap alike)
// so an auto-stop uploads the same way a manual one does (ADR-0007).
class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private val api: HarkenApi = NetworkModule.create { runBlockingBaseUrl() }

    private var cachedBaseUrl: String = AppSettings.DefaultBaseUrl
    private var lastRecordingId: java.util.UUID? = null
    private var lastFilePath: String? = null

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.baseUrl.collect { cachedBaseUrl = it }
        }
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

    private fun runBlockingBaseUrl(): String = cachedBaseUrl

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
            _uiState.value = _uiState.value.copy(uploadStatus = UploadStatus.Uploading, lastError = null)
            try {
                val file = File(filePath)
                val audioPart = MultipartBody.Part.createFormData(
                    "audio", file.name, file.asRequestBody("audio/wav".toMediaType()),
                )
                val sourcePart = AudioSource.Microphone.name.toRequestBody("text/plain".toMediaType())
                val recordingIdPart = recordingId.toString().toRequestBody("text/plain".toMediaType())

                val response = api.upload(audioPart, sourcePart, recordingIdPart)
                _uiState.value = if (response.isSuccessful) {
                    _uiState.value.copy(uploadStatus = UploadStatus.Succeeded, lastSessionId = response.body()?.id)
                } else {
                    _uiState.value.copy(uploadStatus = UploadStatus.Failed, lastError = "HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(uploadStatus = UploadStatus.Failed, lastError = e.message)
            }
        }
    }
}
