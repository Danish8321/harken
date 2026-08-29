package com.harken.android.speech

import com.harken.android.data.TranscriptionSink
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSink : TranscriptionSink {
    val started = mutableListOf<UUID>()
    val completed = mutableListOf<UUID>()
    val failed = mutableListOf<Pair<UUID, String>>()

    override suspend fun startLocalTranscription(id: UUID) {
        started += id
    }

    override suspend fun completeLocal(id: UUID, segments: List<LocalTranscribedSegment>, durationSeconds: Int) {
        completed += id
    }

    override suspend fun failLocal(id: UUID, reason: String) {
        failed += id to reason
    }
}

private class FakeModelProvider(private val result: Result<String> = Result.success("/models/whisper.bin")) : ModelProvider {
    override suspend fun ensureModel(): Result<String> = result
}

private class FakeTranscriber(
    private val holdUntilLatch: CountDownLatch? = null,
    private val throwOnTranscribe: Exception? = null,
) : Transcriber {
    val releaseCount = AtomicInteger(0)

    override suspend fun transcribe(wavPath: String, modelPath: String): List<LocalTranscribedSegment> {
        holdUntilLatch?.await(2, TimeUnit.SECONDS)
        throwOnTranscribe?.let { throw it }
        return listOf(LocalTranscribedSegment(0, "hello"))
    }

    override fun release() {
        releaseCount.incrementAndGet()
    }
}

// wavDurationSeconds reads the file off disk, which a fake WAV path won't satisfy — every
// test here uses a real, empty temp file so that path resolves to 0 instead of throwing.
private fun tempWavPath(): String = kotlin.io.path.createTempFile(suffix = ".wav").toFile().apply { deleteOnExit() }.absolutePath

class TranscriptionCoordinatorTest {

    @Test
    fun `happy path completes the session and releases the transcriber`() {
        val sink = FakeSink()
        val transcriber = FakeTranscriber()
        val sessionId = UUID.randomUUID()

        val started = TranscriptionCoordinator.transcribe(sink, FakeModelProvider(), transcriber, sessionId, tempWavPath())
        assertTrue(started)

        waitForIdle()

        assertEquals(listOf(sessionId), sink.started)
        assertEquals(listOf(sessionId), sink.completed)
        assertTrue(sink.failed.isEmpty())
        assertEquals(1, transcriber.releaseCount.get())
    }

    @Test
    fun `a transcription failure marks the session failed and still releases the transcriber`() {
        val sink = FakeSink()
        val transcriber = FakeTranscriber(throwOnTranscribe = IllegalStateException("boom"))
        val sessionId = UUID.randomUUID()

        TranscriptionCoordinator.transcribe(sink, FakeModelProvider(), transcriber, sessionId, tempWavPath())

        waitForIdle()

        assertTrue(sink.completed.isEmpty())
        assertEquals(1, sink.failed.size)
        assertEquals("boom", sink.failed.single().second)
        assertEquals(1, transcriber.releaseCount.get())
    }

    @Test
    fun `a second call is rejected while one session is already in flight`() {
        val sink = FakeSink()
        val latch = CountDownLatch(1)
        val transcriber = FakeTranscriber(holdUntilLatch = latch)
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()

        val firstStarted = TranscriptionCoordinator.transcribe(sink, FakeModelProvider(), transcriber, firstId, tempWavPath())
        val secondStarted = TranscriptionCoordinator.transcribe(sink, FakeModelProvider(), transcriber, secondId, tempWavPath())

        assertTrue(firstStarted)
        assertFalse(secondStarted)

        latch.countDown()
        waitForIdle()

        assertEquals(listOf(firstId), sink.completed)
    }

    @Test
    fun `a new session can start once the previous one has released`() {
        val sink = FakeSink()
        val firstId = UUID.randomUUID()
        val secondId = UUID.randomUUID()

        TranscriptionCoordinator.transcribe(sink, FakeModelProvider(), FakeTranscriber(), firstId, tempWavPath())
        waitForIdle()

        val secondStarted = TranscriptionCoordinator.transcribe(sink, FakeModelProvider(), FakeTranscriber(), secondId, tempWavPath())
        assertTrue(secondStarted)
        waitForIdle()

        assertEquals(listOf(firstId, secondId), sink.completed)
    }

    // TranscriptionCoordinator's own scope is a real background CoroutineScope (deliberately
    // outside any test/ViewModel scope, see its class doc), so tests poll rather than
    // control a TestDispatcher.
    private fun waitForIdle() = runBlocking {
        val deadline = System.currentTimeMillis() + 2000
        while (TranscriptionCoordinator.activeSessionId.value != null && System.currentTimeMillis() < deadline) {
            delay(20)
        }
    }
}
