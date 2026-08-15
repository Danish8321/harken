namespace Harken.Core.Contracts;

/// <summary>
/// Metadata for one Session in the caller's list. Deliberately carries no transcript
/// text — the list is a browsing surface, not a bulk export.
/// </summary>
public record SessionListItem(
    Guid Id,
    DateTimeOffset StartedAt,
    DateTimeOffset? EndedAt,
    AudioSource Source,
    int SegmentCount,
    bool HasSummary);
