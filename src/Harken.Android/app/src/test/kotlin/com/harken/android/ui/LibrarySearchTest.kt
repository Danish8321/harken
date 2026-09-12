package com.harken.android.ui

import com.harken.android.data.PartOfDay
import com.harken.android.data.SessionRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.UUID

/**
 * What a run of the Library's search box leaves on screen — above all when it is cancelled,
 * which is how nearly every run ends: the box is under `collectLatest`, so each keystroke
 * cancels the query typed before it (ARC-061).
 */
class LibrarySearchTest {
    private companion object {
        const val DEBOUNCE_MS = 180L
    }

    private fun hit(title: String) =
        SessionRepository.SearchHit(
            session =
                SessionRepository.SessionView(
                    id = UUID.randomUUID(),
                    localTitle = title,
                    partOfDay = PartOfDay.Morning,
                    startedAt = "2026-03-04T09:15:00Z",
                    durationSeconds = 90,
                    segmentCount = 3,
                    status = "Completed",
                    failureReason = null,
                    tags = emptyList(),
                    audioPath = null,
                ),
            matchCount = 1,
            snippet = "…$title…",
            offsetSeconds = 0,
            segmentId = UUID.randomUUID(),
        )

    @Test
    fun `a keystroke cancelling a running query leaves the hits already on screen alone`() =
        runTest {
            val previous = listOf(hit("Standup"))
            val state = MutableStateFlow(LibrarySearchState(query = "stand", results = previous))
            val running = CompletableDeferred<Unit>()

            val job =
                launch {
                    runLibrarySearch(state, "stand", DEBOUNCE_MS) {
                        running.complete(Unit)
                        // Still querying when the next letter arrives, which is the whole
                        // case: a search that has already returned cannot be cancelled.
                        CompletableDeferred<List<SessionRepository.SearchHit>>().await()
                    }
                }
            running.await()
            assertTrue("the spinner should be up while a query runs", state.value.isSearching)

            job.cancelAndJoin()

            assertEquals(
                "a cancelled run published \"no results\" over the term it was replacing",
                previous,
                state.value.results,
            )
        }

    @Test
    fun `a query that finishes publishes its hits`() =
        runTest {
            val found = listOf(hit("Retro"), hit("Planning"))
            val state = MutableStateFlow(LibrarySearchState(query = "re"))

            runLibrarySearch(state, "re", DEBOUNCE_MS) { found }

            assertEquals(found, state.value.results)
            assertFalse("the spinner was left up after the query returned", state.value.isSearching)
        }

    @Test
    fun `a query that fails reads as no matches rather than as a stuck spinner`() =
        runTest {
            val state = MutableStateFlow(LibrarySearchState(query = "retro", results = listOf(hit("Retro"))))

            runLibrarySearch(state, "retro", DEBOUNCE_MS) { throw IOException("the database is gone") }

            assertEquals(emptyList<SessionRepository.SearchHit>(), state.value.results)
            assertFalse(state.value.isSearching)
        }

    @Test
    fun `a term too short to query clears what the last one found`() =
        runTest {
            val state =
                MutableStateFlow(
                    LibrarySearchState(query = "r", results = listOf(hit("Retro")), isSearching = true),
                )

            runLibrarySearch(state, "r", DEBOUNCE_MS) { error("a one-letter term must not be queried") }

            assertEquals(emptyList<SessionRepository.SearchHit>(), state.value.results)
            assertFalse(state.value.isSearching)
        }
}
