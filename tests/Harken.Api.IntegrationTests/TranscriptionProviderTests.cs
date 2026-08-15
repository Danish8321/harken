using Harken.Api.Speech;
using Xunit;

namespace Harken.Api.IntegrationTests;

/// <summary>
/// Exercises the ITranscriptionProvider seam itself, not any real Provider — Whisper and
/// Azure batch each get their own tests when they land (slice 04 Task 4 / MVP 2).
/// </summary>
public class TranscriptionProviderTests
{
    private sealed class FakeProvider : ITranscriptionProvider
    {
        private readonly IReadOnlyList<TranscribedSegment> _segments;

        public FakeProvider(string name, bool isAvailable, IReadOnlyList<TranscribedSegment> segments)
        {
            Name = name;
            IsAvailable = isAvailable;
            _segments = segments;
        }

        public string Name { get; }
        public bool IsAvailable { get; }

        public Task<IReadOnlyList<TranscribedSegment>> TranscribeAsync(string audioFilePath, CancellationToken ct)
            => Task.FromResult(_segments);
    }

    [Fact]
    public async Task TranscribeAsyncReturnsSegmentsInOffsetOrder()
    {
        // The fake returns them already ordered — this proves the seam preserves order,
        // not that any real Provider produces it; a real Provider's own ordering
        // guarantee (or lack of one) is that Provider's test to write.
        var segments = new List<TranscribedSegment>
        {
            new(TimeSpan.Zero, "Hello"),
            new(TimeSpan.FromSeconds(3), "world"),
            new(TimeSpan.FromSeconds(7), "again"),
        };
        var provider = new FakeProvider("fake", isAvailable: true, segments);

        var result = await provider.TranscribeAsync("irrelevant.wav", CancellationToken.None);

        Assert.Equal(segments, result);
        Assert.True(result[0].Offset < result[1].Offset);
        Assert.True(result[1].Offset < result[2].Offset);
    }

    [Fact]
    public void UnavailableProviderIsNeverSelected()
    {
        // Mirrors the selection rule the job runner (Task 6) must apply: never hand work
        // to a Provider reporting itself unavailable, e.g. because its model file is
        // missing.
        var providers = new List<ITranscriptionProvider>
        {
            new FakeProvider("azure-batch", isAvailable: false, []),
            new FakeProvider("whisper-local", isAvailable: true, []),
        };

        var selected = providers.FirstOrDefault(p => p.IsAvailable);

        Assert.NotNull(selected);
        Assert.Equal("whisper-local", selected.Name);
    }

    [Fact]
    public void NoAvailableProviderYieldsNoSelection()
    {
        var providers = new List<ITranscriptionProvider>
        {
            new FakeProvider("azure-batch", isAvailable: false, []),
            new FakeProvider("whisper-local", isAvailable: false, []),
        };

        var selected = providers.FirstOrDefault(p => p.IsAvailable);

        Assert.Null(selected);
    }
}
