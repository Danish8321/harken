package com.harken.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.data.AppSettings
import com.harken.android.data.SessionRepository
import com.harken.android.data.TranscriptionProviderChoice
import com.harken.android.data.local.HarkenDatabase
import com.harken.android.network.AudioSource
import com.harken.android.network.HarkenApi
import com.harken.android.network.NetworkModule
import com.harken.android.recording.RecordingController
import com.harken.android.recording.RecordingState
import com.harken.android.speech.ModelDownloadManager
import com.harken.android.speech.OnDeviceTranscriber
import java.io.File
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

// DownloadingModel/Transcribing are the on-device (WhisperLocal) equivalents of
// Uploading — no network call happens in either state, unlike Uploading.
enum class UploadStatus { Idle, DownloadingModel, Transcribing, Uploading, Succeeded, Failed }

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
    private val repository = SessionRepository(
        db = HarkenDatabase.get(application),
        api = NetworkModule.create { cachedBaseUrl },
    )
    private val modelDownloadManager = ModelDownloadManager(application)
    private val onDeviceTranscriber = OnDeviceTranscriber()

    private var cachedBaseUrl: String = AppSettings.DefaultBaseUrl
    private var cachedProvider: TranscriptionProviderChoice = TranscriptionProviderChoice.WhisperLocal
    private var lastRecordingId: java.util.UUID? = null
    private var lastFilePath: String? = null

    // A retry re-enters transcribeOnDevice with the same recordingId as the failed
    // attempt (see retryUpload) — that's expected, not the id collision insertLocalOnly's
    // plain @Insert is meant to guard against. Track which ids already have a local
    // session row so a retry skips re-creating it instead of hitting that guard.
    private val localSessionsCreated = mutableSetOf<java.util.UUID>()

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settings.baseUrl.collect { cachedBaseUrl = it }
        }
        viewModelScope.launch {
            settings.transcriptionProvider.collect { cachedProvider = it }
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
            _uiState.value = _uiState.value.copy(lastError = null)
            if (cachedProvider == TranscriptionProviderChoice.WhisperLocal) {
                transcribeOnDevice(recordingId, filePath)
            } else {
                uploadToBackend(recordingId, filePath)
            }
        }
    }

    // WhisperLocal (ADR-0011): the whole path below — model download, native inference,
    // Room write — never makes a network call. api.upload(...) is never reached here.
    private suspend fun transcribeOnDevice(recordingId: java.util.UUID, filePath: String) {
        _uiState.value = _uiState.value.copy(uploadStatus = UploadStatus.DownloadingModel)
        try {
            if (localSessionsCreated.add(recordingId)) {
                repository.createLocalSession(
                    id = recordingId,
                    startedAt = Instant.now().toString(),
                    source = AudioSource.Microphone.name,
                )
            }

            val modelPath = modelDownloadManager.ensureModel().getOrThrow()

            _uiState.value = _uiState.value.copy(uploadStatus = UploadStatus.Transcribing)
            val segments = onDeviceTranscriber.transcribe(filePath, modelPath)
            val durationSeconds = segments.maxOfOrNull { it.offsetSeconds } ?: 0

            repository.completeLocal(recordingId, segments, durationSeconds)
            _uiState.value = _uiState.value.copy(uploadStatus = UploadStatus.Succeeded, lastSessionId = recordingId)
        } catch (e: Exception) {
            repository.failLocal(recordingId, e.message ?: "On-device transcription failed")
            _uiState.value = _uiState.value.copy(uploadStatus = UploadStatus.Failed, lastError = e.message)
        }
    }

    private suspend fun uploadToBackend(recordingId: java.util.UUID, filePath: String) {
        _uiState.value = _uiState.value.copy(uploadStatus = UploadStatus.Uploading)
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
