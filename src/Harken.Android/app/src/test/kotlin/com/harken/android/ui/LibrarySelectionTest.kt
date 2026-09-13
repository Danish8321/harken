package com.harken.android.ui

import com.harken.android.data.PartOfDay
import com.harken.android.data.SessionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Which rows the Library will let a user queue for deletion. Delete is the one irreversible
 * action in the app, and the row it must refuse — one mid-transcription — is only in that
 * state for as long as the decode runs, which on short audio is under a second. That is too
 * narrow to drive from the UI, so the rule is held here.
 */
class LibrarySelectionTest {
    private fun session(status: String) =
        SessionRepository.SessionView(
            id = UUID.randomUUID(),
            localTitle = "Standup",
            partOfDay = PartOfDay.Morning,
            startedAt = "2026-03-04T09:15:00Z",
            durationSeconds = 90,
            segmentCount = 3,
            status = status,
            failureReason = null,
            tags = emptyList(),
            audioPath = null,
        )

    @Test
    fun `a long-press on a recorded row opens selection holding that row`() {
        val row = session("Recorded")

        val state = LibraryUiState().startingSelection(row)

        assertTrue(state.selecting)
        assertEquals(setOf(row.id), state.selectedIds)
    }

    @Test
    fun `a long-press on a row being transcribed does not open selection`() {
        for (status in listOf("Pending", "Running")) {
            val state = LibraryUiState().startingSelection(session(status))

            assertFalse("a \"$status\" row was offered for deletion", state.selecting)
            assertEquals(emptySet<UUID>(), state.selectedIds)
        }
    }

    @Test
    fun `a tap adds a second row and a tap back removes it`() {
        val first = session("Recorded")
        val second = session("Succeeded")

        val both = LibraryUiState().startingSelection(first).togglingSelection(second)
        assertEquals(setOf(first.id, second.id), both.selectedIds)

        val back = both.togglingSelection(second)
        assertEquals(setOf(first.id), back.selectedIds)
    }

    @Test
    fun `deselecting the last row stays in selection mode`() {
        val row = session("Recorded")

        val empty = LibraryUiState().startingSelection(row).togglingSelection(row)

        assertTrue("dropping to zero dropped the user back to the normal header", empty.selecting)
        assertEquals(emptySet<UUID>(), empty.selectedIds)
    }

    @Test
    fun `a row being transcribed cannot be added to a selection already open`() {
        val open = LibraryUiState().startingSelection(session("Recorded"))

        val after = open.togglingSelection(session("Running"))

        assertEquals(open.selectedIds, after.selectedIds)
    }

    @Test
    fun `a tap outside selection mode selects nothing`() {
        val after = LibraryUiState().togglingSelection(session("Recorded"))

        assertFalse(after.selecting)
        assertEquals(emptySet<UUID>(), after.selectedIds)
    }
}
