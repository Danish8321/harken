package com.harken.android.ui

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.harken.android.R
import com.harken.android.data.SearchQuery
import com.harken.android.data.SessionRepository
import com.harken.android.data.TranscriptText
import com.harken.android.displayTitle
import com.harken.android.ui.components.EmptyState
import com.harken.android.ui.components.ErrorState
import com.harken.android.ui.components.SkeletonRow
import com.harken.android.ui.components.rememberStaggerShown
import com.harken.android.ui.theme.HarkenMotion
import com.harken.android.ui.theme.LocalProtoColors
import com.harken.android.ui.theme.PillShape
import com.harken.android.ui.theme.ProtoColors
import java.util.UUID

// Prototype card visuals wired to the real LibraryViewModel — real Room+network session
// list, real refresh, real tag-based filtering. The prototype's fake "pending upload
// queue" and archive button had no backing endpoint and are dropped rather than left
// pretending to work.

private const val STAGGER_CAP = 8
private const val STAGGER_STEP_MS = 35L

@Composable
fun LibraryScreen(
    onOpenSession: (sessionId: UUID, focusSegmentId: UUID?) -> Unit = { _, _ -> },
    onGoToRecord: () -> Unit = {},
    viewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory),
) {
    val c = LocalProtoColors.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val search by viewModel.searchState.collectAsStateWithLifecycle()
    var filter by remember { mutableStateOf(LibraryFilter.All) }

    val visible =
        remember(state.sessions, filter) {
            when (filter) {
                LibraryFilter.All -> state.sessions
                else -> state.sessions.filter { it.tags.any { t -> t.equals(filter.tag, ignoreCase = true) } }
            }
        }
    val longest = remember(state.sessions) { state.sessions.mapNotNull { it.durationSeconds }.maxOrNull() ?: 1 }

    Column(Modifier.fillMaxSize().background(c.screenBg).padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(stringResource(R.string.library_title), color = c.text, style = MaterialTheme.typography.headlineSmall)
        Text(
            if (search.isActive) {
                pluralStringResource(R.plurals.library_search_result_count, search.results.size, search.results.size)
            } else {
                viewModel.subtitle(state, visible)
            },
            color = c.textSecondary,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            modifier = Modifier.padding(top = 2.dp, bottom = 14.dp),
        )

        SearchField(
            c = c,
            query = search.query,
            onQueryChange = viewModel::onSearchQueryChange,
            onClear = viewModel::clearSearch,
        )

        // The tag filters narrow the list behind the search, not the results in front of
        // it — showing both would offer two ways to reduce one list and answer neither
        // question well.
        if (!search.isActive) {
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibraryFilter.entries.forEach { option ->
                    FilterChipProto(c, selected = filter == option, label = option.label) { filter = option }
                }
            }
        }
        Spacer(Modifier.height(14.dp))

        when {
            search.isActive ->
                SearchResults(
                    c = c,
                    term = search.query.trim(),
                    results = search.results,
                    isSearching = search.isSearching,
                    onOpen = onOpenSession,
                )

            state.loadError != null ->
                ErrorState(
                    title = stringResource(R.string.library_load_failed_title),
                    body = stringResource(R.string.library_load_failed_body, state.loadError.orEmpty()),
                )

            state.isLoading && state.sessions.isEmpty() ->
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(4) { SkeletonRow() }
                }

            visible.isEmpty() ->
                EmptyState(
                    icon = Icons.Filled.GraphicEq,
                    title =
                        if (filter == LibraryFilter.All) {
                            stringResource(R.string.library_empty_title)
                        } else {
                            stringResource(R.string.library_empty_filtered_title, stringResource(filter.label))
                        },
                    body =
                        if (filter == LibraryFilter.All) {
                            stringResource(R.string.library_empty_body)
                        } else {
                            stringResource(R.string.library_empty_filtered_body)
                        },
                    actionLabel = if (filter == LibraryFilter.All) stringResource(R.string.library_empty_action) else null,
                    onAction = if (filter == LibraryFilter.All) onGoToRecord else null,
                )

            else -> {
                // Rows fade/slide in staggered by index on genuine list-load/tab-arrival —
                // animatedIds tracks which ones already played so scrolling a row off-screen
                // and back (LazyColumn disposes/recomposes it) doesn't replay the entrance.
                // Capped at STAGGER_CAP rows so a long list doesn't visibly take a beat to
                // finish settling; HarkenMotion collapses to snap() under reduced motion, so
                // only the artificial per-row delay needs its own skip.
                val animatedIds = remember { mutableStateSetOf<UUID>() }
                val reduced = com.harken.android.ui.theme.LocalReducedMotion.current
                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    itemsIndexed(visible, key = { _, it -> it.id }) { index, session ->
                        val shown = rememberStaggerShown(session.id, index, animatedIds, reduced, STAGGER_CAP, STAGGER_STEP_MS)
                        AnimatedVisibility(
                            visible = shown,
                            enter =
                                fadeIn(HarkenMotion.effectsFast()) +
                                    slideInVertically(HarkenMotion.spatialFast()) { it / 6 },
                        ) {
                            SessionCard(
                                c = c,
                                s = session,
                                longestSeconds = longest,
                                sessionCount = visible.size,
                                isTranscribing = session.id == state.transcribingSessionId,
                                onOpen = { onOpenSession(session.id, null) },
                                onTranscribe = { viewModel.transcribe(session) },
                                transcribeEnabled = state.transcribingSessionId == null,
                            )
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

/**
 * The search field.
 *
 * A [BasicTextField] rather than an OutlinedTextField: the Library is a pill-and-card
 * surface and a Material outlined box reads as a form control dropped into it. The pill
 * keeps the same 48dp minimum target the filter chips have.
 */
@Composable
private fun SearchField(
    c: ProtoColors,
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(c.pillTrack, PillShape)
            .padding(start = 16.dp, end = if (query.isEmpty()) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = c.textSecondary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            decorationBox = { field ->
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.library_search_hint),
                        color = c.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                }
                field()
            },
        )
        if (query.isNotEmpty()) {
            IconButton(
                onClick = {
                    onClear()
                    focusManager.clearFocus()
                },
                modifier = Modifier.size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.library_search_clear),
                    tint = c.textSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    c: ProtoColors,
    term: String,
    results: List<SessionRepository.SearchHit>,
    isSearching: Boolean,
    onOpen: (UUID, UUID?) -> Unit,
) {
    when {
        // Only while there is nothing to show. Re-running the query on the next keystroke
        // must not blank a list the user is already reading.
        isSearching && results.isEmpty() ->
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                repeat(3) { SkeletonRow() }
            }

        results.isEmpty() ->
            EmptyState(
                icon = Icons.Filled.Search,
                title = stringResource(R.string.library_search_empty_title),
                body = stringResource(R.string.library_search_empty_body),
            )

        else ->
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(results, key = { it.session.id }) { hit ->
                    SearchResultCard(c = c, hit = hit, term = term) { onOpen(hit.session.id, hit.segmentId) }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
    }
}

/**
 * One result: the recording, the line that matched, and where in the recording it is.
 *
 * Deliberately not a [SessionCard]: a result answers "is this the one I am looking for?",
 * which the matched line answers and a duration bar does not.
 */
@Composable
private fun SearchResultCard(
    c: ProtoColors,
    hit: SessionRepository.SearchHit,
    term: String,
    onOpen: () -> Unit,
) {
    val snippet = remember(hit.snippet, term) { hit.snippet?.let { SearchQuery.snippet(it, term) } }
    val highlighted =
        remember(snippet, c.accent) {
            val window = snippet ?: return@remember null
            buildAnnotatedString {
                append(window.text)
                if (window.hasMatch) {
                    addStyle(
                        SpanStyle(color = c.accent, fontWeight = FontWeight.Bold),
                        window.matchStart,
                        window.matchEnd.coerceAtMost(window.text.length),
                    )
                }
            }
        }

    Column(
        Modifier
            .fillMaxWidth()
            .background(c.card, MaterialTheme.shapes.large)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                hit.session.displayTitle(),
                color = c.text,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            hit.offsetSeconds?.let { at ->
                Text(
                    TranscriptText.timestamp(at),
                    color = c.textSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        if (highlighted != null) {
            Text(
                highlighted,
                color = c.textSecondary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Text(
            if (hit.matchCount > 0) {
                pluralStringResource(R.plurals.library_search_match_count, hit.matchCount, hit.matchCount)
            } else {
                stringResource(R.string.library_search_title_match)
            },
            color = c.textSecondary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun FilterChipProto(
    c: ProtoColors,
    selected: Boolean,
    @StringRes label: Int,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        shape = PillShape,
        label = { Text(stringResource(label), style = MaterialTheme.typography.labelLarge) },
        colors =
            FilterChipDefaults.filterChipColors(
                containerColor = c.pillTrack,
                labelColor = c.textSecondary,
                selectedContainerColor = c.accent,
                selectedLabelColor = c.onAccent,
            ),
        border =
            FilterChipDefaults.filterChipBorder(
                enabled = true,
                selected = selected,
                borderColor = c.cardBorder,
                selectedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                borderWidth = 1.dp,
            ),
    )
}

@Composable
private fun SessionCard(
    c: ProtoColors,
    s: SessionRepository.SessionView,
    longestSeconds: Int,
    sessionCount: Int,
    isTranscribing: Boolean,
    onOpen: () -> Unit,
    onTranscribe: () -> Unit,
    transcribeEnabled: Boolean,
) {
    // isTranscribing (from TranscriptionCoordinator.activeSessionId) is set the instant
    // Transcribe is tapped; s.status flips Recorded -> Pending/Running only once Room's
    // write lands, one coroutine hop later. Without the OR, that gap showed a disabled
    // Transcribe button instead of the Transcribing chip.
    val transcribing = isTranscribing || s.status == "Pending" || s.status == "Running"
    val recorded = s.status == "Recorded" && !isTranscribing
    val failed = s.status == "Failed"
    val (chipBg, chipFg, chipLabel) =
        when {
            transcribing -> Triple(c.stateDone, c.stateDoneFg, R.string.library_chip_transcribing)
            failed -> Triple(c.stateError, c.stateErrorFg, R.string.library_chip_kept_on_device)
            else -> Triple(c.pillTrack, c.textSecondary, R.string.library_chip_transcribed)
        }
    val metaLine =
        buildString {
            append(formatSessionTimestamp(s.startedAt))
            s.durationSeconds?.let { append(" · ${TranscriptText.timestamp(it)}") }
        }
    val barColor = if (transcribing) c.success else c.textSecondary
    val fraction = ((s.durationSeconds ?: 0).toFloat() / longestSeconds).coerceIn(0f, 1f)

    Column(Modifier.fillMaxWidth().background(c.card, MaterialTheme.shapes.large).padding(16.dp)) {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.displayTitle(),
                    color = c.text,
                    // titleMedium (17sp/Bold), not the 15sp/Bold this hand-wrote: the card
                    // title is the primary scan target on this screen and was only one weight
                    // step clear of its own meta line below.
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    metaLine,
                    color = c.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // Why it failed, not just that it can be retried. The reason is recorded on
                // the session by every failure path, and until it was rendered here the user
                // saw a Transcribe button with no account of what went wrong.
                if (failed) {
                    s.failureReason?.let { reason ->
                        Text(
                            reason,
                            // stateError, not stateErrorFg: the foreground pair is for text
                            // sitting on the error fill, and this text sits on the card.
                            color = c.stateError,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            // A failed transcription offers the same action as one never started. Showing
            // only the "kept on device" chip left the recording with no way forward at all,
            // which is what an interrupted transcription looks like after recovery.
            if (recorded || failed) {
                Button(onClick = onTranscribe, enabled = transcribeEnabled, shape = PillShape) {
                    Text(stringResource(R.string.library_action_transcribe))
                }
            } else {
                Row(
                    Modifier.background(chipBg, PillShape).padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (transcribing) {
                        CircularProgressIndicator(modifier = Modifier.size(11.dp), strokeWidth = 1.5.dp, color = chipFg)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(
                        stringResource(chipLabel),
                        color = chipFg,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        val tag = s.tags.firstOrNull()
        // The bar communicates duration RELATIVE TO OTHER ROWS, so with one session in
        // the list (or one filtered into view) it always fills end to end regardless of
        // actual length — a meaningless "always full" bar that reads as illegible/broken
        // rather than as data. Duration is already in metaLine above, so just drop the
        // bar in that case instead of rendering a comparison with nothing to compare to.
        if (sessionCount > 1 || tag != null) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (sessionCount > 1) {
                    // A bar scaled to REAL duration, relative to the longest recording in the
                    // list. Honest data in the space the fake waveform used to fill.
                    //
                    // Deliberately unanimated: LazyColumn discards and recreates this composable's
                    // state every time the row scrolls out of view and back, so an animateFloatAsState
                    // here restarted from 0 on every re-entry, reading as a glitch during a scroll.
                    // The duration itself never changes while the row is visible, so there is
                    // nothing to animate.
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.weight(1f).height(5.dp),
                        color = barColor,
                        trackColor = c.cardBorder,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                tag?.let {
                    Text(
                        it.uppercase(),
                        color = c.textSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        }
    }
}

/**
 * [tag] is the value stored against a session and is deliberately NOT localized —
 * translating it would orphan every tag already on the device. [label] is what the chip
 * shows.
 */
enum class LibraryFilter(
    val tag: String,
    @StringRes val label: Int,
) {
    All("All", R.string.library_filter_all),
    Meetings("Meetings", R.string.library_filter_meetings),
    Field("Field", R.string.library_filter_field),
    Ideas("Ideas", R.string.library_filter_ideas),
}
