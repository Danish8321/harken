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

    private static readonly Dictionary<string, string> Complete = new()
    {
        ["Jwt:Key"] = "harken-integration-test-signing-key-not-a-secret-0123456789",
        ["AzureSpeech:Key"] = "integration-test-speech-key",
        ["AzureSpeech:Region"] = "westeurope",
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
    [InlineData("AzureSpeech:Key")]
    [InlineData("AzureSpeech:Region")]
    public void StartupThrowsWhenRequiredConfigurationIsAbsent(string missing)
    {
        using var factory = new ConfiguredFactory(Without(missing));

        var ex = Assert.Throws<InvalidOperationException>(() => factory.CreateClient());

        Assert.Contains(missing, ex.Message, StringComparison.Ordinal);
    }
}
