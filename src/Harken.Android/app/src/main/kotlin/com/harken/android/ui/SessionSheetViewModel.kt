package com.harken.android.ui

import android.app.Application
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.data.AppSettings
import com.harken.android.data.SessionRepository
import com.harken.android.data.SpeakerHeuristic
import com.harken.android.data.local.HarkenDatabase
import com.harken.android.network.NetworkModule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.absoluteValue

data class SessionSheetUiState(
    val title: String = "",
    val meta: String = "",
    val tags: List<String> = emptyList(),
    val segments: List<TranscriptRowModel> = emptyList(),
    val summary: String? = null,
    val transcriptMeta: String = "",
    val voiceCount: Int = 1,
    val durationSeconds: Int = 0,
    val playheadSeconds: Int = 0,
    val playing: Boolean = false,
    /** False until GET /sessions/{id}/audio exists — see ADR-0010. */
    val audioAvailable: Boolean = false,
    val waveform: List<Float> = emptyList(),
    val summaryOptionsOpen: Boolean = false,
    val toast: String? = null,
    val error: String? = null,
) {
    val progressFraction: Float
        get() = if (durationSeconds == 0) 0f else (playheadSeconds.toFloat() / durationSeconds).coerceIn(0f, 1f)

    val activeSegmentId: UUID?
        get() = segments.lastOrNull { it.offsetSeconds <= playheadSeconds }?.id

    val plainText: String
        get() = segments.joinToString("\n") { "[${it.offsetSeconds}s] ${it.text}" }
}

class SessionSheetViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private var cachedBaseUrl: String = AppSettings.DefaultBaseUrl
    private val repository = SessionRepository(
        db = HarkenDatabase.get(application),
        api = NetworkModule.create { cachedBaseUrl },
    )

    private val _uiState = MutableStateFlow(SessionSheetUiState())
    val uiState: StateFlow<SessionSheetUiState> = _uiState.asStateFlow()

    private var player: MediaPlayer? = null

    init {
        viewModelScope.launch { settings.baseUrl.collect { cachedBaseUrl = it } }
    }

    fun load(id: UUID) {
        viewModelScope.launch {
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
                    durationSeconds = duration,
                    waveform = waveformFor(id, duration),
                )
            }.collect { _uiState.value = it }
        }
        // Transcription runs async on the backend, so keep reconciling until it settles.
        viewModelScope.launch {
            var attempts = 0
            while (attempts < 200) {
                repository.refreshDetail(id).onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                if (_uiState.value.segments.isNotEmpty() && _uiState.value.summary != null) break
                kotlinx.coroutines.delay(3000)
                attempts += 1
            }
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

    fun summarize(id: UUID) {
        viewModelScope.launch {
            repository.summarize(id)
                .onSuccess { confirm("Summary ready") }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun purge(id: UUID) {
        viewModelScope.launch {
            repository.purge(id).onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
        }
    }

    fun toggleSummaryOptions(open: Boolean) {
        _uiState.value = _uiState.value.copy(summaryOptionsOpen = open)
    }

    fun togglePlay() {
        // BACKEND WORK REQUIRED. Once GET /sessions/{id}/audio ships, point MediaPlayer at
        // "$cachedBaseUrl/sessions/{id}/audio" and drive playheadSeconds from
        // currentPosition. Until then audioAvailable stays false and the controls are
        // disabled with a stated reason — never silently dead.
        if (!_uiState.value.audioAvailable) return
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.start()
        _uiState.value = _uiState.value.copy(playing = p.isPlaying)
    }

    fun seekTo(seconds: Int) {
        _uiState.value = _uiState.value.copy(playheadSeconds = seconds.coerceIn(0, _uiState.value.durationSeconds))
        player?.seekTo(seconds * 1000)
    }

    fun share() { /* wired by the host Activity via ACTION_SEND, unchanged from the previous build */ }

    fun confirm(message: String) {
        _uiState.value = _uiState.value.copy(toast = message)
    }

    override fun onCleared() {
        player?.release()
        player = null
    }

    private fun buildMeta(session: SessionRepository.SessionView?, duration: Int): String {
        if (session == null) return ""
        val length = "${duration / 60}m ${(duration % 60).toString().padStart(2, '0')}s"
        return "${formatSessionTimestamp(session.startedAt)} · $length · whisper base.en"
    }

    /**
     * A deterministic bar pattern for the player's scrub track.
     *
     * Honest framing, unlike the old list rows: this is a SEEK AFFORDANCE, not a
     * rendering of the audio. The API exposes no waveform data, so the bars are derived
     * from the session id and the real duration — they give the thumb something to travel
     * along and they never pretend to be amplitude. If the backend later returns peaks,
     * this is the one function to replace.
     */
    private fun waveformFor(id: UUID, durationSeconds: Int): List<Float> {
        if (durationSeconds == 0) return emptyList()
        val count = 56
        var seed = id.hashCode()
        return List(count) { i ->
            seed = seed * 1_103_515_245 + 12_345
            val noise = ((seed shr 16) and 0x7FFF) / 32_767f
            val envelope = 0.45f + 0.5f * kotlin.math.sin(i / count.toFloat() * Math.PI.toFloat() * 3.1f).absoluteValue
            (noise * 0.5f + envelope * 0.5f).coerceIn(0.12f, 1f)
        }
    }

    private fun stripMarkdown(text: String): String = text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .lines()
        .joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("* ")) "•" + trimmed.removePrefix("*") else line
        }
}
