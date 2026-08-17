using Microsoft.EntityFrameworkCore;
using Harken.Core;

namespace Harken.Api.Data;

public class HarkenDbContext : DbContext
{
    public HarkenDbContext(DbContextOptions<HarkenDbContext> options)
        : base(options)
    {
    }

    public DbSet<Session> Sessions => Set<Session>();

    public DbSet<TranscriptSegment> TranscriptSegments => Set<TranscriptSegment>();

    public DbSet<StoredSummary> StoredSummaries => Set<StoredSummary>();

    public DbSet<Recording> Recordings => Set<Recording>();

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        base.OnModelCreating(modelBuilder);

        modelBuilder.Entity<Session>(entity =>
        {
            entity.HasKey(s => s.Id);
            entity.Property(s => s.Source).HasConversion<string>();
            entity.HasMany(s => s.Segments)
                .WithOne()
                .HasForeignKey(t => t.SessionId)
                .OnDelete(DeleteBehavior.Cascade);
            entity.Property(s => s.TranscriptionStatus).HasConversion<string>();
            // Filtered: uniqueness is what makes a retried upload idempotent, but rows
            // without a client id (the console's) must not collide with each other on NULL.
            entity.HasIndex(s => s.ClientRecordingId)
                .IsUnique()
                .HasFilter("\"ClientRecordingId\" IS NOT NULL");
            // No HasMaxLength: a real failure reason (e.g. a Whisper exception message)
            // can be long, and this column is diagnostic, not queried.
        });

        modelBuilder.Entity<TranscriptSegment>(entity =>
        {
            entity.HasKey(t => t.Id);
        });

        modelBuilder.Entity<StoredSummary>(entity =>
        {
            entity.HasKey(s => s.Id);
            // One Summary per Session; regenerating overwrites the existing row.
            entity.HasIndex(s => s.SessionId).IsUnique();
            entity.HasOne<Session>()
                .WithOne()
                .HasForeignKey<StoredSummary>(s => s.SessionId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<Recording>(entity =>
        {
            entity.HasKey(r => r.Id);
            // One Recording per Session; a Session that is re-recorded is a new Session.
            entity.HasIndex(r => r.SessionId).IsUnique();
            entity.HasOne<Session>()
                .WithOne(s => s.Recording)
                .HasForeignKey<Recording>(r => r.SessionId)
                .OnDelete(DeleteBehavior.Cascade);
        });
    }
}
