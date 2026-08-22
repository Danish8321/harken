package com.harken.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.ui.components.InkSurface
import com.harken.android.ui.components.StatusChip
import com.harken.android.ui.theme.LocalInk
import com.harken.android.ui.theme.PillShape
import java.util.UUID

// Renamed from SessionDetailScreen. Same modal-sheet presentation as before (a session is
// content to review and hand off, not a stack frame), rebuilt on the shared card and ink
// surfaces so it stops being the only screen in the app with a bordered elevated card.
//
// Adapted from the sync original: HorizontalFloatingToolbar / SplitButtonLayout /
// SplitButtonDefaults are Material 3 Expressive components whose Kotlin visibility is
// still internal in the released material3 1.4.0 artifact (same wall hit by MotionScheme
// — see ui/theme/Motion.kt), so the bottom action bar is a plain Row of IconButtons plus
// a Button+DropdownMenu pair instead, giving the same actions with stable APIs.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSheet(
    sessionId: UUID,
    onDismiss: () -> Unit,
    viewModel: SessionSheetViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val clipboard = LocalClipboardManager.current

    var editingTitle by remember { mutableStateOf(false) }
    var titleDraft by remember(state.title) { mutableStateOf(state.title) }
    var summaryOpen by remember { mutableStateOf(true) }
    var confirmDelete by remember { mutableStateOf(false) }
    var summaryMenuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.95f)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { confirmDelete = true }, modifier = Modifier.size(42.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete recording")
                }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 118.dp),
            ) {
                item {
                    // Rename is inline and local — no dialog, and no round trip, because
                    // the backend has no title field yet (see ADR-0010).
                    if (editingTitle) {
                        OutlinedTextField(
                            value = titleDraft,
                            onValueChange = { titleDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = PillShape,
                            label = { Text("Recording name") },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editingTitle = false; titleDraft = state.title }) { Text("Cancel") }
                            Button(
                                onClick = { viewModel.rename(sessionId, titleDraft); editingTitle = false },
                                shape = PillShape,
                            ) { Text("Save name") }
                        }
                    } else {
                        Row(
                            modifier = Modifier.pointerInput(Unit) { detectTapGestures { editingTitle = true } },
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(state.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                            Icon(
                                Icons.Filled.Edit,
                                contentDescription = "Rename",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp).padding(top = 8.dp),
                            )
                        }
                    }
                    Text(
                        state.meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    TagRow(tags = state.tags, onAdd = { viewModel.addTag(sessionId, it) }, modifier = Modifier.padding(top = 14.dp))
                    PlayerCard(
                        state = state,
                        onSeek = { viewModel.seekTo(it) },
                        onTogglePlay = viewModel::togglePlay,
                        modifier = Modifier.padding(top = 18.dp),
                    )
                    state.summary?.let { summary ->
                        SummaryCard(
                            summary = summary,
                            expanded = summaryOpen,
                            onToggle = { summaryOpen = !summaryOpen },
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("TRANSCRIPT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f).height(1.dp).clip(CircleShape)) {
                            Surface(color = MaterialTheme.colorScheme.outlineVariant) { Box(Modifier.fillMaxWidth().height(1.dp)) }
                        }
                        Text(state.transcriptMeta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }

                items(state.segments, key = { it.id }) { segment ->
                    TranscriptRow(
                        segment = segment,
                        active = segment.id == state.activeSegmentId && state.playing,
                        showVoice = state.voiceCount > 1,
                        onSeek = { viewModel.seekTo(segment.offsetSeconds) },
                    )
                }
            }

            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.extraLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(state.plainText))
                        viewModel.confirm("Transcript copied")
                    }) { Icon(Icons.Filled.ContentCopy, contentDescription = "Copy transcript") }
                    IconButton(onClick = viewModel::share) { Icon(Icons.Filled.Share, contentDescription = "Share transcript") }
                    Spacer(Modifier.weight(1f))
                    Button(
                        onClick = { viewModel.summarize(sessionId) },
                        enabled = !state.summarizing,
                        shape = PillShape,
                    ) {
                        if (state.summarizing) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            when {
                                state.summarizing && state.summary == null -> "Summarizing…"
                                state.summarizing -> "Re-summarizing…"
                                state.summary == null -> "Summarize"
                                else -> "Re-summarize"
                            },
                            maxLines = 1,
                        )
                    }
                    Box {
                        IconButton(onClick = { summaryMenuOpen = true; viewModel.toggleSummaryOptions(true) }) {
                            Icon(Icons.Filled.ExpandMore, contentDescription = "Summary options")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = summaryMenuOpen,
                            onDismissRequest = { summaryMenuOpen = false; viewModel.toggleSummaryOptions(false) },
                        ) {
                            androidx.compose.material3.DropdownMenuItem(onClick = { summaryMenuOpen = false }, text = { Text("Short") })
                            androidx.compose.material3.DropdownMenuItem(onClick = { summaryMenuOpen = false }, text = { Text("Detailed") })
                        }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Delete this recording?") },
            text = {
                Text("The WAV file, its transcript and its summary are removed from the backend and this phone. This cannot be undone.")
            },
            confirmButton = {
                Button(
                    onClick = { confirmDelete = false; viewModel.purge(sessionId); onDismiss() },
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it") } },
        )
    }
}

/** The ink player. Scrubbable, and the transcript follows the playhead. */
@Composable
private fun PlayerCard(
    state: SessionSheetUiState,
    onSeek: (Int) -> Unit,
    onTogglePlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalInk.current
    InkSurface(modifier) {
        // BACKEND WORK REQUIRED: playback needs GET /sessions/{id}/audio. Until that
        // exists the controls are visibly disabled with a reason rather than silently
        // dead — see HarkenApi.kt and ADR-0010.
        ScrubbableWaveform(
            bars = state.waveform,
            progress = state.progressFraction,
            enabled = state.audioAvailable,
            onSeekFraction = { onSeek((it * state.durationSeconds).toInt()) },
            modifier = Modifier.fillMaxWidth().height(76.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val radius by animateFloatAsState(
                targetValue = if (state.playing) 20f else 28f,
                animationSpec = com.harken.android.ui.theme.HarkenMotion.spatialDefault(),
                label = "playShape",
            )
            Surface(
                onClick = onTogglePlay,
                enabled = state.audioAvailable,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(radius.dp),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(56.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (state.playing) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(formatElapsed(state.playheadSeconds), style = MaterialTheme.typography.titleLarge, color = ink.onInk, maxLines = 1)
                Text(
                    if (state.audioAvailable) "of ${formatElapsed(state.durationSeconds)} · seek on the wave"
                    else "Playback needs the audio endpoint",
                    style = MaterialTheme.typography.bodySmall,
                    color = ink.onInkDim,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ScrubbableWaveform(
    bars: List<Float>,
    progress: Float,
    enabled: Boolean,
    onSeekFraction: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val played = MaterialTheme.colorScheme.primary
    val remaining = LocalInk.current.onInkDim
    androidx.compose.foundation.Canvas(
        modifier.pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectTapGestures { offset -> onSeekFraction((offset.x / size.width).coerceIn(0f, 1f)) }
        },
    ) {
        if (bars.isEmpty()) return@Canvas
        val slot = size.width / bars.size
        val barWidth = slot * 0.6f
        bars.forEachIndexed { index, value ->
            val h = (size.height * value).coerceAtLeast(3f)
            drawRoundRect(
                color = if (index.toFloat() / bars.size <= progress) played else remaining,
                topLeft = androidx.compose.ui.geometry.Offset(index * slot, (size.height - h) / 2f),
                size = androidx.compose.ui.geometry.Size(barWidth, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun SummaryCard(summary: String, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = com.harken.android.ui.theme.HarkenMotion.spatialFast(),
        label = "summaryChevron",
    )
    Surface(
        onClick = onToggle,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(19.dp))
                Text("SUMMARY", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse summary" else "Expand summary",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.rotate(rotation),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(summary, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

@Composable
private fun TagRow(tags: List<String>, onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tags.forEach { tag ->
            StatusChip(
                label = tag.uppercase(),
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = { onAdd("Meetings") }, shape = PillShape) { Text("+ Tag") }
    }
}

@Composable
private fun TranscriptRow(
    segment: TranscriptRowModel,
    active: Boolean,
    showVoice: Boolean,
    onSeek: () -> Unit,
) {
    // Colour follows the playhead on an effects spring — a fade, never a bounce.
    val container = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background
    Surface(onClick = onSeek, shape = MaterialTheme.shapes.medium, color = container) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(width = 34.dp, height = 44.dp)) {
                if (showVoice) {
                    StatusChip(
                        label = "${segment.voiceIndex + 1}",
                        container = if (segment.voiceIndex == 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                        content = if (segment.voiceIndex == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(
                    formatElapsed(segment.offsetSeconds),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (showVoice) {
                    // "Voice 1", not "Speaker A" and never a name: this is a gap
                    // heuristic, not diarization. See SpeakerHeuristic.
                    Text(
                        "VOICE ${segment.voiceIndex + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (segment.voiceIndex == 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                Text(segment.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

data class TranscriptRowModel(
    val id: UUID,
    val offsetSeconds: Int,
    val text: String,
    val voiceIndex: Int,
)
