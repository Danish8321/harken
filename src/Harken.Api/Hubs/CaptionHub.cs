using System.Collections.Concurrent;
using System.Diagnostics;
using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.SignalR;
using Harken.Api.Data;
using Harken.Core;
using Harken.Core.Contracts;
using Harken.Api.Speech;

namespace Harken.Api.Hubs;

[Authorize]
public class CaptionHub : Hub<ICaptionClient>
{
    private readonly IServiceScopeFactory _scopeFactory;
    private readonly ISpeechTranscriber _transcriber;

    public CaptionHub(IServiceScopeFactory scopeFactory, ISpeechTranscriber transcriber)
    {
        _scopeFactory = scopeFactory;
        _transcriber = transcriber;
    }

    public async Task StreamAudio(IAsyncEnumerable<byte[]> audioChunks, AudioSource source, CancellationToken ct)
    {
        // The Source is declared by the client, not assumed by the server — but it
        // arrives as an int, so an undefined value must be rejected rather than stored.
        if (!Enum.IsDefined(source))
        {
            throw new HubException($"Unknown audio source value '{(int)source}'.");
        }

        // Every Session must have an owner: an ownerless row would be unreachable by
        // every filtered read path and would sit outside the isolation model entirely.
        var ownerId = Context.User?.FindFirstValue(ClaimTypes.NameIdentifier);
        if (string.IsNullOrEmpty(ownerId))
        {
            throw new HubException("Authenticated user id is missing; cannot start a session.");
        }

        var session = new Session
        {
            Id = Guid.NewGuid(),
            OwnerId = ownerId,
            StartedAt = DateTimeOffset.UtcNow,
            Source = source,
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

        // Final-result saves start on Speech SDK callback threads, and the event returns
        // void so it cannot await them. Each save is tracked here instead, and drained
        // before the transcriber is disposed — otherwise the tail segments are still in
        // flight when the recognizer goes away and those writes are simply lost.
        var pendingSaves = new ConcurrentQueue<Task>();

        // Final results: persist a TranscriptSegment (fresh scope) and push a final caption.
        void OnFinal(string text) => pendingSaves.Enqueue(PersistFinalAsync(text));

        async Task PersistFinalAsync(string text)
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
                // CancellationToken.None deliberately: `ct` is already cancelled by the
                // time the last final results arrive (the user stopping the recording is
                // what cancels it), so honouring it here would discard exactly the tail
                // of the transcript this handler exists to persist.
                await db.SaveChangesAsync(CancellationToken.None);

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

            // StopAsync flushes the recognizer, but the saves those last final results
            // triggered are still running. Wait for them before marking the Session ended,
            // so EndedAt is not written while its own tail segments are still unwritten.
            await Task.WhenAll(pendingSaves);

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

            // Covers the paths that skipped the drain above (an exception mid-stream).
            // Already-completed tasks make this free on the normal path, and
            // PersistFinalAsync swallows its own exceptions, so this cannot throw here.
            await Task.WhenAll(pendingSaves);

            await _transcriber.DisposeAsync();
        }
    }
}
