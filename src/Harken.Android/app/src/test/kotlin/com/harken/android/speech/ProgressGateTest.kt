package com.harken.android.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ARC-056: the transcribing notification must not come back after it has been removed.
 *
 * The gate is the ordering between two threads that had none — the decode thread posting
 * progress and the main thread tearing the service down — so the interesting test is the
 * concurrent one, not the sequential pair above it.
 */
class ProgressGateTest {
    @Test
    fun `renders while open`() {
        val gate = ProgressGate()
        var rendered = 0

        gate.render { rendered++ }
        gate.render { rendered++ }

        assertEquals(2, rendered)
    }

    @Test
    fun `a render after the close does not run`() {
        val gate = ProgressGate()
        val rendered = AtomicBoolean(false)

        gate.close {}
        gate.render { rendered.set(true) }

        assertFalse(rendered.get())
    }

    @Test
    fun `a render that arrives while the close is removing the notification waits, and is skipped`() {
        // The exact race: the decode thread calls publishProgress while the main thread is
        // inside stopForeground. Without the lock the notify lands after the removal and
        // the notification stays on screen with nothing left to cancel it.
        val gate = ProgressGate()
        val insideClose = CountDownLatch(1)
        val renderAttempted = CountDownLatch(1)
        val rendered = AtomicBoolean(false)

        val closer =
            Thread {
                gate.close {
                    insideClose.countDown()
                    // Hold the gate long enough that the render below is certainly blocked
                    // on it rather than merely losing a race by a hair.
                    renderAttempted.await(2, TimeUnit.SECONDS)
                    Thread.sleep(50)
                }
            }
        val renderer =
            Thread {
                insideClose.await(2, TimeUnit.SECONDS)
                renderAttempted.countDown()
                gate.render { rendered.set(true) }
            }

        closer.start()
        renderer.start()
        closer.join(5_000)
        renderer.join(5_000)

        assertFalse("the notification was re-posted after it was removed", rendered.get())
    }

    @Test
    fun `closing twice is not an error`() {
        // stopSelf can be reached more than once — the coordinator emitting another id, or
        // the system restarting the service — and a second teardown must not throw.
        val gate = ProgressGate()
        var removals = 0

        gate.close { removals++ }
        gate.close { removals++ }

        assertEquals(2, removals)
        val rendered = AtomicBoolean(false)
        gate.render { rendered.set(true) }
        assertFalse(rendered.get())
    }

    @Test
    fun `renders from several threads while open all run`() {
        val gate = ProgressGate()
        val counted = CountDownLatch(8)

        repeat(8) { Thread { gate.render { counted.countDown() } }.start() }

        assertTrue(counted.await(5, TimeUnit.SECONDS))
    }
}
