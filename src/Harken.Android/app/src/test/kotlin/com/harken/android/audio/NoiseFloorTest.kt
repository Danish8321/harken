package com.harken.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The memoisation in [NoiseFloor] (ARC-011). The percentile itself is exercised through
 * [SilenceDetectorTest] and the real-audio test; what is asserted here is that caching it
 * did not change a single answer.
 */
class NoiseFloorTest {

    private val bytes = 5120

    private fun floorOver(levels: List<Int>): NoiseFloor =
        NoiseFloor().apply { levels.forEach { observe(it, bytes) } }

    @Test
    fun `reading twice without observing gives the same answer`() {
        val floor = floorOver(listOf(10, 400, 20, 900, 30))

        assertEquals(floor.estimate, floor.estimate)
        assertEquals(floor.speechThreshold, floor.speechThreshold)
    }

    @Test
    fun `a chunk observed after a read moves the answer`() {
        // The cache is invalidated on write, so a floor that has already been read once
        // must not keep answering with what it said before. This is the whole risk the
        // memoisation introduces.
        val floor = NoiseFloor(windowBytes = bytes * 4)
        repeat(4) { floor.observe(10, bytes) }
        assertEquals(10, floor.estimate)

        repeat(4) { floor.observe(1000, bytes) }

        assertEquals(1000, floor.estimate)
    }

    @Test
    fun `an unread floor is still correct once it is read`() {
        // The cache is invalidated on write, not recomputed there: a recording nothing
        // reads the threshold of must still answer correctly when it is finally asked, on
        // the recording_stopped event.
        val eager = floorOver(listOf(5, 6, 7))
        repeat(10) { eager.speechThreshold }
        val lazy = floorOver(listOf(5, 6, 7))

        assertEquals(eager.estimate, lazy.estimate)
        assertEquals(eager.speechThreshold, lazy.speechThreshold)
    }

    @Test
    fun `an empty window is a floor of zero at the minimum threshold`() {
        val floor = NoiseFloor()

        assertEquals(0, floor.estimate)
        assertEquals(NoiseFloor.MinSpeechThreshold, floor.speechThreshold)
    }
}
