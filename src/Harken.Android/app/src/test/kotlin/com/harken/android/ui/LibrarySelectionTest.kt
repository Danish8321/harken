package com.harken.android.ui

import com.harken.android.data.PartOfDay
import com.harken.android.data.SessionRepository
import com.harken.android.data.TranscriptionStatus
import com.harken.android.data.TranscriptionStatus.Recorded
import com.harken.android.data.TranscriptionStatus.Running
import com.harken.android.data.TranscriptionStatus.Succeeded
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
    private fun session(status: TranscriptionStatus) =
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
        val row = session(Recorded)

        val state = LibraryUiState().startingSelection(row)

        assertTrue(state.selecting)
        assertEquals(setOf(row.id), state.selectedIds)
    }

    @Test
    fun `a long-press on a row being transcribed does not open selection`() {
        val state = LibraryUiState().startingSelection(session(Running))

        assertFalse("a Running row was offered for deletion", state.selecting)
        assertEquals(emptySet<UUID>(), state.selectedIds)
    }

    @Test
    fun `a long-press is refused from the tap that starts a transcription, not from the row's write`() {
        // TranscriptionCoordinator sets transcribingSessionId immediately; the row still
        // reads Recorded until Room's write lands a coroutine hop later. The guard used to
        // consult only the row, so for the length of that hop the recording being decoded
        // could be selected and deleted out from under the decode (ARC-064).
        val row = session(Recorded)

        val state = LibraryUiState(transcribingSessionId = row.id).startingSelection(row)

        assertFalse("a row the coordinator had already started was offered for deletion", state.selecting)
        assertEquals(emptySet<UUID>(), state.selectedIds)
    }

    @Test
    fun `a tap adds a second row and a tap back removes it`() {
        val first = session(Recorded)
        val second = session(Succeeded)

        val both = LibraryUiState().startingSelection(first).togglingSelection(second)
        assertEquals(setOf(first.id, second.id), both.selectedIds)

        val back = both.togglingSelection(second)
        assertEquals(setOf(first.id), back.selectedIds)
    }

    @Test
    fun `deselecting the last row stays in selection mode`() {
        val row = session(Recorded)

        val empty = LibraryUiState().startingSelection(row).togglingSelection(row)

        assertTrue("dropping to zero dropped the user back to the normal header", empty.selecting)
        assertEquals(emptySet<UUID>(), empty.selectedIds)
    }

    @Test
    fun `a row being transcribed cannot be added to a selection already open`() {
        val open = LibraryUiState().startingSelection(session(Recorded))

        val after = open.togglingSelection(session(Running))

        assertEquals(open.selectedIds, after.selectedIds)
    }

    @Test
    fun `a row the coordinator just started cannot be added to a selection already open`() {
        val transcribing = session(Recorded)
        val open =
            LibraryUiState(transcribingSessionId = transcribing.id)
                .startingSelection(session(Recorded))

        val after = open.togglingSelection(transcribing)

        assertEquals(open.selectedIds, after.selectedIds)
    }

    @Test
    fun `a tap outside selection mode selects nothing`() {
        val after = LibraryUiState().togglingSelection(session(Recorded))

        assertFalse(after.selecting)
        assertEquals(emptySet<UUID>(), after.selectedIds)
    }
}
