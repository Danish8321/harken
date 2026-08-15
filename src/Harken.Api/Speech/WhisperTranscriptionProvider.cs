using Whisper.net;

namespace Harken.Api.Speech;

/// <summary>
/// Local transcription via Whisper.net / whisper.cpp (ADR-0008): the only MVP 1
/// Provider, no cloud account, no meter. IsAvailable reports false rather than throwing
/// when the model file is missing — a clear "not configured" beats a crash on first use.
/// </summary>
public sealed class WhisperTranscriptionProvider : ITranscriptionProvider
{
    private readonly WhisperOptions _options;

    public WhisperTranscriptionProvider(WhisperOptions options)
    {
        _options = options;
    }

    public string Name => "whisper-local";

    public bool IsAvailable =>
        !string.IsNullOrWhiteSpace(_options.ModelPath) && File.Exists(_options.ModelPath);

    public async Task<IReadOnlyList<TranscribedSegment>> TranscribeAsync(string audioFilePath, CancellationToken ct)
    {
        if (!IsAvailable)
        {
            // Callers are expected to check IsAvailable before selecting this Provider
            // (ITranscriptionProvider's contract); this is the loud failure for a caller
            // that skipped that check, not the primary configuration signal.
            throw new InvalidOperationException(
                $"Whisper model not found at '{_options.ModelPath}'. Set Whisper:ModelPath to a valid GGML model file.");
        }

        using var factory = WhisperFactory.FromPath(_options.ModelPath);
        using var processor = factory.CreateBuilder()
            .WithLanguage(_options.Language)
            .Build();

        await using var audio = File.OpenRead(audioFilePath);

        var segments = new List<TranscribedSegment>();
        await foreach (var segment in processor.ProcessAsync(audio, ct))
        {
            segments.Add(new TranscribedSegment(segment.Start, segment.Text.Trim()));
        }

        return segments;
    }
}
