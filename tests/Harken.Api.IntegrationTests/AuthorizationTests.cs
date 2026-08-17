using Xunit;

namespace Harken.Api.IntegrationTests;

// ADR-0009: MVP 1 has no authentication or per-user ownership, so the cross-user
// isolation scenarios this file used to cover no longer apply. What remains is the
// plain not-found behavior for unknown sessions.
public class AuthorizationTests : IClassFixture<CustomWebApplicationFactory>
{
    private readonly CustomWebApplicationFactory _factory;

    public AuthorizationTests(CustomWebApplicationFactory factory)
    {
        _factory = factory;
    }

    [Fact]
    public async Task SummaryForUnknownSessionReturnsNotFound()
    {
        var client = _factory.CreateClient();

        var response = await client.PostAsync($"/sessions/{Guid.NewGuid()}/summary", content: null);

        Assert.Equal(System.Net.HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task SummaryForSessionWithNoTranscriptSucceeds()
    {
        var client = _factory.CreateClient();
        var sessionId = _factory.SeedSession();

        var response = await client.PostAsync($"/sessions/{sessionId}/summary", content: null);

        var body = await response.Content.ReadAsStringAsync();
        Assert.True(response.IsSuccessStatusCode, $"Status {response.StatusCode}: {body}");
        Assert.Contains("(empty transcript)", body);
    }
}
