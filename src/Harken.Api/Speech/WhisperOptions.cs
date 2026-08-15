namespace Harken.Api.Speech;

/// <summary>
/// Config for the local Whisper provider (ADR-0008). ModelPath and Language are both
/// settings, not code — swapping "base" for "medium", or the language, is a restart.
/// </summary>
public sealed record WhisperOptions(string ModelPath, string Language);
