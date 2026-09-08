package com.harken.android.ui

import android.app.Application
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.harken.android.R
import com.harken.android.container
import com.harken.android.data.SessionRepository
import com.harken.android.data.SpeakerHeuristic
import com.harken.android.data.TranscriptText
import com.harken.android.recordingTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private const val TAG = "SessionSheetViewModel"

// Fast enough that the scrubber and the highlighted transcript line track the audio, slow
// enough not to recompose the sheet on every frame.
private const val PLAYBACK_TICK_MS = 200L

data class SessionSheetUiState(
    val title: String = "",
    /**
     * False while [title] is the name derived from the time of day. The rename field
     * seeds from this: prefilling a derived title made "save without editing" quietly
     * freeze it as a real one, and there was then no way back to a derived name.
     */
    val hasLocalTitle: Boolean = false,
    val meta: String = "",
    val tags: List<String> = emptyList(),
    val segments: List<TranscriptRowModel> = emptyList(),
    val transcriptMeta: String = "",
    val voiceCount: Int = 1,
    val durationSeconds: Int = 0,
    val status: String? = null,
    val toast: String? = null,
    val loadError: String? = null,
    /** The WAV this session was recorded to, or null once it is no longer on disk. */
    val audioPath: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    /**
     * Taken from the decoder once the file is opened, falling back to the session's own
     * duration so the scrubber has a scale before the first tap.
     */
    val playbackDurationMs: Int = 0,
) {
    /** The transcript as text, formatted the same way an exported .txt is. */
    val plainText: String
        get() = TranscriptText.body(segments.map { TranscriptText.Line(it.offsetSeconds, it.text) })
}

class SessionSheetViewModel(
    application: Application,
    private val repository: SessionRepository,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(SessionSheetUiState())
    val uiState: StateFlow<SessionSheetUiState> = _uiState.asStateFlow()

    private var observeJob: Job? = null

    // One player at a time, created on the first play tap and released with the sheet.
    private var player: MediaPlayer? = null
    private var ticker: Job? = null

    /** This ViewModel's own Context, for the strings the repository deliberately does
     * not produce (ARC-017). */
    private val app: Application get() = getApplication()

    fun load(id: UUID) {
        // A prior session's job must not keep running once a new one loads — otherwise
        // opening several sessions in one sheet lifetime piles up observers that keep
        // rewriting an old session's rows in the background, which is what made the
        // Library list (and this sheet, if reopened) intermittently flicker.
        observeJob?.cancel()

        observeJob =
            viewModelScope.launch {
                combine(
                    repository.observeSession(id),
                    repository.observeSegments(id),
                ) { session, segments ->
                    val rows = segments.map { TranscriptRowModel(it.id, it.offsetSeconds, it.text, it.voiceIndex) }
                    val voices = SpeakerHeuristic.voiceCount(rows.map { it.voiceIndex })
                    val duration = session?.durationSeconds ?: rows.lastOrNull()?.offsetSeconds ?: 0
                    _uiState.value.copy(
                        title = session?.let { app.recordingTitle(it.localTitle, it.partOfDay) }.orEmpty(),
                        hasLocalTitle = session?.localTitle != null,
                        meta = buildMeta(session, duration, rows.isNotEmpty()),
                        tags = session?.tags.orEmpty(),
                        segments = rows,
                        transcriptMeta = transcriptMeta(rows.size, voices),
                        voiceCount = voices,
                        status = session?.status,
                        durationSeconds = duration,
                        loadError = null,
                        audioPath = session?.audioPath?.takeIf { java.io.File(it).exists() },
                        playbackDurationMs =
                            _uiState.value.playbackDurationMs.takeIf { it > 0 }
                                ?: (duration * 1000),
                    )
                }.flowOn(Dispatchers.Default)
                    .catch { e ->
                        Log.e(TAG, "Failed loading session $id", e)
                        _uiState.value = _uiState.value.copy(loadError = e.message)
                    }.collect { _uiState.value = it }
            }
    }

    /**
     * Starts playing, or pauses if it already is.
     *
     * The player is created on the first tap rather than at load, so opening a session to
     * read its transcript costs no decoder. It survives pausing — pausing and resuming
     * keeps the position — and is torn down when the sheet closes or another session
     * loads.
     */
    fun togglePlayback() {
        val existing = player
        if (existing != null) {
            if (existing.isPlaying) {
                existing.pause()
                ticker?.cancel()
                _uiState.value = _uiState.value.copy(isPlaying = false)
            } else {
                existing.start()
                startTicking()
            }
            return
        }

        val path = _uiState.value.audioPath ?: return
        try {
            player =
                MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    setDataSource(path)
                    prepare()
                    setOnCompletionListener {
                        // Rewind rather than sit at the end, so the same button plays it again.
                        ticker?.cancel()
                        seekTo(0)
                        _uiState.value = _uiState.value.copy(isPlaying = false, positionMs = 0)
                    }
                    seekTo(_uiState.value.positionMs)
                    start()
                }
            _uiState.value = _uiState.value.copy(playbackDurationMs = player?.duration ?: 0)
            startTicking()
        } catch (e: Exception) {
            Log.e(TAG, "Failed playing ${_uiState.value.audioPath}", e)
            releasePlayer()
            _uiState.value =
                _uiState.value.copy(
                    toast = getApplication<Application>().getString(R.string.session_playback_failed),
                )
        }
    }

    /** Moves the playhead, whether or not anything is playing yet. */
    fun seekTo(positionMs: Int) {
        val target = positionMs.coerceAtLeast(0)
        player?.seekTo(target)
        _uiState.value = _uiState.value.copy(positionMs = target)
    }

    /** Jumps to a transcript segment. Tapping a line is how you navigate a long recording. */
    fun seekToSegment(offsetSeconds: Int) = seekTo(offsetSeconds * 1000)

    /**
     * Drops the player and its ticker. Called when the sheet closes: a MediaPlayer holds a
     * decoder the rest of the system wants back, and audio playing on from a sheet the user
     * has dismissed is not something they asked for.
     */
    fun stopPlayback() {
        releasePlayer()
        // playbackDurationMs too: this runs before load() for whichever session opens
        // next (SessionSheet disposes the old sessionId key before the new one's
        // LaunchedEffect fires), and load()'s combine only replaces a nonzero value with
        // the decoder's own — otherwise a session opened after one that had already been
        // played kept showing that other recording's duration until playback started.
        _uiState.value = _uiState.value.copy(isPlaying = false, positionMs = 0, playbackDurationMs = 0)
    }

    private fun startTicking() {
        ticker?.cancel()
        _uiState.value = _uiState.value.copy(isPlaying = true)
        ticker =
            viewModelScope.launch {
                while (true) {
                    val current = player ?: break
                    _uiState.value = _uiState.value.copy(positionMs = current.currentPosition)
                    delay(PLAYBACK_TICK_MS)
                }
            }
    }

    private fun releasePlayer() {
        ticker?.cancel()
        ticker = null
        player?.release()
        player = null
    }

    override fun onCleared() {
        releasePlayer()
    }

    /** A blank [title] clears the local name, so the session goes back to its derived one. */
    fun rename(
        id: UUID,
        title: String,
    ) {
        viewModelScope.launch {
            try {
                repository.rename(id, title.ifBlank { null })
                confirm(R.string.session_toast_renamed)
            } catch (e: Exception) {
                Log.e(TAG, "Failed renaming session $id", e)
                _uiState.value = _uiState.value.copy(toast = getApplication<Application>().getString(R.string.session_action_failed))
            }
        }
    }

    fun addTag(
        id: UUID,
        tag: String,
    ) = setTags(id, (_uiState.value.tags + tag).distinct(), "adding")

    fun removeTag(
        id: UUID,
        tag: String,
    ) = setTags(id, _uiState.value.tags - tag, "removing")

    private fun setTags(
        id: UUID,
        tags: List<String>,
        verb: String,
    ) {
        viewModelScope.launch {
            try {
                repository.setTags(id, tags)
            } catch (e: Exception) {
                Log.e(TAG, "Failed $verb tag on session $id", e)
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

    /**
     * Shares the transcript as text.
     *
     * This used to be an empty method behind a live Share button, so the button did
     * nothing at all (ARC-033).
     */
    fun shareTranscript() {
        val text = _uiState.value.plainText
        if (text.isBlank()) {
            confirm(R.string.session_share_nothing_yet)
            return
        }
        send(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, _uiState.value.title)
                putExtra(Intent.EXTRA_TEXT, text)
            },
            R.string.session_share_transcript,
        )
    }

    /**
     * Shares the recording itself.
     *
     * The user's own copy is the only backup this app can offer: nothing is uploaded and,
     * since ARC-002, nothing is in Auto Backup either. The URI is granted read-only, for
     * this one file, to whichever app the user picks — the recording is not made readable
     * to anything by existing.
     */
    fun shareAudio() {
        val path = _uiState.value.audioPath
        val file = path?.let(::File)
        if (file == null || !file.exists()) {
            confirm(R.string.session_share_audio_missing)
            return
        }
        val app = getApplication<Application>()
        val uri =
            runCatching { FileProvider.getUriForFile(app, "${app.packageName}.files", file) }
                .getOrElse { e ->
                    Log.e(TAG, "Could not build a share URI for $path", e)
                    confirm(R.string.session_share_audio_failed)
                    return
                }
        send(
            Intent(Intent.ACTION_SEND).apply {
                type = "audio/x-wav"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, _uiState.value.title)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            R.string.session_share_audio,
        )
    }

    /**
     * NEW_TASK because this starts from the application context: a ViewModel outlives the
     * composable that called it and holds no Activity to start from.
     */
    private fun send(
        intent: Intent,
        @StringRes chooserTitle: Int,
    ) {
        val app = getApplication<Application>()
        val chooser =
            Intent
                .createChooser(intent, app.getString(chooserTitle))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(chooser) }
            .onFailure { e ->
                Log.e(TAG, "No app accepted the share intent", e)
                confirm(R.string.session_share_no_target)
            }
    }

    fun confirm(
        @StringRes message: Int,
    ) {
        _uiState.value = _uiState.value.copy(toast = getApplication<Application>().getString(message))
    }

    /** "12 segments · 3 voices" — the voice clause only appears when there is more than one. */
    private fun transcriptMeta(
        segmentCount: Int,
        voices: Int,
    ): String {
        val res = getApplication<Application>().resources
        val segments = res.getQuantityString(R.plurals.session_segment_count, segmentCount, segmentCount)
        return if (voices > 1) {
            res.getQuantityString(R.plurals.session_transcript_meta_voices, voices, segments, voices)
        } else {
            segments
        }
    }

    private fun buildMeta(
        session: SessionRepository.SessionView?,
        duration: Int,
        transcribed: Boolean,
    ): String {
        if (session == null) return ""
        val length = TranscriptText.timestamp(duration)
        val base = "${formatSessionTimestamp(session.startedAt)} · $length"
        // "whisper base.en" names the one on-device model this app ships (ADR-0011) — true
        // of every transcript, but false to claim for a session nothing has transcribed yet.
        return if (transcribed) "$base · whisper base.en" else base
    }

    companion object {
        val Factory: ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    SessionSheetViewModel(
                        application = checkNotNull(this[APPLICATION_KEY]),
                        repository = container.repository,
                    )
                }
            }
    }
}
