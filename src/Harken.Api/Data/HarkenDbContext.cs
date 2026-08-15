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

    protected override void OnModelCreating(ModelBuilder modelBuilder)
    {
        modelBuilder.Entity<Session>(entity =>
        {
            entity.HasKey(s => s.Id);
            entity.Property(s => s.Source).HasConversion<string>();
            entity.HasMany(s => s.Segments)
                .WithOne()
                .HasForeignKey(t => t.SessionId)
                .OnDelete(DeleteBehavior.Cascade);
        });

        modelBuilder.Entity<TranscriptSegment>(entity =>
        {
            entity.HasKey(t => t.Id);
        });
    }
}
