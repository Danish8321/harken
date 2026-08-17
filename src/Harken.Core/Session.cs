namespace Harken.Core;

public class Session
{
    public Guid Id { get; set; }

    /// <summary>
    /// Id the client generated when it started recording, sent with the upload. Unique
    /// where present, so re-sending the same recording after a dropped connection returns
    /// the Session that already exists instead of creating a second one. Null for clients
    /// that do not send it — the console still does not.
    /// </summary>
    public Guid? ClientRecordingId { get; set; }

    public DateTimeOffset StartedAt { get; set; }
    public DateTimeOffset? EndedAt { get; set; }
    public AudioSource Source { get; set; }
    public List<TranscriptSegment> Segments { get; set; } = new();

    /// <summary>Null until the client uploads audio (ADR-0007: capture needs no auth,
    /// upload does — a Session can exist before it has a Recording).</summary>
    public Recording? Recording { get; set; }

    /// <summary>Null until a Recording exists; set to Pending on upload.</summary>
    public TranscriptionStatus? TranscriptionStatus { get; set; }

    /// <summary>Set only when TranscriptionStatus is Failed.</summary>
    public string? TranscriptionFailureReason { get; set; }
}
