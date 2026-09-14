package com.harken.android.ui

import com.harken.android.speech.ModelDownloadFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/**
 * The fold both Onboarding and Settings run their download through.
 *
 * Written against `Flow<Int>` rather than `ModelDownloadManager`, so every branch here is
 * reachable without a network, a file or an emulator.
 */
class ModelDownloadTest {
    @Test
    fun `opens Ready when a model is already installed`() {
        assertEquals(ModelDownloadState.Ready, ModelDownloadUi.of(present = true).state)
        assertTrue(ModelDownloadUi.of(present = true).present)
    }

    @Test
    fun `opens NotStarted when no model is installed`() {
        assertEquals(ModelDownloadState.NotStarted, ModelDownloadUi.of(present = false).state)
        assertFalse(ModelDownloadUi.of(present = false).present)
    }

    @Test
    fun `a download ends Ready at 100 with the model present`() =
        runBlocking {
            val emitted = flowOf(10, 55, 90).asDownloadUi(ModelDownloadUi.of(present = false)) { false }.toList()

            // Downloading on the tap, then one per percentage, then Ready.
            assertEquals(List(4) { ModelDownloadState.Downloading } + ModelDownloadState.Ready, emitted.map { it.state })
            assertEquals(listOf(0, 10, 55, 90, 100), emitted.map { it.progress })
            assertTrue(emitted.last().present)
        }

    @Test
    fun `the first value is Downloading, before any progress arrives`() =
        runBlocking {
            val emitted = flowOf<Int>().asDownloadUi(ModelDownloadUi.of(present = false)) { false }.toList()

            assertEquals(ModelDownloadState.Downloading, emitted.first().state)
            assertEquals(0, emitted.first().progress)
        }

    @Test
    fun `a retry clears the error it is retrying`() =
        runBlocking {
            val failed = ModelDownloadUi(state = ModelDownloadState.Failed, error = ModelDownloadFailure.NoConnection)

            val emitted = flowOf(5).asDownloadUi(failed) { false }.toList()

            assertNull(emitted.first().error)
        }

    @Test
    fun `a failure is classified and ends the fold`() =
        runBlocking {
            val emitted =
                flow<Int> {
                    emit(40)
                    throw UnknownHostException("no dns")
                }.asDownloadUi(ModelDownloadUi.of(present = false)) { false }.toList()

            assertEquals(ModelDownloadState.Failed, emitted.last().state)
            assertEquals(ModelDownloadFailure.NoConnection, emitted.last().error)
            // The progress it got to is kept: the bar does not jump back to zero on failure.
            assertEquals(40, emitted.last().progress)
        }

    /**
     * The Settings case `7269302` was about: an update that fails leaves the previous model
     * installed, so the screen must still say "Update", not offer a first-time "Download".
     */
    @Test
    fun `a failed update reports the model still present`() =
        runBlocking {
            val emitted =
                flow<Int> { throw IOException("server hung up") }
                    .asDownloadUi(ModelDownloadUi.of(present = true)) { true }
                    .toList()

            assertEquals(ModelDownloadState.Failed, emitted.last().state)
            assertTrue(emitted.last().present)
        }

    @Test
    fun `a second start while one is running emits nothing and never collects`() =
        runBlocking {
            var collected = false
            val upstream =
                flow {
                    collected = true
                    emit(1)
                }

            val emitted = upstream.asDownloadUi(ModelDownloadUi(state = ModelDownloadState.Downloading, progress = 30)) { false }.toList()

            assertEquals(emptyList<ModelDownloadUi>(), emitted)
            assertFalse(collected)
        }

    /**
     * Leaving the screen mid-transfer is not an outcome. Cancellation propagates untouched,
     * leaving the last emitted value standing rather than claiming a failure that never
     * happened.
     */
    @Test
    fun `cancellation is not reported as a failure`() =
        runBlocking {
            val emitted = mutableListOf<ModelDownloadUi>()
            val cancelled =
                try {
                    flow<Int> {
                        emit(20)
                        throw CancellationException("the user left")
                    }.asDownloadUi(ModelDownloadUi.of(present = false)) { false }
                        .collect { emitted += it }
                    false
                } catch (e: CancellationException) {
                    true
                }

            assertTrue(cancelled)
            assertTrue(emitted.none { it.state == ModelDownloadState.Failed })
            assertEquals(20, emitted.last().progress)
        }
}
