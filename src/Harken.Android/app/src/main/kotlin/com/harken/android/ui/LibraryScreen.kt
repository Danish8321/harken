package com.harken.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.data.SessionRepository
import com.harken.android.ui.components.EmptyState
import com.harken.android.ui.components.HarkenCard
import com.harken.android.ui.components.SkeletonRow
import com.harken.android.ui.components.StatusChip
import java.util.UUID

// Renamed from RecordingListScreen. The row is the biggest change in the redesign: the
// hash-seeded fake waveform is gone (it was pure noise wearing the costume of
// information) and the space it occupied now carries a title, a duration bar scaled to
// real length, tags and live transcription progress.
//
// Adapted from the sync original: ButtonGroup/clickableItem and LoadingIndicator are
// Material 3 Expressive components whose Kotlin visibility is still internal in the
// released material3 1.4.0 artifact (same wall as MotionScheme — see
// ui/theme/Motion.kt), so the filter row is a LazyRow of FilterChips and the
// transcribing indicator is a CircularProgressIndicator instead.

@Composable
fun LibraryScreen(
    onOpenSession: (UUID) -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var filter by remember { mutableStateOf(LibraryFilter.All) }

    val visible = remember(state.sessions, filter) {
        when (filter) {
            LibraryFilter.All -> state.sessions
            else -> state.sessions.filter { it.tags.any { t -> t.equals(filter.label, ignoreCase = true) } }
        }
    }
    val longest = remember(state.sessions) { state.sessions.mapNotNull { it.durationSeconds }.maxOrNull() ?: 1 }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
            Text("Library", style = MaterialTheme.typography.displaySmall)
            Text(
                text = viewModel.subtitle(state),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }

        Spacer(Modifier.height(16.dp))

        LazyRow(
            modifier = Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(LibraryFilter.entries.toList()) { option ->
                val selected = filter == option
                FilterChip(
                    selected = selected,
                    onClick = { filter = option },
                    label = { Text(option.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                )
            }
        }

        Spacer(Modifier.height(14.dp))

        when {
            state.isLoading && state.sessions.isEmpty() -> Column(
                Modifier.padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { repeat(4) { SkeletonRow() } }

            visible.isEmpty() -> EmptyState(
                icon = Icons.Filled.GraphicEq,
                title = if (filter == LibraryFilter.All) "Nothing recorded yet" else "Nothing tagged ${filter.label}",
                body = if (filter == LibraryFilter.All) {
                    "Recordings appear here the moment an upload lands. The phone keeps the file either way, so nothing is lost if the backend is asleep."
                } else {
                    "Tags are local to this phone. Open a recording to add one."
                },
                actionLabel = if (filter == LibraryFilter.All) "Record something" else null,
                onAction = if (filter == LibraryFilter.All) viewModel::goToRecord else null,
                modifier = Modifier.padding(horizontal = 20.dp),
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(visible, key = { it.id }) { session ->
                    SessionRowCard(
                        session = session,
                        longestSeconds = longest,
                        onOpen = { onOpenSession(session.id) },
                        onTranscribe = { viewModel.transcribe(session) },
                        transcribeEnabled = state.transcribingSessionId == null,
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

enum class LibraryFilter(val label: String) { All("All"), Meetings("Meetings"), Field("Field"), Ideas("Ideas") }

@Composable
private fun SessionRowCard(
    session: SessionRepository.SessionView,
    longestSeconds: Int,
    onOpen: () -> Unit,
    onTranscribe: () -> Unit,
    transcribeEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val transcribing = session.status == "Pending" || session.status == "Running"
    val recorded = session.status == "Recorded"
    val failed = session.status == "Failed"

    HarkenCard(modifier = modifier.fillMaxWidth(), onClick = onOpen) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = rowMeta(session),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            when {
                transcribing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.secondary)
                    Text("Transcribing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 1)
                }

                // Recorded but not yet transcribed — the user decides when, and only one
                // session transcribes at a time app-wide (see TranscriptionCoordinator).
                recorded -> Button(onClick = onTranscribe, enabled = transcribeEnabled) {
                    Text("Transcribe")
                }

                failed -> StatusChip(
                    label = "Kept on device",
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                )

                session.hasSummary -> StatusChip(
                    label = "Summarized",
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    leading = { Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer) },
                )

                else -> StatusChip(
                    label = "Transcribed",
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                    leading = { Icon(Icons.Filled.Notes, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // A bar scaled to REAL duration, relative to the longest recording in the
            // list. Honest data in the space the fake waveform used to fill.
            //
            // Deliberately unanimated: LazyColumn discards and recreates this composable's
            // state every time the row scrolls out of view and back, so an animateFloatAsState
            // here restarted from 0 on every re-entry, reading as a glitch during a scroll.
            // The duration itself never changes while the row is visible, so there is
            // nothing to animate.
            val fraction = ((session.durationSeconds ?: 0).toFloat() / longestSeconds).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.weight(1f).height(6.dp),
                color = if (transcribing) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outline,
                trackColor = MaterialTheme.colorScheme.outlineVariant,
            )
            session.tags.firstOrNull()?.let { tag ->
                StatusChip(
                    label = tag.uppercase(),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun rowMeta(session: SessionRepository.SessionView): String = buildString {
    append(formatSessionTimestamp(session.startedAt))
    session.durationSeconds?.let {
        append(" · ")
        append("${it / 60}m ${(it % 60).toString().padStart(2, '0')}s")
    }
}
