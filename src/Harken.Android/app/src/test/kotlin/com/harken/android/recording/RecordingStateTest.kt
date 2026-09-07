package com.harken.android.recording

import com.harken.android.audio.RecordingStopReason
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class RecordingStateTest {
    /** Stands in for elapsedRealtime, so a pause can be several seconds long in no time at all. */
    private var now = 1_000L

    @Before
    fun useTestClock() {
        RecordingState.elapsedRealtime = { now }
    }

    @After
    fun drainState() {
        // Process-wide singleton: a test that leaves a recording in flight poisons the next one.
        RecordingState.markStopped()
        RecordingState.elapsedRealtime = { android.os.SystemClock.elapsedRealtime() }
    }

    @Test
    fun `completion carries the capture's duration and file`() =
        runTest {
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
    fun `completion carries a save failure`() =
        runTest {
            // The recorder writes the row, so the screen learns a save failed the same way it
            // learns anything else about the recording (ARC-016).
            val completed = async { RecordingState.completed.first() }
            yield()

            RecordingState.markStarted(UUID.randomUUID(), "/tmp/unsaved.wav")
            RecordingState.markStopped(durationSeconds = 12, saveError = "disk full")

            assertEquals("disk full", completed.await().saveError)
        }

    @Test
    fun `completion carries why the recording ended`() =
        runTest {
            // The save card leads with the reason, so it has to survive the hand-off from the
            // service: a recording that stopped itself must not look like one the user stopped.
            val completed = async { RecordingState.completed.first() }
            yield()

            RecordingState.markStarted(UUID.randomUUID(), "/tmp/auto.wav")
            RecordingState.markStopped(RecordingStopReason.SilenceTimeout)

            assertEquals(RecordingStopReason.SilenceTimeout, completed.await().stopReason)
        }

    @Test
    fun `a manual stop reports no reason`() =
        runTest {
            val completed = async { RecordingState.completed.first() }
            yield()

            RecordingState.markStarted(UUID.randomUUID(), "/tmp/manual.wav")
            RecordingState.markStopped()

            assertEquals(RecordingStopReason.None, completed.await().stopReason)
        }

    @Test
    fun `a second stop emits nothing`() =
        runTest {
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

    @Test
    fun `the counter freezes while paused`() {
        // The number on screen has to agree with the length of the WAV, and no audio is
        // written while paused (ARC-034).
        RecordingState.markStarted(UUID.randomUUID(), "/tmp/p.wav")
        now += 5_000
        RecordingState.markPaused()

        now += 30_000
        assertEquals(5_000, RecordingState.elapsedMs())
        assertTrue(RecordingState.isPaused.value)
    }

    @Test
    fun `resuming excludes the break, not the audio around it`() {
        RecordingState.markStarted(UUID.randomUUID(), "/tmp/p.wav")
        now += 5_000
        RecordingState.markPaused()
        now += 30_000
        RecordingState.markResumed()
        now += 2_000

        assertEquals(7_000, RecordingState.elapsedMs())
        assertFalse(RecordingState.isPaused.value)
    }

    @Test
    fun `every break is subtracted, not only the last`() {
        RecordingState.markStarted(UUID.randomUUID(), "/tmp/p.wav")
        repeat(3) {
            now += 1_000
            RecordingState.markPaused()
            now += 10_000
            RecordingState.markResumed()
        }

        assertEquals(3_000, RecordingState.elapsedMs())
    }

    @Test
    fun `a second pause does not restart the break`() {
        // The notification's Pause action can be tapped twice before the rebuild lands; the
        // second tap must not discard the time already banked.
        RecordingState.markStarted(UUID.randomUUID(), "/tmp/p.wav")
        now += 5_000
        RecordingState.markPaused()
        now += 10_000
        RecordingState.markPaused()
        now += 10_000
        RecordingState.markResumed()

        assertEquals(5_000, RecordingState.elapsedMs())
    }

    @Test
    fun `resuming a recording that is not paused changes nothing`() {
        RecordingState.markStarted(UUID.randomUUID(), "/tmp/p.wav")
        now += 5_000
        RecordingState.markResumed()

        assertEquals(5_000, RecordingState.elapsedMs())
        assertFalse(RecordingState.isPaused.value)
    }

    @Test
    fun `pausing with no recording in flight is ignored`() {
        RecordingState.markPaused()

        assertFalse(RecordingState.isPaused.value)
        assertEquals(0, RecordingState.elapsedMs())
    }

    @Test
    fun `stopping while paused clears the paused flag`() {
        // Otherwise the next recording starts life looking paused to the UI.
        RecordingState.markStarted(UUID.randomUUID(), "/tmp/p.wav")
        RecordingState.markPaused()
        RecordingState.markStopped()

        assertFalse(RecordingState.isPaused.value)
    }
}
