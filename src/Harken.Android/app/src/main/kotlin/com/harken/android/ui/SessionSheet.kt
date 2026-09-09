package com.harken.android.ui

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.R
import com.harken.android.ui.components.HarkenErrorDialog
import com.harken.android.ui.components.InkSurface
import com.harken.android.ui.components.StatusChip
import com.harken.android.ui.theme.HarkenMotion
import com.harken.android.ui.theme.LocalInk
import com.harken.android.ui.theme.PillShape
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

private const val TRANSCRIPT_STAGGER_CAP = 10
private const val TRANSCRIPT_STAGGER_STEP_MS = 30L

// Not full height: the strip of the screen still showing through is what says the recording
// behind is still there and this is a layer over it, not a new place. It is also the only part
// of the scrim a thumb can reach, so it is measured down from the status bar rather than as a
// fraction of the screen — 5% of a 2412px phone is 51dp, entirely inside a 54dp status bar and
// cutout, which left the scrim visible but untappable (UI-043).
private val SHEET_TOP_GAP = 28.dp
private const val SCRIM_ALPHA = 0.45f
private const val DRAG_HANDLE_ALPHA = 0.4f

// How far the back gesture shrinks the surface at full pull, and what it takes to commit a
// drag: a short flick or a deliberate pull past the threshold, either one.
private const val BACK_PULL_SHRINK = 0.1f
private const val DISMISS_VELOCITY = 1200f
private val DISMISS_DRAG = 120.dp

/**
 * The container-transform key a Library card and this sheet have to agree on, in one place so
 * they cannot drift (UI-043).
 */
internal fun sessionSharedKey(id: UUID): Any = "session-$id"

// Renamed from SessionDetailScreen. Same modal-sheet presentation as before (a session is
// content to review and hand off, not a stack frame), rebuilt on the shared card and ink
// surfaces so it stops being the only screen in the app with a bordered elevated card.
//
// Adapted from the sync original: HorizontalFloatingToolbar / SplitButtonLayout /
// SplitButtonDefaults are Material 3 Expressive components whose Kotlin visibility is
// still internal in the released material3 1.4.0 artifact (same wall hit by MotionScheme
// — see ui/theme/Motion.kt), so the bottom action bar is a plain Row of IconButtons plus
// a Button+DropdownMenu pair instead, giving the same actions with stable APIs.

@Composable
fun SessionSheet(
    sessionId: UUID,
    onDismiss: () -> Unit,
    /** A transcript line to open at, from a search result. Null opens at the top. */
    focusSegmentId: UUID? = null,
    /** Non-null when a Library card is on screen for this session and can hand its bounds
     *  over: the sheet then grows out of that card instead of sliding up. */
    sharedScope: SharedTransitionScope? = null,
    viewModel: SessionSheetViewModel = viewModel(factory = SessionSheetViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    var editingTitle by remember { mutableStateOf(false) }
    // Seeded from the *local* title only. Seeding it from state.title put the derived
    // name ("Afternoon recording") in the box, so saving without editing froze that
    // wording as a real title and left no way back to a derived one. Empty now means
    // "no local name", which is exactly what Save writes.
    var titleDraft by remember(state.title, state.hasLocalTitle) {
        mutableStateOf(if (state.hasLocalTitle) state.title else "")
    }
    val titleFocus = remember { FocusRequester() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) { viewModel.load(sessionId) }

    // The transcript loads after the sheet opens, so the scroll cannot be done when the
    // sheet is built — it waits for the segment to actually exist in the list. The +1 is
    // the header item above the segments.
    val transcriptState = rememberLazyListState()
    LaunchedEffect(focusSegmentId, state.segments) {
        val target = focusSegmentId ?: return@LaunchedEffect
        val index = state.segments.indexOfFirst { it.id == target }
        if (index >= 0) transcriptState.animateScrollToItem(index + 1)
    }

    // Audio must not outlive the sheet it was started from. The ViewModel is remembered
    // across sheet openings, so releasing here rather than only in onCleared is what
    // actually stops a recording playing on into the Library.
    DisposableEffect(sessionId) { onDispose { viewModel.stopPlayback() } }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(state.toast) {
        val message = state.toast ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.toastShown()
    }

    // The sheet shows and hides itself so both halves can be bound to HarkenMotion — see
    // [SheetSurface] for why ModalBottomSheet could not be. onDismiss is the caller REMOVING
    // this composable, so it must not fire when the hide starts, only once the exit has
    // actually finished: the transition settling idle on `false` is that signal. `opened`
    // covers the one frame before targetState is first set, where idle-on-false is the
    // starting state rather than a completed exit.
    val sheet = remember { MutableTransitionState(false) }
    var opened by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        sheet.targetState = true
        opened = true
    }
    LaunchedEffect(opened, sheet.targetState, sheet.currentState, sheet.isIdle) {
        if (opened && !sheet.targetState && !sheet.currentState && sheet.isIdle) onDismiss()
    }
    val hide = { sheet.targetState = false }

    SheetSurface(visible = sheet, onHide = hide, sharedScope = sharedScope, sharedKey = sessionSharedKey(sessionId)) {
        Column(Modifier.fillMaxSize()) {
            val revealedSegmentIds = remember { androidx.compose.runtime.mutableStateSetOf<java.util.UUID>() }
            val reducedMotion = com.harken.android.ui.theme.LocalReducedMotion.current
            // Stretch overscroll and nested scroll are left alone now: the workaround
            // that used to sit here swallowed the transcript's leftover drag so it could
            // not reach ModalBottomSheet's own drag handling and wobble the sheet at the
            // list's top and bottom edge. Nothing above this list drags any more — the
            // handle does, and it is not in the scroll path.
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = transcriptState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding =
                        androidx.compose.foundation.layout
                            .PaddingValues(start = 20.dp, end = 20.dp, bottom = 118.dp),
                ) {
                    item {
                        // Rename is inline and local — no dialog, and no round trip, because
                        // the backend has no title field yet (see ADR-0010).
                        if (editingTitle) {
                            // Opened by a deliberate tap on the title, so it takes the caret
                            // and the keyboard itself — it used to appear unfocused, needing a
                            // second tap before anything could be typed.
                            LaunchedEffect(Unit) { titleFocus.requestFocus() }
                            OutlinedTextField(
                                value = titleDraft,
                                onValueChange = { titleDraft = it },
                                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                                singleLine = true,
                                shape = PillShape,
                                label = { Text(stringResource(R.string.session_name_label)) },
                                placeholder = { Text(state.title) },
                            )
                            Row(
                                modifier = Modifier.padding(top = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(onClick = {
                                    editingTitle = false
                                    titleDraft = if (state.hasLocalTitle) state.title else ""
                                }) { Text(stringResource(R.string.session_cancel)) }
                                Button(
                                    onClick = {
                                        viewModel.rename(sessionId, titleDraft)
                                        editingTitle = false
                                    },
                                    shape = PillShape,
                                ) { Text(stringResource(R.string.session_save_name)) }
                            }
                        } else {
                            Text(
                                state.title,
                                style = MaterialTheme.typography.headlineMedium,
                                modifier =
                                    Modifier.fillMaxWidth().pointerInput(Unit) {
                                        detectTapGestures { editingTitle = true }
                                    },
                            )
                        }
                        Text(
                            state.meta,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        TagRow(
                            tags = state.tags,
                            onAdd = { viewModel.addTag(sessionId, it) },
                            onRemove = { viewModel.removeTag(sessionId, it) },
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        PlaybackCard(
                            audioPath = state.audioPath,
                            isPlaying = state.isPlaying,
                            positionMs = state.positionMs,
                            durationMs = state.playbackDurationMs,
                            onToggle = viewModel::togglePlayback,
                            onSeek = viewModel::seekTo,
                            modifier = Modifier.padding(top = 18.dp),
                        )
                        Row(
                            Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                stringResource(R.string.session_transcript_header),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Box(Modifier.weight(1f).height(1.dp).clip(CircleShape)) {
                                Surface(
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                ) { Box(Modifier.fillMaxWidth().height(1.dp)) }
                            }
                            Text(
                                state.transcriptMeta,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }

                    // A transcribed recording with no segments is a real outcome now that pure
                    // silence is skipped instead of decoded: say so, rather than showing the
                    // transcript header over nothing at all.
                    if (state.status == "Succeeded" && state.segments.isEmpty()) {
                        item {
                            Text(
                                stringResource(R.string.session_transcript_silent),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                    }

                    val activeSegment =
                        PlaybackCursor.activeSegment(
                            state.segments.map { it.offsetSeconds },
                            state.positionMs,
                        )

                    itemsIndexed(state.segments, key = { _, it -> it.id }) { index, segment ->
                        val shown =
                            com.harken.android.ui.components.rememberStaggerShown(
                                segment.id,
                                index,
                                revealedSegmentIds,
                                reducedMotion,
                                TRANSCRIPT_STAGGER_CAP,
                                TRANSCRIPT_STAGGER_STEP_MS,
                            )
                        androidx.compose.animation.AnimatedVisibility(
                            visible = shown,
                            enter =
                                fadeIn(
                                    HarkenMotion.effectsFast(),
                                ) +
                                    slideInVertically(
                                        HarkenMotion.spatialFast(),
                                    ) { it / 8 },
                        ) {
                            TranscriptRow(
                                segment = segment,
                                showVoice = state.voiceCount > 1,
                                isFocused = segment.id == focusSegmentId,
                                isPlaying = state.isPlaying && activeSegment == index,
                                onPlayFromHere = {
                                    viewModel.seekToSegment(segment.offsetSeconds)
                                    if (!state.isPlaying) viewModel.togglePlayback()
                                },
                            )
                        }
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(state.plainText))
                        viewModel.confirm(R.string.session_toast_copied)
                    }) { Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.session_copy_transcript)) }
                    IconButton(onClick = viewModel::shareTranscript) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.session_share_transcript))
                    }
                    // The recording itself, not just what was said in it. Disabled rather
                    // than hidden when the WAV is gone, so the action does not appear and
                    // disappear between two recordings that otherwise look the same.
                    IconButton(onClick = viewModel::shareAudio, enabled = state.audioPath != null) {
                        Icon(Icons.Filled.AudioFile, contentDescription = stringResource(R.string.session_share_audio))
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { editingTitle = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.session_rename))
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.session_delete),
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(R.string.session_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.session_delete_confirm_body))
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmDelete = false
                        // The recording is gone and the sheet is leaving with it, so the
                        // screen cannot report this afterwards — Confirm, as RecordScreen
                        // uses when a save lands (UI-042).
                        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                        viewModel.purge(sessionId)
                        hide()
                    },
                    shape = PillShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.session_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.session_delete_keep)) } },
        )
    }

    state.loadError?.let { message ->
        HarkenErrorDialog(
            title = stringResource(R.string.session_load_failed_title),
            body = stringResource(R.string.session_load_failed_body, message),
            onDismiss = hide,
        )
    }
}

/**
 * Play/pause and a scrubber over the session's own WAV.
 *
 * The recording never leaves the phone (ADR-0011), so there is nothing to stream and no
 * buffering state to show — the position bar is the whole of the transport. A recording
 * whose audio has gone (deleted underneath us, or a row that outlived its file) says so
 * rather than offering a button that cannot work.
 */
@Composable
private fun PlaybackCard(
    audioPath: String?,
    isPlaying: Boolean,
    positionMs: Int,
    durationMs: Int,
    onToggle: () -> Unit,
    onSeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalInk.current
    InkSurface(modifier) {
        if (audioPath == null) {
            Text(
                stringResource(R.string.session_audio_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = ink.onInkDim,
            )
            return@InkSurface
        }

        // While a finger is on the scrubber the bar follows the finger, not the playhead:
        // seeking on every drag pixel stutters the decoder and fights the ticker.
        var scrubbing by remember { mutableStateOf<Float?>(null) }
        val scrubLabel = stringResource(R.string.session_scrub)
        val progress = scrubbing ?: PlaybackCursor.progress(positionMs, durationMs)
        val shownMs = scrubbing?.let { PlaybackCursor.seekTarget(it, durationMs) } ?: positionMs

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IconButton(onClick = onToggle) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.session_pause else R.string.session_play),
                    tint = ink.onInk,
                )
            }
            Text(
                PlaybackCursor.formatClock(shownMs),
                style = MaterialTheme.typography.labelMedium,
                color = ink.onInkDim,
            )
            Slider(
                value = progress,
                onValueChange = { scrubbing = it },
                onValueChangeFinished = {
                    scrubbing?.let { onSeek(PlaybackCursor.seekTarget(it, durationMs)) }
                    scrubbing = null
                },
                colors =
                    SliderDefaults.colors(
                        thumbColor = ink.onInk,
                        activeTrackColor = ink.onInk,
                        inactiveTrackColor = ink.onInkDim,
                    ),
                modifier = Modifier.weight(1f).semantics { contentDescription = scrubLabel },
            )
            Text(
                PlaybackCursor.formatClock(durationMs),
                style = MaterialTheme.typography.labelMedium,
                color = ink.onInkDim,
            )
        }
    }
}

/**
 * The session's tags, and the only place they can be changed.
 *
 * The vocabulary is [LibraryFilter]'s, because a tag that no filter names is a tag the
 * user can never search by. "+ Tag" used to be wired straight to `onAdd("Meetings")` — a
 * stub that made the Field and Ideas filters unreachable and gave a mis-tagged recording
 * no way back.
 */
@Composable
private fun TagRow(
    tags: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var picking by remember { mutableStateOf(false) }
    val available = LibraryFilter.entries.filter { it != LibraryFilter.All && it.tag !in tags }

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        tags.forEach { tag ->
            val removeLabel = stringResource(R.string.session_remove_tag, tag)
            Surface(
                shape = PillShape,
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClickLabel = removeLabel) { onRemove(tag) }.semantics { contentDescription = removeLabel },
            ) {
                Row(
                    Modifier.padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tag.uppercase(), style = MaterialTheme.typography.labelMedium)
                    Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                }
            }
        }
        if (available.isNotEmpty()) {
            Box {
                TextButton(onClick = { picking = true }, shape = PillShape) { Text(stringResource(R.string.session_add_tag)) }
                DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                    available.forEach { filter ->
                        DropdownMenuItem(
                            text = { Text(stringResource(filter.label)) },
                            onClick = {
                                picking = false
                                onAdd(filter.tag)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranscriptRow(
    segment: TranscriptRowModel,
    showVoice: Boolean,
    isPlaying: Boolean,
    onPlayFromHere: () -> Unit,
    /** The line a search result opened this transcript at. Outlined, not filled: the fill
     *  is what "playing" means here, and the two states can be true at once. */
    isFocused: Boolean = false,
) {
    // Tapping a line plays from it: the transcript is how you navigate a recording, and
    // the offset each line already carries is exactly the seek target.
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (isPlaying) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.background,
        border = if (isFocused) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.clickable(role = Role.Button, onClick = onPlayFromHere),
    ) {
        Row(Modifier.padding(horizontal = 15.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.size(width = 34.dp, height = 44.dp)) {
                if (showVoice) {
                    StatusChip(
                        label = "${segment.voiceIndex + 1}",
                        container =
                            if (segment.voiceIndex == 0) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else {
                                MaterialTheme.colorScheme.secondaryContainer
                            },
                        content =
                            if (segment.voiceIndex == 0) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
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
                        stringResource(R.string.session_voice, segment.voiceIndex + 1),
                        style = MaterialTheme.typography.labelSmall,
                        // The ink each voice reads in *on the card*, not the ink it reads
                        // in on its own chip. onPrimaryContainer is ProtoColors.onAccent —
                        // near-white in the light theme — so voice 1's label was white on
                        // white and showed as a ghost behind the segment text (UI-036).
                        // The chip above keeps its container/content pair: that one really
                        // is text on the accent.
                        color =
                            if (segment.voiceIndex == 0) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
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

/**
 * The sheet's own presentation: scrim, bottom-anchored surface, and the two ways out.
 *
 * This is hand-built because ModalBottomSheet could not carry this app's motion (UI-043).
 * Its show and hide run on `SheetDefaultsKt.BottomSheetAnimationSpec`, a private top-level
 * TweenSpec, and neither ModalBottomSheet nor rememberModalBottomSheetState takes an
 * animationSpec — so the largest surface in the app was the one place HarkenMotion could not
 * reach, even though spatialSlow's own doc names "the session sheet" as what it is for. It
 * also composed into a separate Dialog window (`ModalBottomSheetDialog`), which is why no
 * shared-element transition could ever have spanned a Library card into it.
 *
 * What that window gave for free and is re-established here: a scrim that dismisses, a back
 * gesture that follows the finger, a drag handle, and system-bar insets. Focus containment is
 * the one thing it cannot re-create from inside the composition, so AppNav hides what is
 * behind this from accessibility for as long as it is open.
 */
@Composable
private fun SheetSurface(
    visible: MutableTransitionState<Boolean>,
    onHide: () -> Unit,
    sharedScope: SharedTransitionScope?,
    sharedKey: Any,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val dragOffset = remember { Animatable(0f) }
    var backPull by remember { mutableFloatStateOf(0f) }
    val settleBack = HarkenMotion.spatialDefault<Float>()
    val dismissAt = with(LocalDensity.current) { DISMISS_DRAG.toPx() }
    val closeLabel = stringResource(R.string.session_close)
    val boundsSpec = HarkenMotion.spatialSlow<Rect>()
    val contentFadeIn = fadeIn(HarkenMotion.effectsSlow())
    val contentFadeOut = fadeOut(HarkenMotion.effectsSlow())

    // Back follows the finger instead of snapping: progress shrinks the surface toward its own
    // bottom edge, and the flow completing is the gesture being committed. A CancellationException
    // is the user changing their mind mid-gesture, so that path only unwinds the pull.
    PredictiveBackHandler(enabled = visible.targetState) { progress ->
        try {
            progress.collect { backPull = it.progress }
            backPull = 0f
            onHide()
        } catch (cancelled: CancellationException) {
            backPull = 0f
        }
    }

    // Nothing at this level: the scrim and the surface want different halves of the motion
    // vocabulary, so each animates itself. The scrim only recolours, so it takes an effects
    // spec — springing an alpha reads as a rendering glitch (Motion.kt). The surface moves, so
    // it takes spatialSlow, the token whose own doc names this surface.
    AnimatedVisibility(
        visibleState = visible,
        enter = EnterTransition.None,
        exit = ExitTransition.None,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .animateEnterExit(enter = contentFadeIn, exit = contentFadeOut)
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                    // No indication: a ripple spreading across the whole screen reads as a bug,
                    // not as feedback. The click label is what carries the action to TalkBack.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = closeLabel,
                        role = Role.Button,
                        onClick = onHide,
                    ),
            )

            Surface(
                color = MaterialTheme.colorScheme.background,
                // Top corners only, derived from the extraLarge token rather than written as a
                // fresh literal: UI-042 removed the parallel hardcoded shape system.
                shape =
                    MaterialTheme.shapes.extraLarge.copy(
                        bottomStart = CornerSize(0.dp),
                        bottomEnd = CornerSize(0.dp),
                    ),
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(top = SHEET_TOP_GAP)
                        .fillMaxHeight()
                        .then(
                            if (sharedScope == null) {
                                // No card to grow out of — opened from Record, or from a search
                                // result whose own card is not on screen. Slide up instead.
                                Modifier.animateEnterExit(
                                    enter = slideInVertically(HarkenMotion.spatialSlow()) { it },
                                    exit = slideOutVertically(HarkenMotion.spatialSlow()) { it },
                                )
                            } else {
                                with(sharedScope) {
                                    Modifier.sharedBounds(
                                        rememberSharedContentState(sharedKey),
                                        animatedVisibilityScope = this@AnimatedVisibility,
                                        enter = contentFadeIn,
                                        exit = contentFadeOut,
                                        boundsTransform = BoundsTransform { _, _ -> boundsSpec },
                                    )
                                }
                            },
                        ).offset { IntOffset(0, dragOffset.value.roundToInt()) }
                        .graphicsLayer {
                            val shrink = 1f - BACK_PULL_SHRINK * backPull
                            scaleX = shrink
                            scaleY = shrink
                            transformOrigin = TransformOrigin(0.5f, 1f)
                        },
            ) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .draggable(
                                state =
                                    rememberDraggableState { delta ->
                                        // Downward only. Dragging up would fight the fixed height
                                        // rather than expand anything.
                                        scope.launch {
                                            dragOffset.snapTo((dragOffset.value + delta).coerceAtLeast(0f))
                                        }
                                    },
                                orientation = Orientation.Vertical,
                                onDragStopped = { velocity ->
                                    if (dragOffset.value > dismissAt || velocity > DISMISS_VELOCITY) {
                                        onHide()
                                    } else {
                                        dragOffset.animateTo(0f, settleBack)
                                    }
                                },
                            )
                            // The handle is also a button, because a drag is not an action
                            // TalkBack can offer.
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClickLabel = closeLabel,
                                role = Role.Button,
                                onClick = onHide,
                            ).padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(width = 32.dp, height = 4.dp)
                                .background(
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DRAG_HANDLE_ALPHA),
                                    PillShape,
                                ),
                        )
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), content = content)
                }
            }
        }
    }
}
