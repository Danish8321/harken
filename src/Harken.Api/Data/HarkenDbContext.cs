using Microsoft.AspNetCore.Identity;
using Microsoft.AspNetCore.Identity.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore;
using Harken.Core;

namespace Harken.Api.Data;

public class HarkenDbContext : IdentityDbContext<IdentityUser>
{
    public HarkenDbContext(DbContextOptions<HarkenDbContext> options)
        : base(options)
    {
    }

    public DbSet<Session> Sessions => Set<Session>();

    public DbSet<TranscriptSegment> TranscriptSegments => Set<TranscriptSegment>();

    public DbSet<StoredSummary> StoredSummaries => Set<StoredSummary>();

    public DbSet<Recording> Recordings => Set<Recording>();

    protected override void OnModelCreating(ModelBuilder builder)
    {
        // Identity configures its own entities first; ours layer on top.
        base.OnModelCreating(builder);

        builder.Entity<Session>(entity =>
        {
            entity.HasKey(s => s.Id);
            entity.Property(s => s.Source).HasConversion<string>();
            // 450 matches the Identity user id key length.
            entity.Property(s => s.OwnerId).IsRequired().HasMaxLength(450);
            entity.HasIndex(s => s.OwnerId);
            entity.HasMany(s => s.Segments)
                .WithOne()
                .HasForeignKey(t => t.SessionId)
                .OnDelete(DeleteBehavior.Cascade);
            entity.Property(s => s.TranscriptionStatus).HasConversion<string>();
            // No HasMaxLength: a real failure reason (e.g. a Whisper exception message)
            // can be long, and this column is diagnostic, not queried.
        });

        builder.Entity<TranscriptSegment>(entity =>
        {
            entity.HasKey(t => t.Id);
        });

        builder.Entity<StoredSummary>(entity =>
        {
            entity.HasKey(s => s.Id);
            // One Summary per Session; regenerating overwrites the existing row.
            entity.HasIndex(s => s.SessionId).IsUnique();
            entity.HasOne<Session>()
                .WithOne()
                .HasForeignKey<StoredSummary>(s => s.SessionId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        builder.Entity<Recording>(entity =>
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
