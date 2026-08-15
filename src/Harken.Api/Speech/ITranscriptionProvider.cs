namespace Harken.Api.Speech;

/// <summary>
/// Transcribes a finished Recording. Replaces the streaming ISpeechTranscriber removed
/// with live captioning (ADR-0007) — a Provider now takes a whole file and returns
/// ordered segments, rather than owning a recognizer lifetime.
///
/// The backend declares which Providers exist by which are registered and available;
/// the caller selects among those (ADR-0008). A Provider that reports itself
/// unavailable must never be selected — checking availability is the caller's job, not
/// an afterthought, so an unconfigured Provider fails loudly at selection time instead
/// of throwing mid-transcription.
/// </summary>
public interface ITranscriptionProvider
{
    /// <summary>Stable identifier, e.g. "whisper-local" or "azure-batch". Used in config
    /// and in TranscriptionFailureReason messages, never shown untranslated to a User.</summary>
    string Name { get; }

    /// <summary>False when the Provider is registered but not usable right now — e.g. its
    /// model file is missing. Checked before every transcription attempt.</summary>
    bool IsAvailable { get; }

    /// <summary>
    /// Transcribes the audio at <paramref name="audioFilePath"/> and returns its segments
    /// in offset order. Language is set explicitly by the Provider's own configuration —
    /// never inherited from an SDK default (the one decision ADR-0006 keeps).
    /// </summary>
    Task<IReadOnlyList<TranscribedSegment>> TranscribeAsync(string audioFilePath, CancellationToken ct);
}
