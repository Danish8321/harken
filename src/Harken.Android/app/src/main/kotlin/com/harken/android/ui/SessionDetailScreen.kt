package com.harken.android.ui

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.data.AppSettings
import com.harken.android.network.HarkenApi
import com.harken.android.network.NetworkModule
import com.harken.android.network.SessionDetail
import com.harken.android.network.TranscriptSegmentView
import com.harken.android.network.TranscriptionStatus
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SessionDetailUiState(
    val detail: SessionDetail? = null,
    val isLoading: Boolean = true,
    val isSummarizing: Boolean = false,
    val error: String? = null,
)

class SessionDetailViewModel(application: Application) : AndroidViewModel(application) {
    private val settings = AppSettings(application)
    private var cachedBaseUrl: String = AppSettings.DefaultBaseUrl
    private val api: HarkenApi = NetworkModule.create { cachedBaseUrl }

    private val _uiState = MutableStateFlow(SessionDetailUiState())
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { settings.baseUrl.collect { cachedBaseUrl = it } }
    }

    fun load(id: UUID) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            fetchOnce(id)

            // Transcription runs async on the backend, so the screen must poll until it
            // settles. Treat null the same as Pending/Running — the contract's status field
            // is nullable, and stopping on the first unrecognized value would strand the
            // screen on a stale fetch until the user leaves and reopens it. Longer
            // recordings can take several minutes to transcribe, so cap generously
            // (~10 minutes) rather than give up and force a manual reopen to resume.
            var attempts = 0
            while (attempts < 200 &&
                _uiState.value.detail?.transcriptionStatus.let {
                    it == null || it == TranscriptionStatus.Pending || it == TranscriptionStatus.Running
                }
            ) {
                kotlinx.coroutines.delay(3000)
                fetchOnce(id)
                attempts += 1
            }
        }
    }

    private suspend fun fetchOnce(id: UUID) {
        try {
            val response = api.getSession(id)
            if (response.isSuccessful) {
                _uiState.value = _uiState.value.copy(detail = response.body(), isLoading = false, error = null)
            } else {
                _uiState.value = _uiState.value.copy(isLoading = false, error = "HTTP ${response.code()}")
            }
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
        }
    }

    fun summarize(id: UUID) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSummarizing = true, error = null)
            try {
                val response = api.summarize(id)
                if (response.isSuccessful) {
                    // Re-fetch rather than patching the cached detail locally: Gson populates
                    // this data class via reflection and can leave a Kotlin non-null field
                    // holding null, which only surfaces (as a crash) the moment something
                    // calls .copy() on it. A full re-fetch sidesteps that entirely.
                    fetchOnce(id)
                    _uiState.value = _uiState.value.copy(isSummarizing = false)
                } else {
                    _uiState.value = _uiState.value.copy(isSummarizing = false, error = "HTTP ${response.code()}")
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isSummarizing = false, error = e.message)
            }
        }
    }
}

// Opens as a modal sheet sliding up over whichever tab is active (Capture or
// Recordings), not a pushed screen — a session is content to review and hand off,
// dismissed with a swipe or the close button, not backed out of like a stack frame.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailModal(
    sessionId: UUID,
    onDismiss: () -> Unit,
    viewModel: SessionDetailViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    // Backgrounding mid-transcription (e.g. screen off, app-switch) can outlast the poll
    // window; resume polling on return to foreground instead of requiring a full reopen.
    androidx.compose.runtime.DisposableEffect(sessionId, lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.load(sessionId)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        // Cap the sheet to most of the screen so the transcript area below gets a
        // bounded height to scroll within, with the summary pinned outside that scroll.
        Column(modifier = Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Transcript", style = MaterialTheme.typography.titleLarge)
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.CenterStart).size(44.dp),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
                if (state.detail != null) {
                    IconButton(
                        onClick = { shareSession(context, state.detail!!) },
                        modifier = Modifier.align(Alignment.CenterEnd).size(44.dp),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share transcript")
                    }
                }
            }

            if (state.error != null && state.detail != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        state.error ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Crossfade(targetState = state.detail != null, label = "detail-crossfade") { hasDetail ->
                    if (hasDetail) {
                        TranscriptSection(detail = state.detail!!)
                    } else if (state.error != null) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                            Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TranscriptSkeleton(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            // Fixed below the scrollable transcript, not part of it — the summary
            // stays reachable without scrolling through the whole transcript first.
            state.detail?.let { detail ->
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SummarySection(
                        summary = detail.summary?.summary,
                        isSummarizing = state.isSummarizing,
                        transcriptionStatus = detail.transcriptionStatus,
                        onSummarize = { viewModel.summarize(sessionId) },
                    )
                }
            }
        }
    }
}

private fun shareSession(context: android.content.Context, detail: SessionDetail) {
    val body = buildString {
        detail.summary?.summary?.let { appendLine(stripMarkdown(it)); appendLine() }
        detail.segments.forEach { appendLine("[${formatOffset(it.offset)}] ${it.text}") }
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, body)
    }
    context.startActivity(android.content.Intent.createChooser(intent, "Share transcript"))
}

// Backend sends raw TimeSpan strings like "00:00:03.2000000" — trim to whole seconds
// for display; a fractional-tick offset isn't meaningful to a reader.
private fun formatOffset(offset: String): String = offset.substringBefore(".")

// Pulsing placeholder bars shown while the first fetch is in flight, so the screen
// reads as "loading" rather than a blank frame.
@Composable
private fun TranscriptSkeleton(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransitionForSkeleton()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), repeatMode = RepeatMode.Reverse),
        label = "skeletonAlpha",
    )
    val barColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha)

    Column(modifier = modifier.padding(16.dp)) {
        Box(modifier = Modifier.fillMaxWidth(0.5f).height(24.dp).clip(MaterialTheme.shapes.small).background(barColor))
        Box(modifier = Modifier.padding(top = 24.dp).fillMaxWidth(0.3f).height(16.dp).clip(MaterialTheme.shapes.small).background(barColor))
        repeat(6) { index ->
            Box(
                modifier = Modifier
                    .padding(top = if (index == 0) 16.dp else 12.dp)
                    .fillMaxWidth(if (index % 2 == 0) 0.9f else 0.7f)
                    .height(14.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(barColor),
            )
        }
    }
}

@Composable
private fun rememberInfiniteTransitionForSkeleton() =
    androidx.compose.animation.core.rememberInfiniteTransition(label = "skeleton")

// Collapsible so a long summary doesn't push the transcript below the fold by default.
@Composable
private fun SummarySection(
    summary: String?,
    isSummarizing: Boolean,
    transcriptionStatus: TranscriptionStatus?,
    onSummarize: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var expanded by remember(summary) { mutableStateOf(true) }
    val chevronRotation by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevronRotation",
    )

    if (summary != null) {
        // A real Button, not a Row+clickable — gets Material's ripple, focus, and
        // accessibility semantics (toggle role, expanded/collapsed state) for free,
        // where a bare clickable Row silently drops all of that.
        androidx.compose.material3.TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth()
                .semantics {
                    role = Role.Button
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            shape = com.harken.android.ui.theme.PillShape,
            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Summary", style = MaterialTheme.typography.labelLarge)
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse summary" else "Expand summary",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation),
                )
            }
        }

        AnimatedVisibility(visible = expanded, enter = expandVertically(), exit = shrinkVertically()) {
            // Bordered + shadowed like the sheet itself opens over its host — the card
            // reads as a panel nested under the dialog, not a flat fill block.
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.background),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
                elevation = androidx.compose.material3.CardDefaults.cardElevation(defaultElevation = 3.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 8.dp, bottom = 4.dp)) {
                    Text(stripMarkdown(summary), style = MaterialTheme.typography.bodyMedium)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(
                            onClick = { clipboard.setText(AnnotatedString(stripMarkdown(summary))) },
                            modifier = Modifier.size(44.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = "Copy summary",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(15.dp),
                            )
                        }
                    }
                }
            }
        }
    } else {
        Row(
            modifier = Modifier
                .padding(top = 12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Summary", style = MaterialTheme.typography.labelLarge)
            OutlinedButton(
                onClick = onSummarize,
                enabled = !isSummarizing,
                shape = com.harken.android.ui.theme.PillShape,
                modifier = Modifier.height(36.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                if (isSummarizing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Summarizing…")
                    }
                } else {
                    Text("Summarize")
                }
            }
        }
    }

    if (summary == null && !isSummarizing && transcriptionStatus != TranscriptionStatus.Succeeded) {
        Text(
            "Summary available once transcription finishes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// Backend summaries come back as Markdown (**bold**, "* " bullets); the summary card
// renders plain Text, so strip the syntax rather than show literal asterisks.
private fun stripMarkdown(text: String): String =
    text
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .lines()
        .joinToString("\n") { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("* ")) "•" + trimmed.removePrefix("*") else line
        }

// Scrollable transcript-only body — the summary lives outside this, pinned below
// the scroll area in SessionDetailModal, so it stays reachable without scrolling.
@Composable
private fun TranscriptSection(detail: SessionDetail) {
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(formatSessionTimestamp(detail.startedAt), style = MaterialTheme.typography.titleLarge)

        Text("Transcript", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 24.dp))

        when (detail.transcriptionStatus) {
            TranscriptionStatus.Pending, TranscriptionStatus.Running -> Text(
                "Transcribing…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            TranscriptionStatus.Failed -> Text(
                "Transcription failed: ${detail.transcriptionFailureReason ?: "unknown error"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
            else -> {}
        }

        Column(modifier = Modifier.padding(top = 8.dp)) {
            detail.segments.forEach { segment ->
                TranscriptRow(segment = segment, onCopy = { clipboard.setText(AnnotatedString(segment.text)) })
            }
        }
    }
}

// Own copy affordance per line — grabbing one quote shouldn't require selecting text
// by hand. 44dp tap target on the icon; the glyph itself stays visually small.
@Composable
private fun TranscriptRow(segment: TranscriptSegmentView, onCopy: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                formatOffset(segment.offset),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(segment.text, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = onCopy, modifier = Modifier.size(44.dp)) {
            Icon(
                imageVector = Icons.Filled.ContentCopy,
                contentDescription = "Copy",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(15.dp),
            )
        }
    }
}
