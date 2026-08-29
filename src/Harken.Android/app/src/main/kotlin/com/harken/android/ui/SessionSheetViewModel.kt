package com.harken.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.data.SessionRepository
import com.harken.android.data.SpeakerHeuristic
import com.harken.android.data.local.HarkenDatabase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID

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
    val toast: String? = null,
    val error: String? = null,
) {
    val plainText: String
        get() = segments.joinToString("\n") { "[${it.offsetSeconds}s] ${it.text}" }
}

class SessionSheetViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = SessionRepository(
        db = HarkenDatabase.get(application),
    )

    private val _uiState = MutableStateFlow(SessionSheetUiState())
    val uiState: StateFlow<SessionSheetUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    fun load(id: UUID) {
        // A prior session's jobs must not keep running once a new one loads — otherwise
        // opening several sessions in one sheet lifetime piles up polling loops that keep
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
                    meta = buildMeta(session, duration),
                    tags = session?.tags.orEmpty(),
                    segments = rows,
                    summary = summary?.summary?.let(::stripMarkdown),
                    transcriptMeta = "${rows.size} segments${if (voices > 1) " · $voices voices" else ""}",
                    voiceCount = voices,
                    status = session?.status,
                    durationSeconds = duration,
                )
            }.collect { _uiState.value = it }
        }
    }

    fun rename(id: UUID, title: String) {
        viewModelScope.launch {
            repository.rename(id, title)
            confirm("Renamed")
        }
    }

    fun addTag(id: UUID, tag: String) {
        viewModelScope.launch { repository.setTags(id, (_uiState.value.tags + tag).distinct()) }
    }

    fun purge(id: UUID) {
        viewModelScope.launch {
            repository.purge(id).onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun share() { /* wired by the host Activity via ACTION_SEND, unchanged from the previous build */ }

    fun confirm(message: String) {
        _uiState.value = _uiState.value.copy(toast = message)
    }

    private fun buildMeta(session: SessionRepository.SessionView?, duration: Int): String {
        if (session == null) return ""
        val length = "${duration / 60}m ${(duration % 60).toString().padStart(2, '0')}s"
        return "${formatSessionTimestamp(session.startedAt)} · $length · whisper base.en"
    }

    private fun stripMarkdown(text: String): String = text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .lines()
        .joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("* ")) "•" + trimmed.removePrefix("*") else line
        }
}
