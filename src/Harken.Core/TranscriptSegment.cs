namespace Harken.Core;

public class TranscriptSegment
{
    public Guid Id { get; set; }
    public Guid SessionId { get; set; }
    public string Text { get; set; } = string.Empty;
    public TimeSpan Offset { get; set; }
    public DateTimeOffset RecognizedAt { get; set; }
}
