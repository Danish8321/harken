using Harken.Api.Speech;
using Xunit;

namespace Harken.Api.IntegrationTests;

/// <summary>
/// Real transcription is not build-provable — it needs an actual model file and GPU, and
/// is the manual step recorded in the slice notes (Task 7). This covers only what a gate
/// can prove: the Provider reports itself unavailable rather than crashing when
/// unconfigured, per ITranscriptionProvider's contract.
/// </summary>
public class WhisperTranscriptionProviderTests
{
    [Fact]
    public void IsAvailableIsFalseWhenModelPathIsEmpty()
    {
        var provider = new WhisperTranscriptionProvider(new WhisperOptions("", "en"));

        Assert.False(provider.IsAvailable);
    }

    [Fact]
    public void IsAvailableIsFalseWhenModelFileDoesNotExist()
    {
        var missingPath = Path.Combine(Path.GetTempPath(), $"harken-no-such-model-{Guid.NewGuid()}.bin");

        var provider = new WhisperTranscriptionProvider(new WhisperOptions(missingPath, "en"));

        Assert.False(provider.IsAvailable);
    }

    [Fact]
    public async Task TranscribeAsyncThrowsRatherThanSilentlyNoOpWhenUnavailable()
    {
        // A caller that skips the IsAvailable check (contrary to the seam's contract)
        // must still fail loudly, not return an empty transcript that looks like a
        // silent Recording.
        var provider = new WhisperTranscriptionProvider(new WhisperOptions("", "en"));

        await Assert.ThrowsAsync<InvalidOperationException>(
            () => provider.TranscribeAsync("irrelevant.wav", CancellationToken.None));
    }
}
