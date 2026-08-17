using System.Net.Http.Json;
using Harken.Core.Contracts;
using Xunit;

namespace Harken.Api.IntegrationTests;

// ADR-0009: MVP 1 has no ownership model — one implicit user, so the cross-user
// isolation and unauthenticated-rejection cases this file used to cover no longer
// apply. What remains is that list/detail return correctly for the sessions that exist.
public class SessionReadEndpointTests : IClassFixture<CustomWebApplicationFactory>
{
    private static readonly string[] ExpectedTranscript = ["first", "second"];

    private readonly CustomWebApplicationFactory _factory;

    public SessionReadEndpointTests(CustomWebApplicationFactory factory)
    {
        _factory = factory;
    }

    [Fact]
    public async Task ListReturnsSessionsNewestFirst()
    {
        var client = _factory.CreateClient();

        var older = _factory.SeedSession(DateTimeOffset.UtcNow.AddHours(-2));
        var newer = _factory.SeedSession(DateTimeOffset.UtcNow);

        var sessions = await client.GetFromJsonAsync<List<SessionListItem>>("/sessions");

        Assert.NotNull(sessions);
        var ids = sessions!.Select(s => s.Id).ToList();
        Assert.Contains(older, ids);
        Assert.Contains(newer, ids);
        Assert.True(ids.IndexOf(newer) < ids.IndexOf(older), "Sessions must be newest first.");
    }

    [Fact]
    public async Task DetailRoundTripsStoredTranscriptInOffsetOrder()
    {
        var client = _factory.CreateClient();
        var sessionId = _factory.SeedSession();

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
        var client = _factory.CreateClient();
        var sessionId = _factory.SeedSession();

        var generated = await client.PostAsync($"/sessions/{sessionId}/summary", content: null);
        generated.EnsureSuccessStatusCode();

        var detail = await client.GetFromJsonAsync<SessionDetail>($"/sessions/{sessionId}");

        Assert.NotNull(detail);
        Assert.NotNull(detail!.Summary);
        Assert.Equal("(empty transcript)", detail.Summary!.Summary);
    }

    [Fact]
    public async Task UnknownSessionReturnsNotFoundFromDetail()
    {
        var client = _factory.CreateClient();

        var response = await client.GetAsync($"/sessions/{Guid.NewGuid()}");

        Assert.Equal(System.Net.HttpStatusCode.NotFound, response.StatusCode);
    }
}
