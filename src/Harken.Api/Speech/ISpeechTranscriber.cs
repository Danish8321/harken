namespace Harken.Api.Speech;

/// <summary>
/// A single recognizer's lifetime, one per Session. Emits Partial Results (live,
/// not persisted) and Final Results (stabilized Transcript Segments) as distinct events.
/// </summary>
public interface ISpeechTranscriber : IAsyncDisposable
{
    /// <summary>An in-progress recognition; flickers as more audio arrives.</summary>
    event Action<string>? PartialRecognized;

    /// <summary>A stabilized recognition the engine will not revise.</summary>
    event Action<string>? FinalRecognized;

    /// <summary>Begins continuous recognition.</summary>
    Task StartAsync(CancellationToken ct);

    /// <summary>Pushes a 16 kHz / 16-bit / mono PCM chunk into the stream.</summary>
    void PushAudio(ReadOnlyMemory<byte> chunk);

    /// <summary>Stops recognition and flushes.</summary>
    Task StopAsync();
}
