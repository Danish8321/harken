using Microsoft.EntityFrameworkCore;
using Harken.Api.Data;
using Harken.Api.Speech;
using Harken.Core;

namespace Harken.Api.Transcription;

/// <summary>
/// Drains <see cref="TranscriptionJobChannel"/> one Session at a time — a single
/// background loop is what makes "one Whisper run at a time" true, not a lock, since
/// there is only ever one reader (slice-04 Task 6).
/// </summary>
public sealed partial class TranscriptionBackgroundService(
    TranscriptionJobChannel queue,
    IServiceScopeFactory scopeFactory,
    ILogger<TranscriptionBackgroundService> logger) : BackgroundService
{
    [LoggerMessage(Level = LogLevel.Error, Message = "Unhandled failure processing transcription job for session {SessionId}")]
    private partial void LogUnhandledFailure(Exception ex, Guid sessionId);

    [LoggerMessage(Level = LogLevel.Warning, Message = "Skipping transcription job for session {SessionId}: no Recording found")]
    private partial void LogNoRecording(Guid sessionId);


    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        await foreach (var sessionId in queue.ReadAllAsync(stoppingToken))
        {
            try
            {
                await ProcessAsync(sessionId, stoppingToken);
            }
            catch (Exception ex) when (ex is not OperationCanceledException)
            {
                // A job that throws outside ProcessAsync's own try/catch (e.g. failing to
                // even open a DB scope) must not take the whole drain loop down with it —
                // the next queued Session still deserves a chance to transcribe.
                LogUnhandledFailure(ex, sessionId);
            }
        }
    }

    private async Task ProcessAsync(Guid sessionId, CancellationToken ct)
    {
        using var scope = scopeFactory.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        var provider = scope.ServiceProvider.GetRequiredService<ITranscriptionProvider>();

        var session = await db.Sessions
            .Include(s => s.Recording)
            .FirstOrDefaultAsync(s => s.Id == sessionId, ct);

        if (session?.Recording is null)
        {
            LogNoRecording(sessionId);
            return;
        }

        session.TranscriptionStatus = Harken.Core.TranscriptionStatus.Running;
        await db.SaveChangesAsync(ct);

        try
        {
            // Collected in full before anything is persisted: if the provider throws
            // partway through, no partial Transcript ever lands in the store.
            var segments = await provider.TranscribeAsync(session.Recording.StoredPath, ct);

            foreach (var segment in segments)
            {
                db.TranscriptSegments.Add(new TranscriptSegment
                {
                    Id = Guid.NewGuid(),
                    SessionId = sessionId,
                    Text = segment.Text,
                    Offset = segment.Offset,
                    RecognizedAt = DateTimeOffset.UtcNow,
                });
            }

            session.TranscriptionStatus = Harken.Core.TranscriptionStatus.Succeeded;
            session.TranscriptionFailureReason = null;
            await db.SaveChangesAsync(ct);
        }
        catch (Exception ex) when (ex is not OperationCanceledException)
        {
            session.TranscriptionStatus = Harken.Core.TranscriptionStatus.Failed;
            session.TranscriptionFailureReason = ex.Message;
            await db.SaveChangesAsync(ct);
        }
    }
}
