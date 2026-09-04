package com.harken.android.recording

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
