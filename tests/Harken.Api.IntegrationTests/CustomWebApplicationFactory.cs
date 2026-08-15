using System.Linq;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Harken.Api.Data;

namespace Harken.Api.IntegrationTests;

public class CustomWebApplicationFactory : WebApplicationFactory<Program>
{
    private readonly SqliteConnection _connection = new("DataSource=:memory:");

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        _connection.Open();

        // The app fails fast without a signing key; supply a test-only one so the
        // host boots without depending on developer user-secrets.
        builder.UseSetting("Jwt:Key", "harken-integration-test-signing-key-not-a-secret-0123456789");
        builder.UseSetting("Jwt:Issuer", "Harken");
        builder.UseSetting("Jwt:Audience", "Harken");

        // Likewise for Speech: the host fails fast without a key/region. Nothing in the
        // integration suite actually calls Azure, so placeholders are enough to boot.
        builder.UseSetting("AzureSpeech:Key", "integration-test-speech-key");
        builder.UseSetting("AzureSpeech:Region", "westeurope");

        builder.ConfigureServices(services =>
        {
            var descriptor = services.SingleOrDefault(
                d => d.ServiceType == typeof(DbContextOptions<HarkenDbContext>));
            if (descriptor is not null)
            {
                services.Remove(descriptor);
            }

            services.AddDbContext<HarkenDbContext>(o => o.UseSqlite(_connection));

            using var scope = services.BuildServiceProvider().CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
            db.Database.EnsureCreated();
        });
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing)
        {
            _connection.Dispose();
        }
    }
}
