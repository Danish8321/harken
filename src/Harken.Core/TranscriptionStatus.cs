namespace Harken.Core;

/// <summary>
/// A Session's transcription state. Null on <see cref="Session.TranscriptionStatus"/>
/// means no Recording has been uploaded yet — there is nothing to transcribe.
/// </summary>
public enum TranscriptionStatus
{
    Pending,
    Running,
    Succeeded,
    Failed,
}
