package com.harken.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.harken.android.R
import com.harken.android.container
import com.harken.android.data.SearchQuery
import com.harken.android.data.SessionRepository
import com.harken.android.recordingTitle
import com.harken.android.speech.TranscriptionCoordinator
import com.harken.android.speech.TranscriptionService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "LibraryViewModel"

data class LibraryUiState(
    val sessions: List<SessionRepository.SessionView> = emptyList(),
    val isLoading: Boolean = true,
    val loadError: String? = null,
    // Non-null while TranscriptionCoordinator is running one session's transcription —
    // Transcribe is disabled on every OTHER "Recorded" row while this is set, since only
    // one on-device transcription runs at a time app-wide.
    val transcribingSessionId: UUID? = null,
    // True from the long-press that starts multi-select to the Close (X) or a completed
    // delete. Kept separate from `selectedIds.isNotEmpty()` so deselecting every row (Delete
    // disabled at zero) does not silently drop the user back to the normal Library header.
    val selecting: Boolean = false,
    val selectedIds: Set<UUID> = emptySet(),
    val toast: String? = null,
)

/** A session mid-transcription cannot be queued for delete: its row and file are being
 *  written by the running decode, and deleting out from under that races it (ARC-062's
 *  neighbourhood of bugs is what this guards against — see the multi-select grill). */
private fun isSelectable(session: SessionRepository.SessionView) = session.status != "Pending" && session.status != "Running"

/**
 * The long-press that opens selection. A no-op on a row [isSelectable] refuses.
 *
 * Kept out of the ViewModel, like [runLibrarySearch], so the guard on a destructive action is
 * testable: the window where a row is actually mid-transcription is too short to drive from a
 * UI test, so this is the only place the rule can be held to.
 */
internal fun LibraryUiState.startingSelection(session: SessionRepository.SessionView) =
    if (!isSelectable(session)) this else copy(selecting = true, selectedIds = setOf(session.id))

/** A tap on a card while already selecting. Refused on the same rows, for the same reason. */
internal fun LibraryUiState.togglingSelection(session: SessionRepository.SessionView) =
    if (!selecting || !isSelectable(session)) {
        this
    } else {
        copy(selectedIds = if (session.id in selectedIds) selectedIds - session.id else selectedIds + session.id)
    }

/**
 * What the search field is showing.
 *
 * Separate from [LibraryUiState] because it changes on every keystroke and the session
 * list does not: folding the two together would recompose every card in the Library while
 * someone types.
 */
data class LibrarySearchState(
    val query: String = "",
    val results: List<SessionRepository.SearchHit> = emptyList(),
    val isSearching: Boolean = false,
) {
    /** True once the term is long enough to have been run — an empty list then means "no matches". */
    val isActive: Boolean get() = query.trim().length >= SearchQuery.MIN_LENGTH
}

/** Reads sessions straight from Room — recordings are transcribed entirely on-device. */
class LibraryViewModel(
    application: Application,
    private val repository: SessionRepository,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    private val _searchState = MutableStateFlow(LibrarySearchState())
    val searchState: StateFlow<LibrarySearchState> = _searchState.asStateFlow()

    init {
        viewModelScope.launch {
            repository
                .observeSessions()
                .catch { e ->
                    Log.e(TAG, "Failed reading sessions from the local database", e)
                    _uiState.value = _uiState.value.copy(isLoading = false, loadError = e.message)
                }.collect { sessions ->
                    _uiState.value = _uiState.value.copy(sessions = sessions, isLoading = false, loadError = null)
                }
        }
        viewModelScope.launch {
            TranscriptionCoordinator.activeSessionId.collect { id ->
                _uiState.value = _uiState.value.copy(transcribingSessionId = id)
            }
        }
        // collectLatest, so a keystroke cancels both the debounce and any query already
        // running for the term before it — the last thing typed is the only thing queried.
        // What a cancelled run must then do is in [runLibrarySearch], where it can be tested.
        viewModelScope.launch {
            _searchState.map { it.query }.distinctUntilChanged().collectLatest { query ->
                runLibrarySearch(_searchState, query, SEARCH_DEBOUNCE_MS, repository::search)
            }
        }
    }

    /** Typing. The query is run [SEARCH_DEBOUNCE_MS] after the last keystroke, not on each one. */
    fun onSearchQueryChange(query: String) {
        _searchState.value = _searchState.value.copy(query = query)
    }

    fun clearSearch() {
        _searchState.value = LibrarySearchState()
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
        val filePath = session.audioPath ?: return
        TranscriptionService.start(
            context = getApplication(),
            sessionId = session.id,
            filePath = filePath,
            title = getApplication<Application>().recordingTitle(session.localTitle, session.partOfDay),
        )
    }

    /** Long-press on a card. */
    fun startSelecting(session: SessionRepository.SessionView) {
        _uiState.update { it.startingSelection(session) }
    }

    /** A tap on a card while already selecting. */
    fun toggleSelection(session: SessionRepository.SessionView) {
        _uiState.update { it.togglingSelection(session) }
    }

    /** The Close (X) in the selection header. Filter and search are untouched — selection
     *  is an overlay on top of them, not a replacement for them. */
    fun clearSelection() {
        _uiState.update { it.copy(selecting = false, selectedIds = emptySet()) }
    }

    /**
     * The confirmed Delete. Each [SessionRepository.purge] runs independently — one file
     * missing on disk must not stop the rest of the batch from going — and any failures are
     * reported together once the batch finishes, on the vocabulary [SessionSheetViewModel]
     * already uses for a single delete's failures.
     */
    fun deleteSelected() {
        val ids = _uiState.value.selectedIds
        viewModelScope.launch {
            var failures = 0
            ids.forEach { id ->
                repository.purge(id).onFailure { e ->
                    Log.e(TAG, "Failed deleting session $id", e)
                    failures++
                }
            }
            val toast =
                if (failures > 0) {
                    getApplication<Application>().resources.getQuantityString(
                        R.plurals.library_selection_delete_partial,
                        failures,
                        ids.size - failures,
                        ids.size,
                    )
                } else {
                    null
                }
            _uiState.update { it.copy(selecting = false, selectedIds = emptySet(), toast = toast) }
        }
    }

    /** Consumed by the Library's Snackbar host once shown, so it doesn't replay on recomposition. */
    fun toastShown() {
        _uiState.update { it.copy(toast = null) }
    }

    /**
     * Counts [visible], not everything held: the subtitle sits directly above a filtered
     * list, and reading "4 recordings" over an empty Field filter made the filter look
     * broken rather than empty.
     */
    fun subtitle(
        state: LibraryUiState,
        visible: List<SessionRepository.SessionView> = state.sessions,
    ): String {
        // "Recorded" sessions are waiting on the user, not actively transcribing — not
        // counted here.
        val transcribing = visible.count { it.status == "Pending" || it.status == "Running" }
        val res = getApplication<Application>().resources
        val count = res.getQuantityString(R.plurals.library_recording_count, visible.size, visible.size)
        return if (transcribing > 0) {
            res.getQuantityString(R.plurals.library_subtitle_transcribing, transcribing, count, transcribing)
        } else {
            count
        }
    }

    companion object {
        /** Long enough that a typed word runs one query, short enough to feel immediate. */
        private const val SEARCH_DEBOUNCE_MS = 180L

        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    LibraryViewModel(
                        application = checkNotNull(this[APPLICATION_KEY]),
                        repository = container.repository,
                    )
                }
            }
    }
}
