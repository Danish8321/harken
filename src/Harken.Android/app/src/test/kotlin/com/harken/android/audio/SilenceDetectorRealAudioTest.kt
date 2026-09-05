package com.harken.android.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * The detector against a real meeting rather than a synthesised one.
 *
 * The synthetic tests fix the rule in place; this one is why the rule changed. Every level
 * in the old design was chosen against fixtures, and fixtures are ~4x louder than people
 * talking in a room — the shipped threshold of 500 read 97% of this recording as silence
 * and would have stopped it at 617 seconds of 1273. Nothing built from synthetic audio
 * catches that, so the audio is committed.
 *
 * app/src/test/resources/ami-es2002a-0-90s.wav is 90 seconds of the AMI Meeting Corpus
 * (ES2002a), CC BY 4.0 — see ATTRIBUTION.md beside it.
 */
class SilenceDetectorRealAudioTest {

    /** The PCM payload of the excerpt, past WavWriter's canonical 44-byte header. */
    private fun meetingAudio(): ByteArray {
        val wav = checkNotNull(javaClass.getResourceAsStream("/ami-es2002a-0-90s.wav")) {
            "ami-es2002a-0-90s.wav is missing from the test resources"
        }.use { it.readBytes() }
        return wav.copyOfRange(WavFormat.HeaderLength, wav.size)
    }

    /** Feeds the audio in the 160 ms chunks AudioRecordCapture delivers. */
    private fun play(detector: SilenceDetector, pcm: ByteArray): RecordingStopReason {
        val chunk = WavFormat.SampleRate * WavFormat.Channels * (WavFormat.BitsPerSample / 8) * 160 / 1000
        var offset = 0
        while (offset < pcm.size) {
            val length = minOf(chunk, pcm.size - offset)
            val stop = detector.add(pcm, offset, length)
            if (stop != RecordingStopReason.None) return stop
            offset += length
        }
        return RecordingStopReason.None
    }

    @Test
    fun aLiveMeetingIsNotSilence() {
        // Four people talking for 90 seconds, with the pauses that are in any conversation.
        // The run reaches 9.1 s here; on the shipped rule it reached 65.3 s over the same
        // audio, which is the whole defect in one number.
        val detector = SilenceDetector(
            silenceTimeoutMs = TimeUnit.SECONDS.toMillis(30),
            sessionCapMs = TimeUnit.HOURS.toMillis(3),
        )

        assertEquals(RecordingStopReason.None, play(detector, meetingAudio()))
        assertTrue(
            "longest quiet run was ${detector.peakSilentMs} ms",
            detector.peakSilentMs <= TimeUnit.SECONDS.toMillis(15),
        )
    }

    @Test
    fun theThresholdSettlesWhereTheMeetingIs() {
        // Not asserted for its own sake: it is the number reported on recording_stopped, and
        // a field report of "it stopped in the middle of my meeting" is read against it.
        // A headset mix sits far below the old fixed 500 — that is why the meeting failed.
        val detector = SilenceDetector(
            silenceTimeoutMs = TimeUnit.SECONDS.toMillis(30),
            sessionCapMs = TimeUnit.HOURS.toMillis(3),
        )
        play(detector, meetingAudio())

        assertTrue(
            "floor ${detector.noiseFloorEstimate}, speech at ${detector.speechThreshold}",
            detector.speechThreshold in NoiseFloor.MinSpeechThreshold until SpeechSpans.MaxAmplitudeThreshold,
        )
    }
}
