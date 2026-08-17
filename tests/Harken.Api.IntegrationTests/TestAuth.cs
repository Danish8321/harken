using Microsoft.Extensions.DependencyInjection;
using Harken.Api.Data;
using Harken.Core;

namespace Harken.Api.IntegrationTests;

/// <summary>Test helpers: seed a Session/Segment directly. ADR-0009: MVP 1 has no
/// ownership model, so there is no per-user client or token to set up anymore.</summary>
public static class TestAuth
{
    /// <summary>Inserts a Session and returns its id.</summary>
    public static Guid SeedSession(
        this CustomWebApplicationFactory factory,
        DateTimeOffset? startedAt = null)
    {
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();

        var session = new Session
        {
            Id = Guid.NewGuid(),
            StartedAt = startedAt ?? DateTimeOffset.UtcNow,
            Source = AudioSource.Microphone,
        };
        db.Sessions.Add(session);
        db.SaveChanges();

        return session.Id;
    }

    /// <summary>Inserts a Transcript Segment into an existing Session.</summary>
    public static Guid SeedSegment(
        this CustomWebApplicationFactory factory,
        Guid sessionId,
        TimeSpan offset,
        string text)
    {
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();

        var segment = new TranscriptSegment
        {
            Id = Guid.NewGuid(),
            SessionId = sessionId,
            Offset = offset,
            Text = text,
            RecognizedAt = DateTimeOffset.UtcNow,
        };
        db.TranscriptSegments.Add(segment);
        db.SaveChanges();

        return segment.Id;
    }
}
