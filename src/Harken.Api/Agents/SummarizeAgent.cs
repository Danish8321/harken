using Microsoft.Agents.AI;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Harken.Api.Data;
using Harken.Core;
using Harken.Core.Contracts;

// NOTE: The Agent Framework preview exposes IChatClient.AsAIAgent(...) rather than
// the documented CreateAIAgent(...); adapted to the installed API surface.

namespace Harken.Api.Agents;

public class SummarizeAgent
{
    private const string Instructions =
        "You summarize a meeting or lecture transcript. Produce a short paragraph summary " +
        "of what was discussed, followed by 3 to 6 concise bullet points capturing the key " +
        "items and action items. Be brief and factual.";

    private readonly HarkenDbContext _db;
    private readonly AIAgent _agent;

    public SummarizeAgent(
        [FromKeyedServices("chat-model")] IChatClient chatClient,
        HarkenDbContext db)
    {
        _db = db;
        _agent = chatClient.AsAIAgent(instructions: Instructions, name: "Summarizer");
    }

    /// <summary>
    /// Summarizes a Session. Returns null when the Session does not exist — MVP 1 has no
    /// ownership model (ADR-0009), so this is just an existence check.
    /// </summary>
    public async Task<SessionSummary?> SummarizeAsync(Guid sessionId, CancellationToken ct)
    {
        var exists = await _db.Sessions.AnyAsync(s => s.Id == sessionId, ct);
        if (!exists)
        {
            return null;
        }

        // Ordered client-side: SQLite can't translate ORDER BY on a TimeSpan column,
        // and a Session's segment count is small enough this is cheap.
        var segments = (await _db.TranscriptSegments
            .Where(t => t.SessionId == sessionId)
            .Select(t => new { t.Offset, t.Text })
            .ToListAsync(ct))
            .OrderBy(s => s.Offset)
            .Select(s => s.Text)
            .ToList();

        if (segments.Count == 0)
        {
            return await StoreAsync(sessionId, "(empty transcript)", ct);
        }

        var transcript = string.Join("\n", segments);

        var result = await _agent.RunAsync(transcript, cancellationToken: ct);

        return await StoreAsync(sessionId, result.Text, ct);
    }

    /// <summary>
    /// Persists the generated Summary so <c>GET /sessions/{id}</c> can re-read it instead
    /// of paying for another LLM call. Regenerating overwrites the existing row.
    /// </summary>
    private async Task<SessionSummary> StoreAsync(Guid sessionId, string text, CancellationToken ct)
    {
        var generatedAt = DateTimeOffset.UtcNow;

        var existing = await _db.StoredSummaries
            .FirstOrDefaultAsync(s => s.SessionId == sessionId, ct);

        if (existing is null)
        {
            _db.StoredSummaries.Add(new StoredSummary
            {
                Id = Guid.NewGuid(),
                SessionId = sessionId,
                Text = text,
                GeneratedAt = generatedAt,
            });
        }
        else
        {
            existing.Text = text;
            existing.GeneratedAt = generatedAt;
        }

        await _db.SaveChangesAsync(ct);

        return new SessionSummary(sessionId, text, generatedAt);
    }
}
