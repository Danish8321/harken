package com.harken.android.ingest

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class ImportCoordinatorTest {
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()

    @Before
    fun setUp() {
        ImportCoordinator.reset()
        ImportCoordinator.isRecording = { false }
    }

    @After
    fun tearDown() {
        ImportCoordinator.reset()
    }

    @Test
    fun `an import is admitted when nothing else is happening`() {
        assertTrue(ImportCoordinator.begin(first) is ImportAdmission.Admitted)
        assertEquals(first, ImportCoordinator.activeImportId.value)
    }

    @Test
    fun `a second import is refused while the first runs`() {
        // Three voice notes shared at once is the case this exists for. There is no queue,
        // so the honest answer is a refusal rather than two silently dropped.
        ImportCoordinator.begin(first)

        assertEquals(ImportAdmission.AlreadyImporting, ImportCoordinator.begin(second))
    }

    @Test
    fun `the slot is free again once the import ends`() {
        ImportCoordinator.begin(first)
        ImportCoordinator.end(first)

        assertNull(ImportCoordinator.activeImportId.value)
        assertTrue(ImportCoordinator.begin(second) is ImportAdmission.Admitted)
    }

    @Test
    fun `an import is refused while the microphone is open`() {
        // A decode competes with a capture, and only one of the two is unrepeatable.
        ImportCoordinator.isRecording = { true }

        assertEquals(ImportAdmission.RecordingInProgress, ImportCoordinator.begin(first))
        assertNull("a refusal must not claim the slot", ImportCoordinator.activeImportId.value)
    }

    @Test
    fun `a late finisher cannot clear the import that replaced it`() {
        ImportCoordinator.begin(first)
        ImportCoordinator.end(first)
        ImportCoordinator.begin(second)

        ImportCoordinator.end(first)

        assertEquals(second, ImportCoordinator.activeImportId.value)
    }

    @Test
    fun `cancel reaches the decode that is running`() {
        val admission = ImportCoordinator.begin(first) as ImportAdmission.Admitted

        ImportCoordinator.cancel()

        assertTrue(admission.cancelled.get())
    }

    @Test
    fun `a cancelled import does not cancel the next one`() {
        // The flag is per import. Reusing one would make the next import stop before it
        // decoded a single buffer, and look like it had simply failed.
        val cancelledOne = (ImportCoordinator.begin(first) as ImportAdmission.Admitted).cancelled
        ImportCoordinator.cancel()
        ImportCoordinator.end(first)

        val next = (ImportCoordinator.begin(second) as ImportAdmission.Admitted).cancelled

        assertFalse(next.get())
        assertTrue("the first import's flag must stay set", cancelledOne.get())
    }

    @Test
    fun `progress is reported within its own bounds and reset between imports`() {
        ImportCoordinator.begin(first)
        ImportCoordinator.publishProgress(0.5f)
        assertEquals(0.5f, ImportCoordinator.progress.value, 0f)

        ImportCoordinator.publishProgress(4f)
        assertEquals(1f, ImportCoordinator.progress.value, 0f)

        ImportCoordinator.end(first)
        assertEquals(0f, ImportCoordinator.progress.value, 0f)
    }
}
