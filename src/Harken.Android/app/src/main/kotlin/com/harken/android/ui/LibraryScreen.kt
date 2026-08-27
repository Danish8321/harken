package com.harken.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.data.SessionRepository
import com.harken.android.ui.components.EmptyState
import com.harken.android.ui.components.ErrorState
import com.harken.android.ui.components.SkeletonRow
import com.harken.android.ui.theme.ProtoBodyFont
import com.harken.android.ui.theme.ProtoColors
import com.harken.android.ui.theme.ProtoHeadingFont
import com.harken.android.ui.theme.rememberProtoColors
import java.util.UUID

// Prototype card visuals wired to the real LibraryViewModel — real Room+network session
// list, real refresh, real tag-based filtering. The prototype's fake "pending upload
// queue" and archive button had no backing endpoint and are dropped rather than left
// pretending to work.

@Composable
fun LibraryScreen(
    onOpenSession: (UUID) -> Unit = {},
    viewModel: LibraryViewModel = viewModel(),
) {
    val c = rememberProtoColors()
    val state by viewModel.uiState.collectAsState()
    var filter by remember { mutableStateOf(LibraryFilter.All) }

    LaunchedEffect(Unit) { viewModel.refresh() }

    val visible = remember(state.sessions, filter) {
        when (filter) {
            LibraryFilter.All -> state.sessions
            else -> state.sessions.filter { it.tags.any { t -> t.equals(filter.label, ignoreCase = true) } }
        }
    }
    val longest = remember(state.sessions) { state.sessions.mapNotNull { it.durationSeconds }.maxOrNull() ?: 1 }

    Column(Modifier.fillMaxSize().background(c.screenBg).padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text("Library", color = c.text, fontFamily = ProtoHeadingFont, fontSize = 26.sp)
        Text(
            viewModel.subtitle(state),
            color = c.textSecondary,
            fontFamily = ProtoBodyFont,
            fontSize = 13.5.sp,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
        )

        Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryFilter.entries.forEach { option ->
                FilterChipProto(c, selected = filter == option, label = option.label) { filter = option }
            }
        }

        when {
            state.error != null && state.sessions.isEmpty() -> ErrorState(
                title = "Backend unreachable",
                body = "Nothing answered at ${state.baseUrl}. Check the machine is awake and on this Wi-Fi — recordings keep saving locally meanwhile.",
                onRetry = viewModel::refresh,
                secondaryLabel = "Change address",
                onSecondary = viewModel::openSettings,
            )

            state.isLoading && state.sessions.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(4) { SkeletonRow() }
            }

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
            )

            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(visible, key = { it.id }) { session ->
                    SessionCard(c, session, longest) { onOpenSession(session.id) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun FilterChipProto(c: ProtoColors, selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 13.sp) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = c.pillTrack,
            labelColor = c.textSecondary,
            selectedContainerColor = c.accentFill,
            selectedLabelColor = c.accentFillFg,
        ),
        border = null,
    )
}

@Composable
private fun SessionCard(
    c: ProtoColors,
    s: SessionRepository.SessionView,
    longestSeconds: Int,
    onOpen: () -> Unit,
) {
    val transcribing = s.status == "Pending" || s.status == "Running"
    val failed = s.status == "Failed"
    val (chipBg, chipFg, chipLabel) = when {
        transcribing -> Triple(c.accentFill2, c.accentFill2Fg, "Transcribing")
        failed -> Triple(c.dangerFill, c.dangerFillFg, "Kept on device")
        s.hasSummary -> Triple(c.accentFill2, c.accentFill2Fg, "Summarized")
        else -> Triple(c.pillTrack, c.textSecondary, "Transcribed")
    }
    val metaLine = buildString {
        append(formatSessionTimestamp(s.startedAt))
        s.durationSeconds?.let { append(" · ${it / 60}m ${(it % 60).toString().padStart(2, '0')}s") }
    }
    val barColor = if (transcribing) Color(0xFFAEBF92) else Color(0xFF82796A)
    val fraction = ((s.durationSeconds ?: 0).toFloat() / longestSeconds).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth().background(c.card, RoundedCornerShape(24.dp)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(s.title, color = c.text, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(metaLine, color = c.textSecondary, fontFamily = ProtoBodyFont, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            Row(
                Modifier.background(chipBg, RoundedCornerShape(999.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (transcribing) {
                    CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = chipFg)
                    Spacer(Modifier.width(4.dp))
                }
                Text(chipLabel, color = chipFg, fontFamily = ProtoBodyFont, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(5.dp),
            color = barColor,
            trackColor = c.cardBorder,
        )
    }
}

enum class LibraryFilter(val label: String) { All("All"), Meetings("Meetings"), Field("Field"), Ideas("Ideas") }
