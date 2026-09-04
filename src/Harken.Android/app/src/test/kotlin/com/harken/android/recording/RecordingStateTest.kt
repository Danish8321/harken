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
    fun `completion carries the capture's duration`() = runTest {
        val id = UUID.randomUUID()
        val completed = async { RecordingState.completed.first() }
        yield()

        RecordingState.markStarted(id, "/tmp/$id.wav")
        Thread.sleep(1100)
        RecordingState.markStopped()

        val result = completed.await()
        assertEquals(id, result.recordingId)
        // Wall-clock, so pinned to a range rather than a value: the point is that it is the
        // real length and not 0, which is what the Library used to show for every recording.
        assertTrue("expected >= 1s, got ${result.durationSeconds}", result.durationSeconds >= 1)
        assertTrue("expected < 10s, got ${result.durationSeconds}", result.durationSeconds < 10)
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
