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
    // A real temp file, not a single shared in-memory SqliteConnection object: the
    // background transcription job (Task 6) now runs concurrently with request handlers,
    // each opening its own DbContext/connection. Multiple DbContexts sharing one
    // SqliteConnection instance is not supported and surfaces as "database is locked"
    // under that concurrency; a file with WAL journaling and a busy timeout lets separate
    // connections coordinate the way they would against the app's real SQLite database.
    private readonly string _dbPath =
        Path.Combine(Path.GetTempPath(), "harken-test-" + Guid.NewGuid().ToString("N") + ".db");

    private string ConnectionString => $"Data Source={_dbPath};Default Timeout=5";

    // Unique per factory instance so parallel test runs never share (or race on
    // cleaning up) the same directory.
    private readonly string _recordingsPath =
        Path.Combine(Path.GetTempPath(), "harken-test-recordings-" + Guid.NewGuid().ToString("N"));

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        // The app fails fast without a signing key; supply a test-only one so the
        // host boots without depending on developer user-secrets.
        builder.UseSetting("Jwt:Key", "harken-integration-test-signing-key-not-a-secret-0123456789");
        builder.UseSetting("Jwt:Issuer", "Harken");
        builder.UseSetting("Jwt:Audience", "Harken");
        builder.UseSetting("Storage:RecordingsPath", _recordingsPath);

        builder.ConfigureServices(services =>
        {
            var descriptor = services.SingleOrDefault(
                d => d.ServiceType == typeof(DbContextOptions<HarkenDbContext>));
            if (descriptor is not null)
            {
                services.Remove(descriptor);
            }

            services.AddDbContext<HarkenDbContext>(o => o.UseSqlite(ConnectionString));

            using var scope = services.BuildServiceProvider().CreateScope();
            var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
            db.Database.EnsureCreated();

            // WAL lets readers and writers proceed concurrently instead of blocking on a
            // single file lock; EnsureCreated's connection already opened the file, so set
            // it once here rather than per-connection.
            using var conn = new SqliteConnection(ConnectionString);
            conn.Open();
            using var cmd = conn.CreateCommand();
            cmd.CommandText = "PRAGMA journal_mode=WAL;";
            cmd.ExecuteNonQuery();
        });
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing)
        {
            SqliteConnection.ClearAllPools();

            foreach (var path in new[] { _dbPath, _dbPath + "-wal", _dbPath + "-shm" })
            {
                if (File.Exists(path))
                {
                    File.Delete(path);
                }
            }

            if (Directory.Exists(_recordingsPath))
            {
                Directory.Delete(_recordingsPath, recursive: true);
            }
        }
    }
}
