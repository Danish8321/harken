package com.harken.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.data.AppSettings
import com.harken.android.data.SessionRepository
import com.harken.android.data.local.HarkenDatabase
import com.harken.android.network.NetworkModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LibraryUiState(
    val sessions: List<SessionRepository.SessionView> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val baseUrl: String = AppSettings.DefaultBaseUrl,
)

/**
 * Reads from Room, refreshes from the API. The list is never blocked on the network:
 * observeSessions() emits the mirror immediately and refresh() reconciles behind it, so
 * an unreachable backend degrades to "slightly stale" rather than "empty screen".
 */
class LibraryViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private var cachedBaseUrl: String = AppSettings.DefaultBaseUrl
    private val repository = SessionRepository(
        db = HarkenDatabase.get(application),
        api = NetworkModule.create { cachedBaseUrl },
    )

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    var onNavigateToRecord: (() -> Unit)? = null
    var onNavigateToSettings: (() -> Unit)? = null

    init {
        viewModelScope.launch {
            settings.baseUrl.collect { url ->
                cachedBaseUrl = url
                _uiState.value = _uiState.value.copy(baseUrl = url.removePrefix("http://").removePrefix("https://"))
            }
        }
        viewModelScope.launch {
            repository.observeSessions().collect { sessions ->
                _uiState.value = _uiState.value.copy(sessions = sessions, isLoading = false)
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(error = null)
            repository.refresh().onFailure { e ->
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    fun subtitle(state: LibraryUiState): String {
        val transcribing = state.sessions.count { it.status == "Pending" || it.status == "Running" }
        val count = "${state.sessions.size} recording${if (state.sessions.size == 1) "" else "s"}"
        return if (transcribing > 0) "$count · $transcribing transcribing" else count
    }

    fun goToRecord() { onNavigateToRecord?.invoke() }
    fun openSettings() { onNavigateToSettings?.invoke() }
}
