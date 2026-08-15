namespace Harken.Core;

public class Session
{
    public Guid Id { get; set; }
    public DateTimeOffset StartedAt { get; set; }
    public DateTimeOffset? EndedAt { get; set; }
    public AudioSource Source { get; set; }
    public List<TranscriptSegment> Segments { get; set; } = new();
}
