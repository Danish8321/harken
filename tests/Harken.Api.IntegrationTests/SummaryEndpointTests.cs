using System.Net;
using Xunit;

namespace Harken.Api.IntegrationTests;

public class SummaryEndpointTests : IClassFixture<CustomWebApplicationFactory>
{
    private readonly CustomWebApplicationFactory _factory;

    public SummaryEndpointTests(CustomWebApplicationFactory factory)
    {
        _factory = factory;
    }

    [Fact]
    public async Task SummaryForSessionWithNoTranscriptReturnsEmptyTranscriptMessage()
    {
        var client = _factory.CreateClient();
        var sessionId = _factory.SeedSession();

        var response = await client.PostAsync($"/sessions/{sessionId}/summary", content: null);

        var body = await response.Content.ReadAsStringAsync();
        Assert.True(response.IsSuccessStatusCode, $"Status {response.StatusCode}: {body}");
        Assert.Contains("(empty transcript)", body);
    }

    [Fact]
    public async Task HealthReturnsOkWithoutAToken()
    {
        var client = _factory.CreateClient();

        var response = await client.GetAsync("/health");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
    }
}
