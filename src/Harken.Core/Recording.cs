namespace Harken.Core;

/// <summary>
/// The captured audio of a Session, as a file (CONTEXT.md: Recording). Created on the
/// client, uploaded to the backend, and kept after transcription so a Session can be
/// re-transcribed by a better model or a different Provider later (ADR-0007, ADR-0008).
/// </summary>
public class Recording
{
    public Guid Id { get; set; }

    public Guid SessionId { get; set; }

    /// <summary>Path on backend storage, server-generated — never a client-supplied name.</summary>
    public string StoredPath { get; set; } = string.Empty;

    public long ByteLength { get; set; }

    public TimeSpan Duration { get; set; }

    public string ContentType { get; set; } = string.Empty;

    public DateTimeOffset UploadedAt { get; set; }
}
