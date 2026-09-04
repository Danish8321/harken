namespace Harken.Core.Audio;

/// <summary>Why a recording ended on its own, if it did.</summary>
public enum RecordingStopReason
{
    None = 0,

    /// <summary>Nothing above the amplitude threshold for the configured span.</summary>
    // ADR-0007's forgotten-recorder case: the room went quiet and stayed quiet.
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
    /// RMS level of a chunk below which it counts as silence. Room tone on a phone mic sits
    /// well under this; speech at conversational distance goes far above it.
    ///
    /// Measured, no longer guessed: over a 7m25s capture on a Nothing Phone 2 in an
    /// occupied room, chunk RMS ran p10 140 / p50 299 / p90 606, while chunk *peak* cleared
    /// 500 in 86% of chunks. Reading the peak made almost every chunk audible.
    /// </summary>
    public const short DefaultAmplitudeThreshold = 500;

    /// <summary>
    /// How much accumulated silence one unit of audible audio burns off.
    ///
    /// An audible chunk used to zero the run outright, which is why the timeout could not
    /// fire in a real room: on that same device capture the longest unbroken quiet run was
    /// 1.4s against the 300s needed, because isolated clicks — a desk knock, a chair —
    /// kept restarting it. Decaying instead of resetting keeps a 160ms transient cheap
    /// (it costs 1.6s of run) while a few seconds of speech still wipes the run entirely,
    /// which is the case that must never be cut off.
    /// </summary>
    public const int AudibleDecayFactor = 10;

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

    /// <summary>
    /// Length of the current run of silence. Audible audio burns it down at
    /// <see cref="AudibleDecayFactor"/> times its own length, so speech clears it and a
    /// stray transient only dents it.
    /// </summary>
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
            _silentBytes = Math.Max(0, _silentBytes - ((long)pcm.Length * AudibleDecayFactor));
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
        long sumOfSquares = 0;
        long samples = 0;

        // Whole 16-bit little-endian samples only. A trailing odd byte cannot be read as a
        // sample and is ignored rather than misread as a loud one.
        for (var i = 0; i + 1 < pcm.Length; i += 2)
        {
            long sample = (short)(pcm[i] | (pcm[i + 1] << 8));
            sumOfSquares += sample * sample;
            samples++;
        }

        // The level of the chunk, not its loudest sample: one click in an otherwise quiet
        // 160ms of room tone is not someone talking, and reading the peak let that click
        // speak for the whole chunk.
        //
        // Compared as mean square against the squared threshold — no sqrt, and no overflow:
        // the loudest possible chunk sums 32768² per sample, which stays inside long for
        // any chunk AudioRecord hands over.
        return samples == 0 || sumOfSquares < (long)_amplitudeThreshold * _amplitudeThreshold * samples;
    }

    private static TimeSpan ToDuration(long bytes)
    {
        const int bytesPerSecond = WavWriter.SampleRate * WavWriter.Channels * (WavWriter.BitsPerSample / 8);
        return TimeSpan.FromSeconds(bytes / (double)bytesPerSecond);
    }
}
