using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Xunit;

namespace Harken.Api.IntegrationTests;

public class StartupConfigurationTests
{
    // Settings are blanked explicitly rather than merely left unset: a developer's
    // user-secrets are part of the host's configuration, so "absent" has to be
    // expressed as an empty override to make the test deterministic on any machine.
    private sealed class ConfiguredFactory : WebApplicationFactory<Program>
    {
        private readonly IReadOnlyDictionary<string, string> _settings;

        public ConfiguredFactory(IReadOnlyDictionary<string, string> settings)
            => _settings = settings;

        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            foreach (var (key, value) in _settings)
            {
                builder.UseSetting(key, value);
            }
        }
    }

    // Jwt:Key is the only required setting since ADR-0007 removed live captioning:
    // transcription runs on a local model, so the host no longer needs cloud credentials
    // to start.
    private static readonly Dictionary<string, string> Complete = new()
    {
        ["Jwt:Key"] = "harken-integration-test-signing-key-not-a-secret-0123456789",
    };

    private static Dictionary<string, string> Without(string missing)
    {
        var settings = new Dictionary<string, string>(Complete, StringComparer.Ordinal)
        {
            [missing] = "",
        };
        return settings;
    }

    [Theory]
    [InlineData("Jwt:Key")]
    public void StartupThrowsWhenRequiredConfigurationIsAbsent(string missing)
    {
        using var factory = new ConfiguredFactory(Without(missing));

        var ex = Assert.Throws<InvalidOperationException>(() => factory.CreateClient());

        Assert.Contains(missing, ex.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void StartupSucceedsWithoutAzureCredentials()
    {
        // Guards the ADR-0007 promise that MVP 1 needs no cloud account at all: a host
        // that still demanded an Azure key would make the local-only path a fiction.
        using var factory = new ConfiguredFactory(Complete);

        using var client = factory.CreateClient();

        Assert.NotNull(client);
    }
}
