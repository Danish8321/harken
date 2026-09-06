package com.harken.android.speech

import com.harken.android.data.TranscriptionSink
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.awaitCancellation
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

/**
 * @param holdUntilLatch blocks the decode so a test can observe the in-flight state.
 * @param suspendForever parks the decode at a cancellable suspension point, which is what
 *   a real decode between spans looks like to a cancel.
 */
private class FakeTranscriber(
    private val holdUntilLatch: CountDownLatch? = null,
    private val throwOnTranscribe: Exception? = null,
    private val suspendForever: Boolean = false,
) : Transcriber {
    val releaseCount = AtomicInteger(0)
    val started = CountDownLatch(1)

    override suspend fun transcribe(
        wavPath: String,
        modelPath: String,
        onProgress: (fraction: Float) -> Unit,
    ): List<LocalTranscribedSegment> {
        holdUntilLatch?.await(2, TimeUnit.SECONDS)
        throwOnTranscribe?.let { throw it }
        onProgress(0.5f)
        started.countDown()
        if (suspendForever) awaitCancellation()
        onProgress(1f)
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
    fun `progress is reported to the caller`() {
        val seen = java.util.concurrent.CopyOnWriteArrayList<Float>()

        TranscriptionCoordinator.transcribe(
            FakeSink(), FakeModelProvider(), FakeTranscriber(), UUID.randomUUID(), tempWavPath(),
            onProgress = { seen += it },
        )
        waitForIdle()

        assertEquals(listOf(0.5f, 1f), seen.toList())
    }

    @Test
    fun `cancelling a running session fails it with the cancelled message and releases the transcriber`() {
        val sink = FakeSink()
        val transcriber = FakeTranscriber(suspendForever = true)
        val sessionId = UUID.randomUUID()

        TranscriptionCoordinator.transcribe(
            sink, FakeModelProvider(), transcriber, sessionId, tempWavPath(),
            cancelledMessage = "you stopped it",
        )
        assertTrue("decode never reached the cancellation point", transcriber.started.await(2, TimeUnit.SECONDS))

        TranscriptionCoordinator.cancel()
        waitForIdle()

        assertTrue(sink.completed.isEmpty())
        assertEquals(sessionId to "you stopped it", sink.failed.single())
        // Released, so the next session gets a clean whisper context rather than the
        // half-decoded one this cancel abandoned.
        assertEquals(1, transcriber.releaseCount.get())
        assertEquals(null, TranscriptionCoordinator.activeSessionId.value)
    }

    @Test
    fun `a new session can start after a cancel`() {
        val sink = FakeSink()
        val secondId = UUID.randomUUID()

        val cancelled = FakeTranscriber(suspendForever = true)
        TranscriptionCoordinator.transcribe(sink, FakeModelProvider(), cancelled, UUID.randomUUID(), tempWavPath())
        cancelled.started.await(2, TimeUnit.SECONDS)
        TranscriptionCoordinator.cancel()
        waitForIdle()

        assertTrue(TranscriptionCoordinator.transcribe(sink, FakeModelProvider(), FakeTranscriber(), secondId, tempWavPath()))
        waitForIdle()

        assertEquals(listOf(secondId), sink.completed)
    }

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
