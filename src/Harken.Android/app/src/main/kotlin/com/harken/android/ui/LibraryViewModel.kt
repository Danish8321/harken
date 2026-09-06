package com.harken.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import com.harken.android.R
import com.harken.android.container
import com.harken.android.data.SessionRepository
import com.harken.android.speech.TranscriptionCoordinator
import com.harken.android.speech.TranscriptionService
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private const val TAG = "LibraryViewModel"

data class LibraryUiState(
    val sessions: List<SessionRepository.SessionView> = emptyList(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
    // Non-null while TranscriptionCoordinator is running one session's transcription —
    // Transcribe is disabled on every OTHER "Recorded" row while this is set, since only
    // one on-device transcription runs at a time app-wide.
    val transcribingSessionId: UUID? = null,
)

/** Reads sessions straight from Room — recordings are transcribed entirely on-device. */
class LibraryViewModel(
    application: Application,
    private val repository: SessionRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.observeSessions()
                .catch { e ->
                    Log.e(TAG, "Failed reading sessions from the local database", e)
                    _uiState.value = _uiState.value.copy(isLoading = false, loadError = e.message)
                }
                .collect { sessions ->
                    _uiState.value = _uiState.value.copy(sessions = sessions, isLoading = false, loadError = null)
                }
        }
        viewModelScope.launch {
            TranscriptionCoordinator.activeSessionId.collect { id ->
                _uiState.value = _uiState.value.copy(transcribingSessionId = id)
            }
        }
    }

    /**
     * Starts on-device transcription for a "Recorded" session. No-op if one is already
     * running — [TranscriptionCoordinator] holds that invariant and the service defers to
     * it.
     *
     * Goes through [TranscriptionService] rather than calling the coordinator directly:
     * the coordinator survives this ViewModel, but nothing here survives the *process*
     * being reclaimed, and a decode holding whisper's working set in a cached process is
     * the first thing Android takes (ARC-003).
     */
    fun transcribe(session: SessionRepository.SessionView) {
        val filePath = session.pendingUploadPath ?: return
        TranscriptionService.start(
            context = getApplication(),
            sessionId = session.id,
            filePath = filePath,
            title = session.title,
        )
    }

    /**
     * Counts [visible], not everything held: the subtitle sits directly above a filtered
     * list, and reading "4 recordings" over an empty Field filter made the filter look
     * broken rather than empty.
     */
    fun subtitle(state: LibraryUiState, visible: List<SessionRepository.SessionView> = state.sessions): String {
        // "Recorded" sessions are waiting on the user, not actively transcribing — not
        // counted here.
        val transcribing = visible.count { it.status == "Pending" || it.status == "Running" }
        val res = getApplication<Application>().resources
        val count = res.getQuantityString(R.plurals.library_recording_count, visible.size, visible.size)
        return if (transcribing > 0) res.getString(R.string.library_subtitle_transcribing, count, transcribing) else count
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                LibraryViewModel(
                    application = checkNotNull(this[APPLICATION_KEY]),
                    repository = container.repository,
                )
            }
        }
    }
}
