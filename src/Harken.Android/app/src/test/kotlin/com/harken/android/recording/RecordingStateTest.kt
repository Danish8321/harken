package com.harken.android.recording

import com.harken.android.audio.RecordingStopReason
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStateTest {
    @After
    fun drainState() {
        // Process-wide singleton: a test that leaves a recording in flight poisons the next one.
        RecordingState.markStopped()
    }

    @Test
    fun `completion carries the capture's duration and file`() = runTest {
        val id = UUID.randomUUID()
        val completed = async { RecordingState.completed.first() }
        yield()

        RecordingState.markStarted(id, "/tmp/$id.wav")
        // The recorder measures the length off the finished WAV and passes it in; this
        // object no longer times the capture itself (ARC-009).
        RecordingState.markStopped(durationSeconds = 249)

        val result = completed.await()
        assertEquals(id, result.recordingId)
        assertEquals("/tmp/$id.wav", result.filePath)
        assertEquals(249, result.durationSeconds)
        assertNull(result.saveError)
    }

    @Test
    fun `completion carries a save failure`() = runTest {
        // The recorder writes the row, so the screen learns a save failed the same way it
        // learns anything else about the recording (ARC-016).
        val completed = async { RecordingState.completed.first() }
        yield()

        RecordingState.markStarted(UUID.randomUUID(), "/tmp/unsaved.wav")
        RecordingState.markStopped(durationSeconds = 12, saveError = "disk full")

        assertEquals("disk full", completed.await().saveError)
    }

    @Test
    fun `completion carries why the recording ended`() = runTest {
        // The save card leads with the reason, so it has to survive the hand-off from the
        // service: a recording that stopped itself must not look like one the user stopped.
        val completed = async { RecordingState.completed.first() }
        yield()

        RecordingState.markStarted(UUID.randomUUID(), "/tmp/auto.wav")
        RecordingState.markStopped(RecordingStopReason.SilenceTimeout)

        assertEquals(RecordingStopReason.SilenceTimeout, completed.await().stopReason)
    }

    @Test
    fun `a manual stop reports no reason`() = runTest {
        val completed = async { RecordingState.completed.first() }
        yield()

        RecordingState.markStarted(UUID.randomUUID(), "/tmp/manual.wav")
        RecordingState.markStopped()

        assertEquals(RecordingStopReason.None, completed.await().stopReason)
    }

    @Test
    fun `a second stop emits nothing`() = runTest {
        RecordingState.markStarted(UUID.randomUUID(), "/tmp/a.wav")
        RecordingState.markStopped()

        val second = async { RecordingState.completed.first() }
        yield()
        RecordingState.markStopped()
        yield()

        assertTrue("duplicate stop re-emitted a completion", second.isActive)
        second.cancel()
        assertNull(RecordingState.recordingId)
    }
}
