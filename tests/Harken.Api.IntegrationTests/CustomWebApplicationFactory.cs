using System.Linq;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Data.Sqlite;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
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

    // Pooling=False so disposal can delete the file without reaching for
    // SqliteConnection.ClearAllPools(), which is process-global: calling it as one test
    // class tore down closed pooled connections belonging to factories other classes were
    // still building, and the victim's host build threw. Nothing here is hot enough for
    // the pool to be worth that.
    private string ConnectionString => $"Data Source={_dbPath};Default Timeout=5;Pooling=False";

    // Unique per factory instance so parallel test runs never share (or race on
    // cleaning up) the same directory.
    private readonly string _recordingsPath =
        Path.Combine(Path.GetTempPath(), "harken-test-recordings-" + Guid.NewGuid().ToString("N"));

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
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
        });
    }

    // Creating the schema belongs after the host exists, not inside ConfigureServices.
    // Doing it there meant calling services.BuildServiceProvider() — a second, throwaway
    // container (ASP0000) whose singletons are duplicates of the real host's and which is
    // never disposed — and it ran while the host was still being built, so anything it
    // threw surfaced from HostApplicationBuilder.Build() with no hint of which test.
    protected override IHost CreateHost(IHostBuilder builder)
    {
        var host = base.CreateHost(builder);

        using (var scope = host.Services.CreateScope())
        {
            scope.ServiceProvider.GetRequiredService<HarkenDbContext>().Database.EnsureCreated();
        }

        // WAL lets readers and writers proceed concurrently instead of blocking on a
        // single file lock. It is a property of the database file, so it is set once here
        // rather than per-connection.
        using var conn = new SqliteConnection(ConnectionString);
        conn.Open();
        using var cmd = conn.CreateCommand();
        cmd.CommandText = "PRAGMA journal_mode=WAL;";
        cmd.ExecuteNonQuery();

        return host;
    }

    protected override void Dispose(bool disposing)
    {
        base.Dispose(disposing);
        if (disposing)
        {
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
