package com.harken.android.ui

import android.util.Log
import com.harken.android.data.SearchQuery
import com.harken.android.data.SessionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

private const val TAG = "LibrarySearch"

/**
 * One run of the Library's search box: wait out the typing, ask [search], publish what came
 * back into [state].
 *
 * Kept out of [LibraryViewModel] for the reason [PlaybackCursor] is — an `AndroidViewModel`
 * built from the app's container is not constructible on the JVM — and because what this has
 * to get right is what it does when it is *cancelled*. Every keystroke cancels the run before
 * it, so cancellation is the ordinary end of a run here, not the exceptional one (ARC-061).
 *
 * A term shorter than [SearchQuery.MIN_LENGTH] is not a query; it clears whatever the last
 * one found and returns.
 */
suspend fun runLibrarySearch(
    state: MutableStateFlow<LibrarySearchState>,
    query: String,
    debounceMs: Long,
    search: suspend (String) -> List<SessionRepository.SearchHit>,
) {
    if (query.trim().length < SearchQuery.MIN_LENGTH) {
        state.update { it.copy(results = emptyList(), isSearching = false) }
        return
    }
    delay(debounceMs)
    state.update { it.copy(isSearching = true) }

    // Not runCatching: it catches Throwable, so the cancellation the next keystroke raises
    // was read as "the search failed" — logged as an error and published as no results, which
    // put a confident "No matches" over the hits that were on screen until the new query came
    // back (ARC-061). A cancelled run must publish nothing at all.
    val hits =
        try {
            search(query)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            emptyList()
        }
    state.update { it.copy(results = hits, isSearching = false) }
}
