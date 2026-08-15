namespace Harken.Api.Speech;

/// <summary>
/// One piece of recognized text with its offset into the Recording, as a Provider
/// returns it. Distinct from <see cref="Harken.Core.TranscriptSegment"/>, which is the
/// persisted row — this is provider output before it has a SessionId or an identity.
/// </summary>
public sealed record TranscribedSegment(TimeSpan Offset, string Text);
