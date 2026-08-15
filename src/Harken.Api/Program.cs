using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Identity;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.AI;
using Microsoft.IdentityModel.Tokens;
using OllamaSharp;
using Harken.Api.Agents;
using Harken.Api.Auth;
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

// --- Identity + JWT ---
var jwtSection = builder.Configuration.GetSection(JwtOptions.SectionName);
var jwtKey = jwtSection["Key"];
if (string.IsNullOrWhiteSpace(jwtKey))
{
    // Fail fast: never fall back to a hardcoded signing key — anyone with the source
    // could then mint valid tokens for any user.
    throw new InvalidOperationException(
        "Jwt:Key is not configured. Set it with: " +
        "dotnet user-secrets set \"Jwt:Key\" \"<32+ byte random value>\" --project src/Harken.Api");
}

var jwtOptions = new JwtOptions(
    jwtKey,
    jwtSection["Issuer"] ?? "Harken",
    jwtSection["Audience"] ?? "Harken");
builder.Services.AddSingleton(jwtOptions);
builder.Services.AddSingleton<TokenService>();

builder.Services
    .AddIdentityCore<IdentityUser>(options =>
    {
        options.User.RequireUniqueEmail = true;
        options.Password.RequiredLength = 12;
        options.Password.RequireDigit = true;
        options.Password.RequireLowercase = true;
        options.Password.RequireUppercase = true;
        options.Password.RequireNonAlphanumeric = true;
    })
    .AddEntityFrameworkStores<HarkenDbContext>();

builder.Services
    .AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
    .AddJwtBearer(options =>
    {
        options.TokenValidationParameters = new TokenValidationParameters
        {
            ValidateIssuer = true,
            ValidIssuer = jwtOptions.Issuer,
            ValidateAudience = true,
            ValidAudience = jwtOptions.Audience,
            ValidateLifetime = true,
            ValidateIssuerSigningKey = true,
            IssuerSigningKey = new SymmetricSecurityKey(Encoding.UTF8.GetBytes(jwtOptions.Key)),
            ClockSkew = TimeSpan.FromMinutes(1),
        };

        // The access_token query-string exception was here for the SignalR hub, which a
        // WebSocket handshake cannot give an Authorization header. ADR-0007 removed the
        // hub, so it is gone with it: tokens in query strings leak into logs and
        // referrers, and every remaining endpoint is plain HTTP that can carry a header.
    });

builder.Services.AddAuthorization();

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

app.UseAuthentication();
app.UseAuthorization();

// Liveness probe: anonymous by design — a monitor must be able to tell the host is up
// without holding a user token, and this exposes nothing user-specific.
app.MapGet("/health", () => Results.Ok(new { status = "ok" })).AllowAnonymous();

app.MapPost("/auth/register", async (
    RegisterRequest request,
    UserManager<IdentityUser> userManager) =>
{
    var user = new IdentityUser { UserName = request.Email, Email = request.Email };
    var result = await userManager.CreateAsync(user, request.Password);
    if (!result.Succeeded)
    {
        var errors = result.Errors
            .GroupBy(e => e.Code, StringComparer.Ordinal)
            .ToDictionary(g => g.Key, g => g.Select(e => e.Description).ToArray(), StringComparer.Ordinal);
        return Results.ValidationProblem(errors);
    }

    return Results.Ok();
}).AllowAnonymous();

app.MapPost("/auth/login", async (
    LoginRequest request,
    UserManager<IdentityUser> userManager,
    TokenService tokens) =>
{
    var user = await userManager.FindByEmailAsync(request.Email);
    // Generic 401 in both branches: revealing "no such user" would let anyone
    // enumerate which email addresses have accounts.
    if (user is null || !await userManager.CheckPasswordAsync(user, request.Password))
    {
        return Results.Unauthorized();
    }

    return Results.Ok(tokens.CreateToken(user));
}).AllowAnonymous();

app.MapPost("/sessions", async (
    HttpRequest request,
    ClaimsPrincipal user,
    HarkenDbContext db,
    RecordingStorage storage,
    TranscriptionJobChannel jobs,
    CancellationToken ct) =>
{
    var ownerId = user.FindFirstValue(ClaimTypes.NameIdentifier);
    if (string.IsNullOrEmpty(ownerId))
    {
        return Results.Unauthorized();
    }

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

    var session = new Session
    {
        Id = Guid.NewGuid(),
        OwnerId = ownerId,
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
    await db.SaveChangesAsync(ct);

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
}).RequireAuthorization();

app.MapGet("/sessions", async (
    ClaimsPrincipal user,
    HarkenDbContext db,
    CancellationToken ct) =>
{
    var ownerId = user.FindFirstValue(ClaimTypes.NameIdentifier);
    if (string.IsNullOrEmpty(ownerId))
    {
        return Results.Unauthorized();
    }

    // Metadata only: no transcript text leaves this endpoint.
    // Materialized then ordered client-side: SQLite can't translate ORDER BY on a
    // DateTimeOffset column (the same limitation that bit us on TimeSpan in
    // SummarizeAgent); the caller's session count is small enough this is cheap.
    var sessions = (await db.Sessions
        .Where(s => s.OwnerId == ownerId)
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
}).RequireAuthorization();

app.MapGet("/sessions/{id:guid}", async (
    Guid id,
    ClaimsPrincipal user,
    HarkenDbContext db,
    CancellationToken ct) =>
{
    var ownerId = user.FindFirstValue(ClaimTypes.NameIdentifier);
    if (string.IsNullOrEmpty(ownerId))
    {
        return Results.Unauthorized();
    }

    // Filtering by owner in the same query means not-found and not-yours produce the
    // same 404 — a 403 would confirm the id exists and allow enumeration.
    var session = await db.Sessions
        .Where(s => s.Id == id && s.OwnerId == ownerId)
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
}).RequireAuthorization();

app.MapPost("/sessions/{id:guid}/summary", async (
    Guid id,
    ClaimsPrincipal user,
    SummarizeAgent agent,
    CancellationToken ct) =>
{
    var ownerId = user.FindFirstValue(ClaimTypes.NameIdentifier);
    if (string.IsNullOrEmpty(ownerId))
    {
        return Results.Unauthorized();
    }

    SessionSummary? summary;
    try
    {
        summary = await agent.SummarizeAsync(id, ownerId, ct);
    }
    catch (Exception ex)
    {
        // Genuine LLM/provider failure — the ownership check already passed.
        return Results.Problem(detail: ex.Message, statusCode: StatusCodes.Status502BadGateway);
    }

    // Not found and not-yours are the same answer: a 403 here would confirm the id
    // exists and let a caller enumerate other users' session ids.
    return summary is null ? Results.NotFound() : Results.Ok(summary);
}).RequireAuthorization();

app.Run();

public partial class Program { }
