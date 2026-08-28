package com.harken.android.ui

import android.app.Application
import androidx.annotation.StringRes
import android.media.MediaPlayer
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.harken.android.R
import com.harken.android.data.AppSettings
import com.harken.android.data.SessionRepository
import com.harken.android.data.SpeakerHeuristic
import com.harken.android.data.local.HarkenDatabase
import com.harken.android.network.NetworkModule
import kotlinx.coroutines.Job
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
    val status: String? = null,
    val summarizing: Boolean = false,
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
    private var currentSessionId: UUID? = null
    private var observeJob: Job? = null
    private var pollJob: Job? = null
    private var tickerJob: Job? = null

    init {
        viewModelScope.launch { settings.baseUrl.collect { cachedBaseUrl = it } }
    }

    fun load(id: UUID) {
        // A prior session's jobs must not keep running once a new one loads — otherwise
        // opening several sessions in one sheet lifetime piles up polling loops that keep
        // rewriting an old session's rows in the background, which is what made the
        // Library list (and this sheet, if reopened) intermittently flicker.
        observeJob?.cancel()
        pollJob?.cancel()
        currentSessionId = id
        releasePlayer()

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
                    transcriptMeta = transcriptMeta(rows.size, voices),
                    voiceCount = voices,
                    status = session?.status,
                    durationSeconds = duration,
                    audioAvailable = session != null,
                    waveform = waveformFor(id, duration),
                )
            }.collect { _uiState.value = it }
        }
        // Transcription runs async on the backend, so keep reconciling until it reaches a
        // terminal state (Succeeded or Failed) — not until a summary shows up, since a
        // summary is only ever generated on request and would otherwise never arrive,
        // leaving this polling and rewriting the session's rows for up to ten minutes.
        pollJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < 200) {
                repository.refreshDetail(id).onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
                val status = _uiState.value.status
                if (status == "Succeeded" || status == "Failed") break
                kotlinx.coroutines.delay(3000)
                attempts += 1
            }
        }
    }

    fun rename(id: UUID, title: String) {
        viewModelScope.launch {
            repository.rename(id, title)
            confirm(R.string.session_toast_renamed)
        }
    }

    fun addTag(id: UUID, tag: String) {
        viewModelScope.launch { repository.setTags(id, (_uiState.value.tags + tag).distinct()) }
    }

    fun summarize(id: UUID) {
        if (_uiState.value.summarizing) return
        _uiState.value = _uiState.value.copy(summarizing = true)
        viewModelScope.launch {
            repository.summarize(id)
                .onSuccess { confirm(R.string.session_toast_summary_ready) }
                .onFailure { e -> _uiState.value = _uiState.value.copy(error = e.message) }
            _uiState.value = _uiState.value.copy(summarizing = false)
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
        if (!_uiState.value.audioAvailable) return
        val id = currentSessionId ?: return

        val p = player
        if (p == null) {
            preparePlayer(id)
            return
        }
        if (p.isPlaying) {
            p.pause()
            tickerJob?.cancel()
        } else {
            p.start()
            startTicker(p)
        }
        _uiState.value = _uiState.value.copy(playing = p.isPlaying)
    }

    private fun preparePlayer(id: UUID) {
        val url = "${cachedBaseUrl.trimEnd('/')}/sessions/$id/audio"
        val p = MediaPlayer()
        player = p
        try {
            p.setDataSource(url)
            p.setOnPreparedListener { prepared ->
                prepared.seekTo(_uiState.value.playheadSeconds * 1000)
                prepared.start()
                _uiState.value = _uiState.value.copy(playing = true)
                startTicker(prepared)
            }
            p.setOnCompletionListener {
                tickerJob?.cancel()
                _uiState.value = _uiState.value.copy(playing = false, playheadSeconds = 0)
            }
            p.prepareAsync()
        } catch (e: Exception) {
            player = null
            _uiState.value = _uiState.value.copy(error = e.message)
        }
    }

    private fun startTicker(p: MediaPlayer) {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            while (true) {
                _uiState.value = _uiState.value.copy(playheadSeconds = p.currentPosition / 1000)
                kotlinx.coroutines.delay(200)
            }
        }
    }

    fun seekTo(seconds: Int) {
        val clamped = seconds.coerceIn(0, _uiState.value.durationSeconds)
        _uiState.value = _uiState.value.copy(playheadSeconds = clamped)
        player?.seekTo(clamped * 1000)
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

    private fun releasePlayer() {
        tickerJob?.cancel()
        player?.release()
        player = null
    }

    override fun onCleared() {
        releasePlayer()
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
