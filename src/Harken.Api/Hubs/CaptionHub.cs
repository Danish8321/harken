using System.Diagnostics;
using Microsoft.AspNetCore.SignalR;
using Harken.Api.Data;
using Harken.Core;
using Harken.Core.Contracts;
using Harken.Api.Speech;

namespace Harken.Api.Hubs;

public class CaptionHub : Hub<ICaptionClient>
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ISpeechTranscriber _transcriber;

    public CaptionHub(IServiceScopeFactory scopeFactory, ISpeechTranscriber transcriber)
    {
        _scopeFactory = scopeFactory;
        _transcriber = transcriber;
    }

    public async Task StreamAudio(IAsyncEnumerable<byte[]> audioChunks, CancellationToken ct)
    {
        var session = new Session
        {
            Id = Guid.NewGuid(),
            StartedAt = DateTimeOffset.UtcNow,
            Source = AudioSource.Microphone,
        };

        // Initial Session insert — its own scope + DbContext.
        await using (var scope = _scopeFactory.CreateAsyncScope())
        {
            var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
            db.Sessions.Add(session);
            await db.SaveChangesAsync(ct);
        }

        var caller = Clients.Caller;
        await caller.SessionStarted(session.Id);

        var stopwatch = Stopwatch.StartNew();

        // Partial results: live captions, not persisted. Fire-and-forget push.
        void OnPartial(string text)
        {
            try
            {
                var elapsed = stopwatch.Elapsed;
                _ = caller.ReceiveCaption(new CaptionUpdate(text, false, elapsed));
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"PartialRecognized handler failed: {ex}");
            }
        }

        // Final results: persist a TranscriptSegment (fresh scope) and push a final caption.
        async void OnFinal(string text)
        {
            try
            {
                var elapsed = stopwatch.Elapsed;
                var segment = new TranscriptSegment
                {
                    Id = Guid.NewGuid(),
                    SessionId = session.Id,
                    Text = text,
                    Offset = elapsed,
                    RecognizedAt = DateTimeOffset.UtcNow,
                };

                await using var scope = _scopeFactory.CreateAsyncScope();
                var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
                db.TranscriptSegments.Add(segment);
                await db.SaveChangesAsync(ct);

                await caller.ReceiveCaption(new CaptionUpdate(text, true, elapsed));
            }
            catch (Exception ex)
            {
                Console.Error.WriteLine($"FinalRecognized handler failed: {ex}");
            }
        }

        _transcriber.PartialRecognized += OnPartial;
        _transcriber.FinalRecognized += OnFinal;

        try
        {
            await _transcriber.StartAsync(ct);

            try
            {
                await foreach (var chunk in audioChunks.WithCancellation(ct))
                {
                    _transcriber.PushAudio(chunk);
                }
            }
            catch (OperationCanceledException)
            {
                // Client cancelled — fall through to graceful stop.
            }

            await _transcriber.StopAsync();

            session.EndedAt = DateTimeOffset.UtcNow;

            // Final EndedAt update — its own scope + DbContext.
            await using var scope = _scopeFactory.CreateAsyncScope();
            var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
            db.Sessions.Update(session);
            await db.SaveChangesAsync(CancellationToken.None);
        }
        finally
        {
            _transcriber.PartialRecognized -= OnPartial;
            _transcriber.FinalRecognized -= OnFinal;
            await _transcriber.DisposeAsync();
        }
    }
}
