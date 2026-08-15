using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Harken.Api.Data;
using Harken.Api.Hubs;
using Harken.Api.Speech;
using Harken.Core;
using Harken.Core.Contracts;
using Xunit;

namespace Harken.Api.IntegrationTests;

/// <summary>
/// The last final results arrive while the recognizer is stopping. Their saves start on
/// callback threads and nothing in the event signature can await them, so the stream must
/// drain them itself — otherwise the tail of every transcript is silently lost.
/// </summary>
public class TranscriptTailTests : IClassFixture<CustomWebApplicationFactory>
{
    private readonly CustomWebApplicationFactory _factory;

    public TranscriptTailTests(CustomWebApplicationFactory factory)
    {
        _factory = factory;
    }

    [Fact]
    public async Task FinalResultsRaisedDuringStopArePersisted()
    {
        var (_, userId) = await _factory.CreateAuthenticatedClientAsync();
        var scopeFactory = _factory.Services.GetRequiredService<IServiceScopeFactory>();
        var transcriber = new StopEmittingTranscriber("the last thing said");

        // The handler's slowest step is pushing the caption to the client, and it runs
        // after the save. Making it slow is what makes an undrained handler observable:
        // asserting on the row alone passes either way, because the write itself is fast
        // enough to land before the assertion by luck rather than by the drain.
        var slowClient = new SlowCaptionClient(TimeSpan.FromMilliseconds(300));

        var hub = new CaptionHub(scopeFactory, transcriber)
        {
            Context = new FakeHubCallerContext(userId),
            Clients = new FakeHubCallerClients(slowClient),
        };

        await hub.StreamAudio(EmptyChunks(), AudioSource.Microphone, CancellationToken.None);

        // StreamAudio has returned, so the handler must have finished — not still be in
        // flight against a transcriber that is already disposed.
        Assert.True(slowClient.FinalCaptionCompleted);

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        var session = await db.Sessions.AsNoTracking().SingleAsync(s => s.OwnerId == userId);
        var segments = await db.TranscriptSegments.AsNoTracking()
            .Where(t => t.SessionId == session.Id)
            .ToListAsync();

        Assert.Equal("the last thing said", Assert.Single(segments).Text);
    }

    /// <summary>
    /// Takes a measurable amount of time to accept a final caption, and records whether
    /// that ever finished. If the stream does not drain its handlers, this flag is still
    /// false when StreamAudio returns.
    /// </summary>
    private sealed class SlowCaptionClient : ICaptionClient
    {
        private readonly TimeSpan _delay;

        public SlowCaptionClient(TimeSpan delay) => _delay = delay;

        public bool FinalCaptionCompleted { get; private set; }

        public async Task ReceiveCaption(CaptionUpdate update)
        {
            await Task.Delay(_delay);

            if (update.IsFinal)
            {
                FinalCaptionCompleted = true;
            }
        }

        public Task SessionStarted(Guid sessionId) => Task.CompletedTask;
    }

    private static async IAsyncEnumerable<byte[]> EmptyChunks()
    {
        await Task.CompletedTask;
        yield break;
    }

    /// <summary>
    /// Emits one final result from inside <c>StopAsync</c>, reproducing the real timing:
    /// the Speech SDK flushes its last recognition as the recognizer shuts down. The
    /// delay widens the window so a missing drain fails rather than passing by luck.
    /// </summary>
    private sealed class StopEmittingTranscriber : ISpeechTranscriber
    {
        private readonly string _finalText;

        public StopEmittingTranscriber(string finalText) => _finalText = finalText;

        public event Action<string>? PartialRecognized;

        public event Action<string>? FinalRecognized;

        public Task StartAsync(CancellationToken ct) => Task.CompletedTask;

        public void PushAudio(ReadOnlyMemory<byte> chunk) { }

        public async Task StopAsync()
        {
            await Task.Delay(20);
            FinalRecognized?.Invoke(_finalText);
        }

        public ValueTask DisposeAsync()
        {
            // Silences the unused-event warning: the real transcriber raises this one.
            PartialRecognized?.Invoke(string.Empty);
            return ValueTask.CompletedTask;
        }
    }
}
