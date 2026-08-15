using System.Threading.Channels;

namespace Harken.Api.Transcription;

/// <summary>
/// Hands Session ids from request handlers to <see cref="TranscriptionBackgroundService"/>.
/// A single unbounded channel with one reader serializes jobs by construction — Whisper
/// runs one file at a time because 4 GB of VRAM does not hold two (slice-04 Task 6).
/// </summary>
public sealed class TranscriptionJobChannel
{
    private readonly Channel<Guid> _channel = Channel.CreateUnbounded<Guid>();

    public void Enqueue(Guid sessionId) => _channel.Writer.TryWrite(sessionId);

    public IAsyncEnumerable<Guid> ReadAllAsync(CancellationToken ct) =>
        _channel.Reader.ReadAllAsync(ct);
}
