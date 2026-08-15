using System.Net;
using Xunit;

namespace Harken.Api.IntegrationTests;

public class AuthorizationTests : IClassFixture<CustomWebApplicationFactory>
{
    private readonly CustomWebApplicationFactory _factory;

    public AuthorizationTests(CustomWebApplicationFactory factory)
    {
        _factory = factory;
    }

    [Fact]
    public async Task SummaryWithoutTokenReturnsUnauthorized()
    {
        var client = _factory.CreateClient();

        var response = await client.PostAsync($"/sessions/{Guid.NewGuid()}/summary", content: null);

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task SummaryForAnotherUsersSessionReturnsNotFoundNotForbidden()
    {
        var (_, userAId) = await _factory.CreateAuthenticatedClientAsync();
        var sessionOfA = _factory.SeedSession(userAId);

        var (clientB, _) = await _factory.CreateAuthenticatedClientAsync();

        var response = await clientB.PostAsync($"/sessions/{sessionOfA}/summary", content: null);

        // 403 would confirm the session id is real, enabling enumeration.
        Assert.NotEqual(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task SummaryForUnknownSessionReturnsNotFound()
    {
        var (client, _) = await _factory.CreateAuthenticatedClientAsync();

        var response = await client.PostAsync($"/sessions/{Guid.NewGuid()}/summary", content: null);

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task SummaryForOwnSessionWithNoTranscriptSucceeds()
    {
        var (client, userId) = await _factory.CreateAuthenticatedClientAsync();
        var sessionId = _factory.SeedSession(userId);

        var response = await client.PostAsync($"/sessions/{sessionId}/summary", content: null);

        var body = await response.Content.ReadAsStringAsync();
        Assert.True(response.IsSuccessStatusCode, $"Status {response.StatusCode}: {body}");
        Assert.Contains("(empty transcript)", body);
    }
}
