using System.Net.Http.Headers;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Harken.Api.Speech;
using Harken.Api.Transcription;
using Harken.Core.Contracts;
using Xunit;

namespace Harken.Api.IntegrationTests;

/// <summary>
/// Exercises the background transcription job (slice-04 Task 6) end to end through the
/// real HTTP surface: upload, poll, and read back the result — with a fake Provider
/// standing in for Whisper so these tests run without a model file.
///
/// Each test builds its own <see cref="CustomWebApplicationFactory"/> (own in-memory
/// SQLite connection) rather than sharing one via IClassFixture: with a real background
/// reader now attached, sharing a connection across tests risks two DbContexts touching
/// the same SqliteConnection concurrently, which is not supported and surfaces as flaky
/// 500s.
/// </summary>
public class TranscriptionJobTests
{
    private sealed class FakeProvider(IReadOnlyList<TranscribedSegment>? segments, Exception? failure) : ITranscriptionProvider
    {
        public string Name => "fake";
        public bool IsAvailable => true;

        public Task<IReadOnlyList<TranscribedSegment>> TranscribeAsync(string audioFilePath, CancellationToken ct)
            => failure is not null
                ? Task.FromException<IReadOnlyList<TranscribedSegment>>(failure)
                : Task.FromResult(segments ?? []);
    }

    private static WebApplicationFactory<Program> WithProvider(ITranscriptionProvider provider) =>
        new CustomWebApplicationFactory().WithWebHostBuilder(builder => builder.ConfigureServices(services =>
        {
            services.RemoveAll<ITranscriptionProvider>();
            services.AddSingleton(provider);
        }));

    private static MultipartFormDataContent BuildUpload(byte[] audioBytes)
    {
        var content = new MultipartFormDataContent();
        var audioContent = new ByteArrayContent(audioBytes);
        audioContent.Headers.ContentType = new MediaTypeHeaderValue("audio/wav");
        content.Add(audioContent, "audio", "recording.wav");
        content.Add(new StringContent("Microphone"), "source");
        return content;
    }

    private static async Task<SessionDetail> PollUntilTerminalAsync(HttpClient client, Guid sessionId)
    {
        // The client polls GET /sessions/{id} for status (slice-04 decision 3: no push,
        // no connection held open across a job that can take minutes) — this mirrors that
        // exact contract rather than reaching into the DB directly.
        var deadline = DateTime.UtcNow.AddSeconds(10);
        while (DateTime.UtcNow < deadline)
        {
            var detail = await client.GetFromJsonAsync<SessionDetail>($"/sessions/{sessionId}");
            if (detail is not null &&
                detail.TranscriptionStatus is Harken.Core.TranscriptionStatus.Succeeded
                    or Harken.Core.TranscriptionStatus.Failed)
            {
                return detail;
            }
            await Task.Delay(50);
        }
        throw new TimeoutException($"Session {sessionId} never reached a terminal transcription status.");
    }

    [Fact]
    public async Task StatusReachesSucceededAndSegmentsLandInOrder()
    {
        var segments = new List<TranscribedSegment>
        {
            new(TimeSpan.Zero, "Hello"),
            new(TimeSpan.FromSeconds(3), "world"),
        };
        using var factory = WithProvider(new FakeProvider(segments, failure: null));
        var client = factory.CreateClient();

        var upload = await client.PostAsync("/sessions", BuildUpload([1, 2, 3]));
        upload.EnsureSuccessStatusCode();
        var created = await upload.Content.ReadFromJsonAsync<SessionListItem>();
        Assert.NotNull(created);

        var detail = await PollUntilTerminalAsync(client, created!.Id);

        Assert.Equal(Harken.Core.TranscriptionStatus.Succeeded, detail.TranscriptionStatus);
        Assert.Null(detail.TranscriptionFailureReason);
        Assert.Equal(2, detail.Segments.Count);
        Assert.Equal("Hello", detail.Segments[0].Text);
        Assert.Equal("world", detail.Segments[1].Text);
        Assert.True(detail.Segments[0].Offset < detail.Segments[1].Offset);
    }

    [Fact]
    public async Task ThrowingProviderYieldsFailedWithReasonAndNoPartialTranscript()
    {
        using var factory = WithProvider(new FakeProvider(segments: null, failure: new InvalidOperationException("model exploded")));
        var client = factory.CreateClient();

        var upload = await client.PostAsync("/sessions", BuildUpload([1, 2, 3]));
        upload.EnsureSuccessStatusCode();
        var created = await upload.Content.ReadFromJsonAsync<SessionListItem>();
        Assert.NotNull(created);

        var detail = await PollUntilTerminalAsync(client, created!.Id);

        Assert.Equal(Harken.Core.TranscriptionStatus.Failed, detail.TranscriptionStatus);
        Assert.Equal("model exploded", detail.TranscriptionFailureReason);
        Assert.Empty(detail.Segments);
    }

    [Fact]
    public async Task PollingAnUnknownSessionGetsNotFound()
    {
        using var factory = WithProvider(new FakeProvider(segments: [], failure: null));
        var client = factory.CreateClient();

        var response = await client.GetAsync($"/sessions/{Guid.NewGuid()}");
        var body = await response.Content.ReadAsStringAsync();

        Assert.True(response.StatusCode == System.Net.HttpStatusCode.NotFound, $"Status {(int)response.StatusCode}: {body}");
    }
}
