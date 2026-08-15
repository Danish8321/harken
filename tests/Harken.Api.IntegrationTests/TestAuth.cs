using System.Net.Http.Headers;
using System.Net.Http.Json;
using Microsoft.Extensions.DependencyInjection;
using Harken.Api.Data;
using Harken.Core;

namespace Harken.Api.IntegrationTests;

/// <summary>Test helpers: register+login a user, and seed an owned Session.</summary>
public static class TestAuth
{
    private const string Password = "Str0ng!Passw0rd!";

    private sealed record TokenResponse(string Token, DateTimeOffset ExpiresAt);

    /// <summary>Registers a fresh user and returns an HttpClient carrying their bearer token.</summary>
    public static async Task<(HttpClient Client, string UserId)> CreateAuthenticatedClientAsync(
        this CustomWebApplicationFactory factory)
    {
        var email = $"user-{Guid.NewGuid():N}@harken.test";
        var client = factory.CreateClient();

        var register = await client.PostAsJsonAsync("/auth/register", new { Email = email, Password });
        register.EnsureSuccessStatusCode();

        var login = await client.PostAsJsonAsync("/auth/login", new { Email = email, Password });
        login.EnsureSuccessStatusCode();

        var token = await login.Content.ReadFromJsonAsync<TokenResponse>()
            ?? throw new InvalidOperationException("Login returned no token.");

        client.DefaultRequestHeaders.Authorization =
            new AuthenticationHeaderValue("Bearer", token.Token);

        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        var userId = db.Users.Single(u => u.Email == email).Id;

        return (client, userId);
    }

    /// <summary>Inserts a Session owned by <paramref name="ownerId"/> and returns its id.</summary>
    public static Guid SeedSession(
        this CustomWebApplicationFactory factory,
        string ownerId,
        DateTimeOffset? startedAt = null)
    {
        using var scope = factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();

        var session = new Session
        {
            Id = Guid.NewGuid(),
            OwnerId = ownerId,
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
