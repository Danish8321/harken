using Microsoft.Agents.AI;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.AI;
using Microsoft.Extensions.DependencyInjection;
using Harken.Api.Data;
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

    public async Task<SessionSummary> SummarizeAsync(Guid sessionId, CancellationToken ct)
    {
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
            return new SessionSummary(sessionId, "(empty transcript)", DateTimeOffset.UtcNow);
        }

        var transcript = string.Join("\n", segments);

        var result = await _agent.RunAsync(transcript, cancellationToken: ct);

        return new SessionSummary(sessionId, result.Text, DateTimeOffset.UtcNow);
    }
}
