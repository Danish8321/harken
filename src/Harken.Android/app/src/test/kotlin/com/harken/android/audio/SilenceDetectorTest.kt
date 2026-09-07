package com.harken.android.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

class SilenceDetectorTest {
    private fun silentChunk(bytes: Int) = ByteArray(bytes)

    /** A chunk of constant [amplitude] — room tone, a hum, a fan: sound, but not speech. */
    private fun toneChunk(
        bytes: Int,
        amplitude: Int,
    ): ByteArray {
        val chunk = ByteArray(bytes)
        var i = 0
        while (i + 1 < bytes) {
            chunk[i] = (amplitude and 0xFF).toByte()
            chunk[i + 1] = ((amplitude shr 8) and 0xFF).toByte()
            i += 2
        }
        return chunk
    }

    /** [seconds] of audio at [amplitude], fed a second at a time. */
    private fun feed(
        detector: SilenceDetector,
        seconds: Int,
        amplitude: Int,
    ): RecordingStopReason {
        var last = RecordingStopReason.None
        val chunk = if (amplitude == 0) silentChunk(32000) else toneChunk(32000, amplitude)
        repeat(seconds) { last = detector.add(chunk, 0, chunk.size) }
        return last
    }

    private fun loudChunk(bytes: Int): ByteArray {
        val chunk = ByteArray(bytes)
        var i = 0
        while (i + 1 < bytes) {
            chunk[i] = 0xFF.toByte()
            chunk[i + 1] = 0x7F // 0x7FFF, above default threshold of 500
            i += 2
        }
        return chunk
    }

    /** Room tone at amplitude 10 with one 8000 tick in it — a knock, not a voice. */
    private fun chunkWithATick(bytes: Int): ByteArray {
        val chunk = ByteArray(bytes)
        var i = 0
        while (i + 1 < bytes) {
            chunk[i] = 0x0A
            chunk[i + 1] = 0x00
            i += 2
        }
        chunk[0] = 0x40
        chunk[1] = 0x1F
        return chunk
    }

    @Test
    fun loudAudioNeverTriggersStop() {
        val detector = SilenceDetector(silenceTimeoutMs = 1000, sessionCapMs = 10_000)
        repeat(5) {
            assertEquals(RecordingStopReason.None, detector.add(loudChunk(3200), 0, 3200))
        }
    }

    @Test
    fun sustainedSilenceTriggersSilenceTimeout() {
        // bytesPerMs = 32 for 16kHz/16-bit/mono, so 1000ms of silence = 32000 bytes.
        val detector = SilenceDetector(silenceTimeoutMs = 1000, sessionCapMs = 60_000)
        val chunk = silentChunk(16000)
        assertEquals(RecordingStopReason.None, detector.add(chunk, 0, chunk.size))
        assertEquals(RecordingStopReason.SilenceTimeout, detector.add(chunk, 0, chunk.size))
    }

    @Test
    fun sessionCapWinsOverSilenceOnTheSameChunk() {
        val detector = SilenceDetector(silenceTimeoutMs = 1000, sessionCapMs = 1000)
        val chunk = silentChunk(32000)
        assertEquals(RecordingStopReason.SessionCap, detector.add(chunk, 0, chunk.size))
    }

    @Test
    fun nonSilentChunkClearsTheSilenceRun() {
        val detector = SilenceDetector(silenceTimeoutMs = 1000, sessionCapMs = 60_000)
        val silent = silentChunk(16000)
        val loud = loudChunk(16000)
        assertEquals(RecordingStopReason.None, detector.add(silent, 0, silent.size))
        assertEquals(RecordingStopReason.None, detector.add(loud, 0, loud.size))
        assertEquals(RecordingStopReason.None, detector.add(silent, 0, silent.size))
    }

    @Test
    fun aChunkIsJudgedByItsLevelNotItsLoudestSample() {
        // Reading the peak made one tick speak for the whole chunk, which on a device held
        // the longest quiet run to 1.4s against the 300s the timeout needs.
        val detector = SilenceDetector(silenceTimeoutMs = 1000, sessionCapMs = 60_000)
        val ticked = chunkWithATick(16000)
        assertEquals(RecordingStopReason.None, detector.add(ticked, 0, ticked.size))
        assertEquals(RecordingStopReason.SilenceTimeout, detector.add(ticked, 0, ticked.size))
    }

    @Test
    fun aTransientDentsTheSilenceRunRatherThanRestartingIt() {
        // 20s of quiet, then a 100ms knock: 1s of run burned, 19s left — so a room with the
        // occasional noise in it still reaches the timeout.
        val detector = SilenceDetector(silenceTimeoutMs = 20_000, sessionCapMs = 300_000)
        val silent = silentChunk(32000 * 20)
        val knock = loudChunk(3200)
        assertEquals(RecordingStopReason.SilenceTimeout, detector.add(silent, 0, silent.size))

        val after = SilenceDetector(silenceTimeoutMs = 20_000, sessionCapMs = 300_000)
        after.add(silent, 0, silent.size - 32000) // 19s: just under the timeout
        after.add(knock, 0, knock.size)
        // The knock cost 1s of the 19s run, so one more second of quiet is not yet enough...
        assertEquals(RecordingStopReason.None, after.add(silentChunk(32000), 0, 32000))
        // ...but the second one gets there. Under the old rule the run would have restarted
        // at zero here and the timeout would never have arrived.
        assertEquals(RecordingStopReason.SilenceTimeout, after.add(silentChunk(32000), 0, 32000))
    }

    @Test
    fun speechClearsTheRunOutright() {
        // The case that must never be cut off: a pause mid-sentence.
        val detector = SilenceDetector(silenceTimeoutMs = 5000, sessionCapMs = 300_000)
        val silent = silentChunk(32000 * 4)
        val speech = loudChunk(32000)
        detector.add(silent, 0, silent.size)
        detector.add(speech, 0, speech.size)
        assertEquals(RecordingStopReason.None, detector.add(silent, 0, silent.size))
    }

    @Test
    fun aSteadyToneBelowTheCapIsNotSpeech() {
        // A fan, a hum, an empty room: sound throughout, nobody in it. Under the fixed 500
        // this recording was audible and never timed out; the timeout existed for exactly
        // this case and did nothing.
        val detector = SilenceDetector(silenceTimeoutMs = 5000, sessionCapMs = 300_000)

        assertEquals(RecordingStopReason.SilenceTimeout, feed(detector, seconds = 6, amplitude = 300))
    }

    @Test
    fun aChunkAtTheCapIsSpeechHoweverQuietTheRoomWasBefore() {
        // The ceiling's job: 12x a large floor can put speech out of reach, and a recording
        // that cannot hear speech stops in the middle of a meeting.
        val detector = SilenceDetector(silenceTimeoutMs = 5000, sessionCapMs = 300_000)
        feed(detector, seconds = 4, amplitude = 300)

        assertEquals(
            RecordingStopReason.None,
            feed(detector, seconds = 1, amplitude = NoiseFloor.MAX_SPEECH_THRESHOLD),
        )
    }

    @Test
    fun theFloorTracksRecentAudioNotTheWholeRecording() {
        // Reading the floor from the whole recording is what does not work: the near-silence
        // between a meeting's words pins it near zero for hours afterwards, so the room
        // never rises above it and the timeout never fires again.
        val detector = SilenceDetector(silenceTimeoutMs = 300_000, sessionCapMs = 3_600_000)

        feed(detector, seconds = NoiseFloor.WINDOW_SECONDS, amplitude = 20)
        assertEquals(20 * NoiseFloor.SPEECH_FACTOR, detector.speechThreshold)

        feed(detector, seconds = NoiseFloor.WINDOW_SECONDS, amplitude = 300)
        assertEquals(NoiseFloor.MAX_SPEECH_THRESHOLD, detector.speechThreshold)
    }

    @Test
    fun theVerdictDoesNotDependOnHowTheAudioIsChunked() {
        // The detector is byte-driven so that a late or coalesced chunk cannot change the
        // outcome. The floor underneath it has to be too.
        val big = SilenceDetector(silenceTimeoutMs = 5000, sessionCapMs = 300_000)
        val small = SilenceDetector(silenceTimeoutMs = 5000, sessionCapMs = 300_000)
        val oneSecond = toneChunk(32000, 300)
        val eighthOfASecond = toneChunk(4000, 300)

        repeat(4) { big.add(oneSecond, 0, oneSecond.size) }
        repeat(32) { small.add(eighthOfASecond, 0, eighthOfASecond.size) }

        assertEquals(big.speechThreshold, small.speechThreshold)
        assertEquals(big.peakSilentMs, small.peakSilentMs)
    }

    @Test
    fun noStopIsPossibleBeforeTheFloorHasAFullWindow() {
        // Why no warm-up rule is needed: the shipped timeout is five minutes and the window
        // is one, so the floor has been full for four minutes before a stop can happen.
        val detector =
            SilenceDetector(
                silenceTimeoutMs = TimeUnit.MINUTES.toMillis(5),
                sessionCapMs = TimeUnit.HOURS.toMillis(3),
            )

        assertEquals(RecordingStopReason.None, feed(detector, NoiseFloor.WINDOW_SECONDS, amplitude = 0))
    }
}
