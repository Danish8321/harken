using Harken.Core.Audio;
using Xunit;

namespace Harken.Core.UnitTests;

/// <summary>
/// The two auto-stop limits ADR-0007 moved to the client. Both are hard to observe on a
/// device — you would have to sit through five minutes of silence or three hours of
/// recording to see either fire — so the real coverage lives here (slice-06 Task 5).
/// </summary>
public class SilenceDetectorTests
{
    private static readonly TimeSpan Timeout = TimeSpan.FromSeconds(5);
    private static readonly TimeSpan Cap = TimeSpan.FromSeconds(60);

    private static SilenceDetector Detector(TimeSpan? timeout = null, TimeSpan? cap = null)
        => new(timeout ?? Timeout, cap ?? Cap);

    /// <summary>PCM of the given length where every sample sits at <paramref name="amplitude"/>.</summary>
    private static byte[] Pcm(TimeSpan duration, short amplitude)
    {
        var samples = (int)(duration.TotalSeconds * WavWriter.SampleRate);
        var pcm = new byte[samples * 2];

        for (var i = 0; i < samples; i++)
        {
            pcm[i * 2] = (byte)(amplitude & 0xFF);
            pcm[(i * 2) + 1] = (byte)((amplitude >> 8) & 0xFF);
        }

        return pcm;
    }

    private static byte[] Quiet(TimeSpan duration) => Pcm(duration, amplitude: 10);

    private static byte[] Loud(TimeSpan duration) => Pcm(duration, amplitude: 8_000);

    [Fact]
    public void SilenceBelowThresholdTripsAtTheConfiguredSpan()
    {
        var detector = Detector();

        Assert.Equal(RecordingStopReason.None, detector.Add(Quiet(TimeSpan.FromSeconds(4))));
        Assert.Equal(RecordingStopReason.SilenceTimeout, detector.Add(Quiet(TimeSpan.FromSeconds(1))));
    }

    [Fact]
    public void AudibleChunkClearsTheSilenceRun()
    {
        // The whole point of a *run*: a user who pauses to think mid-sentence must not have
        // their recording ended out from under them. A second of speech burns ten seconds
        // of run, so any real utterance clears it outright.
        var detector = Detector();

        detector.Add(Quiet(TimeSpan.FromSeconds(4)));
        detector.Add(Loud(TimeSpan.FromSeconds(1)));

        Assert.Equal(TimeSpan.Zero, detector.SilenceRun);
        Assert.Equal(RecordingStopReason.None, detector.Add(Quiet(TimeSpan.FromSeconds(4))));
    }

    [Fact]
    public void ATransientDentsTheSilenceRunRatherThanRestartingIt()
    {
        // Measured on a device: reading the loudest sample and zeroing the run on it held
        // the longest quiet run to 1.4s in a real room, so the five-minute timeout could
        // never arrive. A knock on the desk must cost a little of the run, not all of it.
        var detector = Detector(timeout: TimeSpan.FromSeconds(30), cap: TimeSpan.FromMinutes(5));

        detector.Add(Quiet(TimeSpan.FromSeconds(20)));
        detector.Add(Loud(TimeSpan.FromSeconds(0.1)));

        Assert.Equal(TimeSpan.FromSeconds(19), detector.SilenceRun);
    }

    [Fact]
    public void ARoomWithOccasionalClicksStillTimesOut()
    {
        // The forgotten-recorder case ADR-0007 exists for: nobody is talking, but the room
        // is not a vacuum. This is what the old peak-and-reset rule could not do.
        var detector = Detector(timeout: TimeSpan.FromSeconds(60), cap: TimeSpan.FromMinutes(10));
        var reason = RecordingStopReason.None;

        // A click every 10 seconds, for two minutes.
        for (var i = 0; i < 12 && reason == RecordingStopReason.None; i++)
        {
            detector.Add(Loud(TimeSpan.FromSeconds(0.1)));
            reason = detector.Add(Quiet(TimeSpan.FromSeconds(10)));
        }

        Assert.Equal(RecordingStopReason.SilenceTimeout, reason);
    }

    [Fact]
    public void AChunkIsJudgedByItsLevelNotItsLoudestSample()
    {
        // A tick inside 100ms of room tone is not someone speaking. Under the old rule the
        // one sample decided the chunk; under RMS it is 1/1600th of it.
        var detector = Detector();
        var chunk = Quiet(TimeSpan.FromSeconds(0.1));
        chunk[0] = 0x40;
        chunk[1] = 0x1F; // one sample at 8000, among 1600 at 10

        detector.Add(chunk);

        Assert.Equal(detector.Duration, detector.SilenceRun);
    }

    [Fact]
    public void SustainedSpeechIsNeverMistakenForRoomTone()
    {
        // The other side of the same rule: level must not average speech away.
        var detector = Detector();

        detector.Add(Quiet(TimeSpan.FromSeconds(4)));
        detector.Add(Loud(TimeSpan.FromSeconds(0.5)));

        Assert.Equal(TimeSpan.Zero, detector.SilenceRun);
    }

    [Fact]
    public void SilenceRunAccumulatesAcrossChunkBoundaries()
    {
        // AudioRecord hands over whatever it has; the same silence arrives in different
        // chunk sizes run to run, and must trip at the same point regardless.
        var detector = Detector();

        for (var i = 0; i < 9; i++)
        {
            Assert.Equal(RecordingStopReason.None, detector.Add(Quiet(TimeSpan.FromSeconds(0.5))));
        }

        Assert.Equal(RecordingStopReason.SilenceTimeout, detector.Add(Quiet(TimeSpan.FromSeconds(0.5))));
    }

    [Fact]
    public void CapFiresIndependentlyOfSilence()
    {
        // Continuously loud audio never trips the silence timeout, so only the cap can end
        // a recording someone forgot to stop.
        var detector = Detector(timeout: TimeSpan.FromSeconds(5), cap: TimeSpan.FromSeconds(10));

        Assert.Equal(RecordingStopReason.None, detector.Add(Loud(TimeSpan.FromSeconds(9))));
        Assert.Equal(RecordingStopReason.SessionCap, detector.Add(Loud(TimeSpan.FromSeconds(1))));
    }

    [Fact]
    public void CapCountsSilentAudioToo()
    {
        // A recording left running in an empty room hits the silence timeout first, but if
        // the timeout were longer than the cap the cap must still bound it.
        var detector = Detector(timeout: TimeSpan.FromSeconds(30), cap: TimeSpan.FromSeconds(10));

        Assert.Equal(RecordingStopReason.SessionCap, detector.Add(Quiet(TimeSpan.FromSeconds(10))));
    }

    [Fact]
    public void CapWinsWhenBothLimitsLandOnTheSameChunk()
    {
        var detector = Detector(timeout: TimeSpan.FromSeconds(5), cap: TimeSpan.FromSeconds(5));

        Assert.Equal(RecordingStopReason.SessionCap, detector.Add(Quiet(TimeSpan.FromSeconds(5))));
    }

    [Fact]
    public void DurationTracksEveryChunkRegardlessOfLoudness()
    {
        var detector = Detector();

        detector.Add(Loud(TimeSpan.FromSeconds(1)));
        detector.Add(Quiet(TimeSpan.FromSeconds(2)));

        Assert.Equal(TimeSpan.FromSeconds(3), detector.Duration);
        Assert.Equal(TimeSpan.FromSeconds(2), detector.SilenceRun);
    }

    [Fact]
    public void AmplitudeExactlyAtThresholdCountsAsAudible()
    {
        var detector = Detector();

        detector.Add(Pcm(TimeSpan.FromSeconds(1), SilenceDetector.DefaultAmplitudeThreshold));

        Assert.Equal(TimeSpan.Zero, detector.SilenceRun);
    }

    [Fact]
    public void LoudNegativeSamplesAreNotMistakenForSilence()
    {
        // A waveform is symmetric around zero; reading magnitude wrongly would make half of
        // every loud sound register as silence.
        var detector = Detector();

        detector.Add(Pcm(TimeSpan.FromSeconds(1), -8_000));

        Assert.Equal(TimeSpan.Zero, detector.SilenceRun);
    }

    [Fact]
    public void FullScaleNegativeSampleIsAudible()
    {
        // short.MinValue has no positive counterpart — the loudest possible sample must not
        // overflow into being read as quiet.
        var detector = Detector();

        detector.Add(Pcm(TimeSpan.FromSeconds(1), short.MinValue));

        Assert.Equal(TimeSpan.Zero, detector.SilenceRun);
    }

    [Fact]
    public void EmptyChunkChangesNothing()
    {
        var detector = Detector();

        Assert.Equal(RecordingStopReason.None, detector.Add([]));
        Assert.Equal(TimeSpan.Zero, detector.Duration);
        Assert.Equal(TimeSpan.Zero, detector.SilenceRun);
    }

    [Fact]
    public void TrailingOddByteIsIgnoredRatherThanReadAsASample()
    {
        var detector = Detector();

        // One whole quiet sample plus a stray byte. Read as a sample, 0xFF would pair with
        // nothing and could register as loud; ignored, the chunk stays silent throughout.
        detector.Add([0x0A, 0x00, 0xFF]);

        Assert.Equal(detector.Duration, detector.SilenceRun);
        Assert.True(detector.SilenceRun > TimeSpan.Zero);
    }

    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    public void NonPositiveLimitsAreRejected(int seconds)
    {
        var bad = TimeSpan.FromSeconds(seconds);

        Assert.Throws<ArgumentOutOfRangeException>(() => new SilenceDetector(bad, Cap));
        Assert.Throws<ArgumentOutOfRangeException>(() => new SilenceDetector(Timeout, bad));
    }
}
