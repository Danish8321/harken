using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.AI;
using OllamaSharp;
using Harken.Api.Agents;
using Harken.Api.Data;
using Harken.Api.Speech;
using Harken.Api.Storage;
using Harken.Api.Transcription;
using Harken.Core;
using Harken.Core.Contracts;

// 500 MB: comfortably above a multi-hour WAV recording (~115 MB/hour, decision 1 in
// slice-04-record-then-transcribe.md) with headroom, while still bounding a runaway or
// malicious upload rather than accepting an unbounded body. Kestrel's own default
// (~28.6 MB) is well under a real recording, so it has to move too — checking file.Length
// in the handler alone would never even run for anything real.
const long MaxUploadBytes = 500L * 1024 * 1024;

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.ConfigureKestrel(o => o.Limits.MaxRequestBodySize = MaxUploadBytes);

var connectionString = builder.Configuration.GetConnectionString("Harken")
    ?? "Data Source=harken.db";

builder.Services.AddDbContext<HarkenDbContext>(o => o.UseSqlite(connectionString));

// Transcription provider seam (ADR-0008): MVP 1 registers local Whisper only. MVP 2
// adds Azure batch as a second registration; whichever reports IsAvailable is what the
// backend can actually offer.
var whisperSection = builder.Configuration.GetSection("Whisper");
var whisperOptions = new WhisperOptions(
    whisperSection["ModelPath"] ?? "",
    whisperSection["Language"] ?? "en");
builder.Services.AddSingleton(whisperOptions);
builder.Services.AddSingleton<ITranscriptionProvider, WhisperTranscriptionProvider>();

// Provider seam: only this registration changes when swapping to Azure Foundry.
var ollamaEndpoint = builder.Configuration["Ollama:Endpoint"]
    ?? Environment.GetEnvironmentVariable("OLLAMA_ENDPOINT")
    ?? "http://localhost:11434";
var ollamaModel = builder.Configuration["Ollama:Model"]
    ?? Environment.GetEnvironmentVariable("OLLAMA_MODEL_NAME")
    ?? "gemma3:4b";
builder.Services.AddKeyedSingleton<IChatClient>("chat-model", (_, _) =>
    new OllamaApiClient(ollamaEndpoint, ollamaModel));

builder.Services.AddScoped<SummarizeAgent>();

var recordingsPath = builder.Configuration["Storage:RecordingsPath"] ?? "recordings";
builder.Services.AddSingleton(new RecordingStorage(
    Path.IsPathRooted(recordingsPath) ? recordingsPath : Path.Combine(builder.Environment.ContentRootPath, recordingsPath)));

// Task 6: one background reader drains the queue, so exactly one Whisper run happens at
// a time regardless of how many uploads arrive concurrently.
builder.Services.AddSingleton<TranscriptionJobChannel>();
builder.Services.AddHostedService<TranscriptionBackgroundService>();

var app = builder.Build();

// ADR-0009: MVP 1 has no authentication and no ownership model. One implicit user,
// reachable by anything on the LAN that can route to the host.
app.MapGet("/health", () => Results.Ok(new { status = "ok" }));

app.MapPost("/sessions", async (
    HttpRequest request,
    HarkenDbContext db,
    RecordingStorage storage,
    TranscriptionJobChannel jobs,
    CancellationToken ct) =>
{
    if (!request.HasFormContentType)
    {
        return Results.BadRequest("Expected multipart/form-data with an audio file.");
    }

    var form = await request.ReadFormAsync(ct);
    var file = form.Files["audio"];
    if (file is null || file.Length == 0)
    {
        return Results.BadRequest("No audio file provided (expected form field 'audio').");
    }

    // WAV only (slice-04 decision 1: what the console records, what Whisper wants
    // natively). Not a client-supplied filename check — that never reaches the
    // filesystem — this is purely "did we get audio we can transcribe".
    if (!string.Equals(file.ContentType, "audio/wav", StringComparison.OrdinalIgnoreCase)
        && !string.Equals(file.ContentType, "audio/x-wav", StringComparison.OrdinalIgnoreCase))
    {
        return Results.BadRequest($"Unsupported content type '{file.ContentType}'. Expected audio/wav.");
    }

    if (file.Length > MaxUploadBytes)
    {
        return Results.StatusCode(StatusCodes.Status413PayloadTooLarge);
    }

    var sourceValue = form["source"].ToString();
    if (!Enum.TryParse<AudioSource>(sourceValue, ignoreCase: true, out var source)
        || !Enum.IsDefined(source))
    {
        return Results.BadRequest($"Missing or invalid 'source' (expected one of: {string.Join(", ", Enum.GetNames<AudioSource>())}).");
    }

    // Optional: clients that generate a recording id get idempotent retries, clients that
    // do not (the console) keep the old behavior. An unparseable value is a client bug
    // worth reporting rather than silently treating as absent.
    Guid? clientRecordingId = null;
    var clientRecordingIdValue = form["recordingId"].ToString();
    if (!string.IsNullOrEmpty(clientRecordingIdValue))
    {
        if (!Guid.TryParse(clientRecordingIdValue, out var parsed))
        {
            return Results.BadRequest($"Invalid 'recordingId' — expected a GUID, got '{clientRecordingIdValue}'.");
        }

        clientRecordingId = parsed;

        // A phone on a flaky connection retries uploads routinely (slice-04 carried this
        // forward). Returning the Session that already exists is what keeps one recording
        // from becoming several, and the client cannot tell a lost response from a lost
        // request, so it has to be safe to just send again.
        var existing = await db.Sessions
            .AsNoTracking()
            .Include(s => s.Segments)
            .SingleOrDefaultAsync(s => s.ClientRecordingId == parsed, ct);

        if (existing is not null)
        {
            return Results.Ok(new SessionListItem(
                existing.Id,
                existing.StartedAt,
                existing.EndedAt,
                existing.Source,
                existing.Segments.Count,
                await db.StoredSummaries.AnyAsync(x => x.SessionId == existing.Id, ct)));
        }
    }

    var session = new Session
    {
        Id = Guid.NewGuid(),
        ClientRecordingId = clientRecordingId,
        StartedAt = DateTimeOffset.UtcNow,
        EndedAt = DateTimeOffset.UtcNow,
        Source = source,
    };

    // The stored filename comes from the Session's own server-generated id, never from
    // the client — RecordingStorage does not accept one.
    await using (var stream = file.OpenReadStream())
    {
        var storedPath = await storage.SaveAsync(session.Id, stream, ct);

        session.Recording = new Recording
        {
            Id = Guid.NewGuid(),
            SessionId = session.Id,
            StoredPath = storedPath,
            ByteLength = file.Length,
            ContentType = file.ContentType,
            UploadedAt = DateTimeOffset.UtcNow,
            // Duration isn't read from the WAV header here — Task 6 (or a later pass)
            // can derive it from the file itself. Zero, not a guess.
            Duration = TimeSpan.Zero,
        };
    }

    session.TranscriptionStatus = Harken.Core.TranscriptionStatus.Pending;

    db.Sessions.Add(session);

    try
    {
        await db.SaveChangesAsync(ct);
    }
    catch (DbUpdateException) when (clientRecordingId is not null)
    {
        // Two retries of the same upload can both miss the lookup above and race to insert.
        // The unique index is what actually enforces one Session per recording; this turns
        // its error back into the same answer the earlier caller got.
        db.ChangeTracker.Clear();

        var winner = await db.Sessions
            .AsNoTracking()
            .Include(s => s.Segments)
            .SingleOrDefaultAsync(s => s.ClientRecordingId == clientRecordingId, ct);

        if (winner is null)
        {
            throw;
        }

        return Results.Ok(new SessionListItem(
            winner.Id,
            winner.StartedAt,
            winner.EndedAt,
            winner.Source,
            winner.Segments.Count,
            await db.StoredSummaries.AnyAsync(x => x.SessionId == winner.Id, ct)));
    }

    // Enqueued after the commit: a job reading the id back out of the DB must be able to
    // find the Session it's for.
    jobs.Enqueue(session.Id);

    return Results.Created($"/sessions/{session.Id}", new SessionListItem(
        session.Id,
        session.StartedAt,
        session.EndedAt,
        session.Source,
        SegmentCount: 0,
        HasSummary: false));
});

app.MapGet("/sessions", async (
    HarkenDbContext db,
    CancellationToken ct) =>
{
    // Metadata only: no transcript text leaves this endpoint.
    // Materialized then ordered client-side: SQLite can't translate ORDER BY on a
    // DateTimeOffset column (the same limitation that bit us on TimeSpan in
    // SummarizeAgent); the session count is small enough this is cheap.
    var sessions = (await db.Sessions
        .Select(s => new SessionListItem(
            s.Id,
            s.StartedAt,
            s.EndedAt,
            s.Source,
            db.TranscriptSegments.Count(t => t.SessionId == s.Id),
            db.StoredSummaries.Any(x => x.SessionId == s.Id)))
        .ToListAsync(ct))
        .OrderByDescending(s => s.StartedAt)
        .ToList();

    return Results.Ok(sessions);
});

app.MapGet("/sessions/{id:guid}", async (
    Guid id,
    HarkenDbContext db,
    CancellationToken ct) =>
{
    var session = await db.Sessions
        .Where(s => s.Id == id)
        .Select(s => new { s.Id, s.StartedAt, s.EndedAt, s.Source, s.TranscriptionStatus, s.TranscriptionFailureReason })
        .FirstOrDefaultAsync(ct);

    if (session is null)
    {
        return Results.NotFound();
    }

    // Materialize then order client-side: SQLite can't translate ORDER BY on a
    // TimeSpan column (same limitation already worked around in SummarizeAgent),
    // and a Session's segment count is small enough this is cheap.
    var segments = (await db.TranscriptSegments
        .Where(t => t.SessionId == id)
        .Select(t => new TranscriptSegmentView(t.Id, t.Offset, t.Text, t.RecognizedAt))
        .ToListAsync(ct))
        .OrderBy(t => t.Offset)
        .ToList();

    var stored = await db.StoredSummaries
        .Where(x => x.SessionId == id)
        .Select(x => new SessionSummary(x.SessionId, x.Text, x.GeneratedAt))
        .FirstOrDefaultAsync(ct);

    return Results.Ok(new SessionDetail(
        session.Id,
        session.StartedAt,
        session.EndedAt,
        session.Source,
        segments,
        stored,
        session.TranscriptionStatus,
        session.TranscriptionFailureReason));
});

app.MapPost("/sessions/{id:guid}/summary", async (
    Guid id,
    SummarizeAgent agent,
    CancellationToken ct) =>
{
    SessionSummary? summary;
    try
    {
        summary = await agent.SummarizeAsync(id, ct);
    }
    catch (Exception ex)
    {
        // Genuine LLM/provider failure — the Session existed.
        return Results.Problem(detail: ex.Message, statusCode: StatusCodes.Status502BadGateway);
    }

    return summary is null ? Results.NotFound() : Results.Ok(summary);
});

app.Run();

public partial class Program { }
