namespace Harken.Core.Contracts;

/// <summary>One Session with its ordered Transcript Segments and stored Summary, if any.</summary>
public record SessionDetail(
    Guid Id,
    DateTimeOffset StartedAt,
    DateTimeOffset? EndedAt,
    AudioSource Source,
    IReadOnlyList<TranscriptSegmentView> Segments,
    SessionSummary? Summary,
    TranscriptionStatus? TranscriptionStatus,
    string? TranscriptionFailureReason);

/// <summary>A single stored Transcript Segment as returned to a client.</summary>
public record TranscriptSegmentView(
    Guid Id,
    TimeSpan Offset,
    string Text,
    DateTimeOffset RecognizedAt);
