namespace Harken.Core;

public class Session
{
    public Guid Id { get; set; }

    /// <summary>Identity user id that owns this session.</summary>
    public string OwnerId { get; set; } = string.Empty;

    public DateTimeOffset StartedAt { get; set; }
    public DateTimeOffset? EndedAt { get; set; }
    public AudioSource Source { get; set; }
    public List<TranscriptSegment> Segments { get; set; } = new();
}
