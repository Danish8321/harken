using System.Net;
using System.Net.Http.Json;
using Harken.Core.Contracts;
using Xunit;

namespace Harken.Api.IntegrationTests;

public class SessionReadEndpointTests : IClassFixture<CustomWebApplicationFactory>
{
    private static readonly string[] ExpectedTranscript = ["first", "second"];

    private readonly CustomWebApplicationFactory _factory;

    public SessionReadEndpointTests(CustomWebApplicationFactory factory)
    {
        _factory = factory;
    }

    [Fact]
    public async Task ListReturnsOnlyTheCallersSessionsNewestFirst()
    {
        var (client, userId) = await _factory.CreateAuthenticatedClientAsync();
        var (_, otherUserId) = await _factory.CreateAuthenticatedClientAsync();

        var older = _factory.SeedSession(userId, DateTimeOffset.UtcNow.AddHours(-2));
        var newer = _factory.SeedSession(userId, DateTimeOffset.UtcNow);
        var foreign = _factory.SeedSession(otherUserId);

        var sessions = await client.GetFromJsonAsync<List<SessionListItem>>("/sessions");

        Assert.NotNull(sessions);
        var ids = sessions!.Select(s => s.Id).ToList();
        Assert.DoesNotContain(foreign, ids);
        Assert.Contains(older, ids);
        Assert.Contains(newer, ids);
        Assert.True(ids.IndexOf(newer) < ids.IndexOf(older), "Sessions must be newest first.");
    }

    [Fact]
    public async Task DetailRoundTripsStoredTranscriptInOffsetOrder()
    {
        var (client, userId) = await _factory.CreateAuthenticatedClientAsync();
        var sessionId = _factory.SeedSession(userId);

        // Inserted out of order on purpose: the endpoint must order by Offset.
        _factory.SeedSegment(sessionId, TimeSpan.FromSeconds(30), "second");
        _factory.SeedSegment(sessionId, TimeSpan.FromSeconds(5), "first");

        var detail = await client.GetFromJsonAsync<SessionDetail>($"/sessions/{sessionId}");

        Assert.NotNull(detail);
        Assert.Equal(sessionId, detail!.Id);
        Assert.Equal(ExpectedTranscript, detail.Segments.Select(s => s.Text));
        Assert.Null(detail.Summary);
    }

    [Fact]
    public async Task DetailExposesStoredSummaryAfterItIsGenerated()
    {
        var (client, userId) = await _factory.CreateAuthenticatedClientAsync();
        var sessionId = _factory.SeedSession(userId);

        var generated = await client.PostAsync($"/sessions/{sessionId}/summary", content: null);
        generated.EnsureSuccessStatusCode();

        var detail = await client.GetFromJsonAsync<SessionDetail>($"/sessions/{sessionId}");

        Assert.NotNull(detail);
        Assert.NotNull(detail!.Summary);
        Assert.Equal("(empty transcript)", detail.Summary!.Summary);
    }

    [Fact]
    public async Task AnotherUsersSessionReturnsNotFoundFromDetail()
    {
        var (client, _) = await _factory.CreateAuthenticatedClientAsync();
        var (_, otherUserId) = await _factory.CreateAuthenticatedClientAsync();
        var foreign = _factory.SeedSession(otherUserId);

        var response = await client.GetAsync($"/sessions/{foreign}");

        // 404, never 403 — a 403 would confirm the session id exists.
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task AnotherUsersSessionIsAbsentFromTheList()
    {
        var (client, _) = await _factory.CreateAuthenticatedClientAsync();
        var (_, otherUserId) = await _factory.CreateAuthenticatedClientAsync();
        var foreign = _factory.SeedSession(otherUserId);

        var sessions = await client.GetFromJsonAsync<List<SessionListItem>>("/sessions");

        Assert.NotNull(sessions);
        Assert.DoesNotContain(foreign, sessions!.Select(s => s.Id));
    }

    [Fact]
    public async Task ReadEndpointsRejectUnauthenticatedCallers()
    {
        var client = _factory.CreateClient();

        var list = await client.GetAsync("/sessions");
        var detail = await client.GetAsync($"/sessions/{Guid.NewGuid()}");

        Assert.Equal(HttpStatusCode.Unauthorized, list.StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, detail.StatusCode);
    }
}
