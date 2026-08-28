package com.harken.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.data.SessionRepository
import com.harken.android.data.local.HarkenDatabase
import com.harken.android.speech.ModelDownloadManager
import com.harken.android.speech.OnDeviceTranscriber
import com.harken.android.speech.TranscriptionCoordinator
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(
    val sessions: List<SessionRepository.SessionView> = emptyList(),
    val isLoading: Boolean = true,
    // Non-null while TranscriptionCoordinator is running one session's transcription —
    // Transcribe is disabled on every OTHER "Recorded" row while this is set, since only
    // one on-device transcription runs at a time app-wide.
    val transcribingSessionId: UUID? = null,
)

/** Reads from Room; there is no backend to reconcile against (ADR-0011). */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SessionRepository(
        db = HarkenDatabase.get(application),
    )
    private val modelDownloadManager = ModelDownloadManager(application)
    private val onDeviceTranscriber = OnDeviceTranscriber()

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    var onNavigateToRecord: (() -> Unit)? = null
    var onNavigateToSettings: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            repository.observeSessions().collect { sessions ->
                _uiState.value = _uiState.value.copy(sessions = sessions, isLoading = false)
            }
        }
        viewModelScope.launch {
            TranscriptionCoordinator.activeSessionId.collect { id ->
                _uiState.value = _uiState.value.copy(transcribingSessionId = id)
            }
        }
    }

    /** Starts on-device transcription for a "Recorded" session. No-op if one is already running. */
    fun transcribe(session: SessionRepository.SessionView) {
        val filePath = session.pendingUploadPath ?: return
        TranscriptionCoordinator.transcribe(
            repository = repository,
            modelDownloadManager = modelDownloadManager,
            onDeviceTranscriber = onDeviceTranscriber,
            sessionId = session.id,
            filePath = filePath,
        )
    }

    fun subtitle(state: LibraryUiState): String {
        val transcribing = state.sessions.count { it.status == "Pending" || it.status == "Running" }
        // "Recorded" sessions are waiting on the user, not actively transcribing — not
        // counted here.
        val count = "${state.sessions.size} recording${if (state.sessions.size == 1) "" else "s"}"
        return if (transcribing > 0) "$count · $transcribing transcribing" else count
    }

    fun goToRecord() { onNavigateToRecord?.invoke() }
    fun openSettings() { onNavigateToSettings?.invoke() }
}
