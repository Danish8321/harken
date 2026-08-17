namespace Harken.Core.Audio;

/// <summary>Why a recording ended on its own, if it did.</summary>
public enum RecordingStopReason
{
    None = 0,

    /// <summary>Nothing above the amplitude threshold for the configured span.</summary>
    SilenceTimeout,

    /// <summary>The recording reached its maximum length.</summary>
    SessionCap,
}

/// <summary>
/// Watches the PCM going into a recording and reports when it should stop on its own —
/// after a run of silence, or at the session cap. ADR-0007 moved both limits from the
/// server to the client, where they bound battery and storage rather than spend.
///
/// Deliberately driven by the audio itself rather than by a timer: at 16 kHz/16-bit/mono
/// the byte count *is* the elapsed time, so this stays pure and testable, and a stalled
/// capture cannot age the recording out while no audio is arriving.
/// </summary>
public sealed class SilenceDetector
{
    /// <summary>
    /// Peak 16-bit sample below which a chunk counts as silence. Room tone on a phone mic
    /// sits well under this; speech at conversational distance goes far above it. A guess
    /// against real rooms, not a measurement — worth revisiting with a device recording.
    /// </summary>
    public const short DefaultAmplitudeThreshold = 500;

    private readonly TimeSpan _silenceTimeout;
    private readonly TimeSpan _sessionCap;
    private readonly short _amplitudeThreshold;

    private long _totalBytes;
    private long _silentBytes;

    public SilenceDetector(
        TimeSpan silenceTimeout,
        TimeSpan sessionCap,
        short amplitudeThreshold = DefaultAmplitudeThreshold)
    {
        if (silenceTimeout <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(silenceTimeout), "Silence timeout must be positive.");
        }
        if (sessionCap <= TimeSpan.Zero)
        {
            throw new ArgumentOutOfRangeException(nameof(sessionCap), "Session cap must be positive.");
        }
        if (amplitudeThreshold < 0)
        {
            throw new ArgumentOutOfRangeException(nameof(amplitudeThreshold), "Threshold must not be negative.");
        }

        _silenceTimeout = silenceTimeout;
        _sessionCap = sessionCap;
        _amplitudeThreshold = amplitudeThreshold;
    }

    /// <summary>Audio observed so far, derived from the byte count.</summary>
    public TimeSpan Duration => ToDuration(_totalBytes);

    /// <summary>Length of the current unbroken run of silence. Reset by any audible chunk.</summary>
    public TimeSpan SilenceRun => ToDuration(_silentBytes);

    /// <summary>
    /// Feeds one chunk of PCM and reports whether the recording should now stop. Chunk
    /// boundaries do not matter: silence is measured in bytes, not in chunks.
    /// </summary>
    public RecordingStopReason Add(ReadOnlySpan<byte> pcm)
    {
        _totalBytes += pcm.Length;

        if (IsSilent(pcm))
        {
            _silentBytes += pcm.Length;
        }
        else
        {
            _silentBytes = 0;
        }

        // The cap is checked first: a recording that hits both limits in the same chunk ran
        // its full length, which is the more informative reason to report.
        if (Duration >= _sessionCap)
        {
            return RecordingStopReason.SessionCap;
        }

        return SilenceRun >= _silenceTimeout ? RecordingStopReason.SilenceTimeout : RecordingStopReason.None;
    }

    private bool IsSilent(ReadOnlySpan<byte> pcm)
    {
        // Whole 16-bit little-endian samples only. A trailing odd byte cannot be read as a
        // sample and is ignored rather than misread as a loud one.
        for (var i = 0; i + 1 < pcm.Length; i += 2)
        {
            var sample = (short)(pcm[i] | (pcm[i + 1] << 8));

            // Negated separately: -32768 has no positive counterpart, so Math.Abs overflows.
            var magnitude = sample == short.MinValue ? short.MaxValue : Math.Abs(sample);
            if (magnitude >= _amplitudeThreshold)
            {
                return false;
            }
        }

        return true;
    }

    private static TimeSpan ToDuration(long bytes)
    {
        const int bytesPerSecond = WavWriter.SampleRate * WavWriter.Channels * (WavWriter.BitsPerSample / 8);
        return TimeSpan.FromSeconds(bytes / (double)bytesPerSecond);
    }
}
