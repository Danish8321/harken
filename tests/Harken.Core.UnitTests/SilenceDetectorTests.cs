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
    public void AudibleChunkResetsTheSilenceRun()
    {
        // The whole point of a *run*: a user who pauses to think mid-sentence must not have
        // their recording ended out from under them.
        var detector = Detector();

        detector.Add(Quiet(TimeSpan.FromSeconds(4)));
        detector.Add(Loud(TimeSpan.FromSeconds(1)));

        Assert.Equal(TimeSpan.Zero, detector.SilenceRun);
        Assert.Equal(RecordingStopReason.None, detector.Add(Quiet(TimeSpan.FromSeconds(4))));
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
