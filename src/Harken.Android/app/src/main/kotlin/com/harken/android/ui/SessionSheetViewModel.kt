package com.harken.android.ui

import android.app.Application
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.R
import com.harken.android.data.SessionRepository
import com.harken.android.data.SpeakerHeuristic
import com.harken.android.data.local.HarkenDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "SessionSheetViewModel"

data class SessionSheetUiState(
    val title: String = "",
    val meta: String = "",
    val tags: List<String> = emptyList(),
    val segments: List<TranscriptRowModel> = emptyList(),
    val summary: String? = null,
    val transcriptMeta: String = "",
    val voiceCount: Int = 1,
    val durationSeconds: Int = 0,
    val status: String? = null,
    val summaryOptionsOpen: Boolean = false,
    val toast: String? = null,
    val loadError: String? = null,
) {
    val plainText: String
        get() = segments.joinToString("\n") { "[${it.offsetSeconds}s] ${it.text}" }
}

class SessionSheetViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SessionRepository(db = HarkenDatabase.get(application))

    private val _uiState = MutableStateFlow(SessionSheetUiState())
    val uiState: StateFlow<SessionSheetUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    fun load(id: UUID) {
        // A prior session's job must not keep running once a new one loads — otherwise
        // opening several sessions in one sheet lifetime piles up observers that keep
        // rewriting an old session's rows in the background, which is what made the
        // Library list (and this sheet, if reopened) intermittently flicker.
        observeJob?.cancel()

        observeJob = viewModelScope.launch {
            combine(
                repository.observeSession(id),
                repository.observeSegments(id),
                repository.observeSummary(id),
            ) { session, segments, summary ->
                val rows = segments.map { TranscriptRowModel(it.id, it.offsetSeconds, it.text, it.voiceIndex) }
                val voices = SpeakerHeuristic.voiceCount(rows.map { it.voiceIndex })
                val duration = session?.durationSeconds ?: rows.lastOrNull()?.offsetSeconds ?: 0
                _uiState.value.copy(
                    title = session?.title.orEmpty(),
                    meta = buildMeta(session, duration, rows.isNotEmpty()),
                    tags = session?.tags.orEmpty(),
                    segments = rows,
                    summary = summary?.summary?.let(::stripMarkdown),
                    transcriptMeta = transcriptMeta(rows.size, voices),
                    voiceCount = voices,
                    status = session?.status,
                    durationSeconds = duration,
                    loadError = null,
                )
            }.catch { e ->
                Log.e(TAG, "Failed loading session $id", e)
                _uiState.value = _uiState.value.copy(loadError = e.message)
            }.collect { _uiState.value = it }
        }
    }

    fun rename(id: UUID, title: String) {
        viewModelScope.launch {
            try {
                repository.rename(id, title)
                confirm(R.string.session_toast_renamed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed renaming session $id", e)
                _uiState.value = _uiState.value.copy(toast = getApplication<Application>().getString(R.string.session_action_failed))
            }
        }
    }

    fun addTag(id: UUID, tag: String) {
        viewModelScope.launch {
            try {
                repository.setTags(id, (_uiState.value.tags + tag).distinct())
            } catch (e: Exception) {
                Log.e(TAG, "Failed adding tag to session $id", e)
                _uiState.value = _uiState.value.copy(toast = getApplication<Application>().getString(R.string.session_action_failed))
            }
        }
    }

    fun purge(id: UUID) {
        viewModelScope.launch {
            repository.purge(id).onFailure { e ->
                Log.e(TAG, "Failed deleting session $id", e)
                _uiState.value = _uiState.value.copy(toast = getApplication<Application>().getString(R.string.session_action_failed))
            }
        }
    }

    /** Consumed by SessionSheet's Snackbar host once shown, so it doesn't replay on recomposition. */
    fun toastShown() {
        _uiState.value = _uiState.value.copy(toast = null)
    }

    fun toggleSummaryOptions(open: Boolean) {
        _uiState.value = _uiState.value.copy(summaryOptionsOpen = open)
    }

    fun share() { /* wired by the host Activity via ACTION_SEND, unchanged from the previous build */ }

    fun confirm(@StringRes message: Int) {
        _uiState.value = _uiState.value.copy(toast = getApplication<Application>().getString(message))
    }

    /** "12 segments · 3 voices" — the voice clause only appears when there is more than one. */
    private fun transcriptMeta(segmentCount: Int, voices: Int): String {
        val res = getApplication<Application>().resources
        val segments = res.getQuantityString(R.plurals.session_segment_count, segmentCount, segmentCount)
        return if (voices > 1) res.getString(R.string.session_transcript_meta_voices, segments, voices) else segments
    }

    private fun buildMeta(session: SessionRepository.SessionView?, duration: Int, transcribed: Boolean): String {
        if (session == null) return ""
        val length = "${duration / 60}m ${(duration % 60).toString().padStart(2, '0')}s"
        val base = "${formatSessionTimestamp(session.startedAt)} · $length"
        // "whisper base.en" names the one on-device model this app ships (ADR-0011) — true
        // of every transcript, but false to claim for a session nothing has transcribed yet.
        return if (transcribed) "$base · whisper base.en" else base
    }

    private fun stripMarkdown(text: String): String = text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .lines()
        .joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("* ")) "•" + trimmed.removePrefix("*") else line
        }
}
