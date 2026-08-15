using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace Harken.Api.Data;

public class HarkenDbContextFactory : IDesignTimeDbContextFactory<HarkenDbContext>
{
    public HarkenDbContext CreateDbContext(string[] args)
    {
        var options = new DbContextOptionsBuilder<HarkenDbContext>()
            .UseSqlite("Data Source=harken.db")
            .Options;

        return new HarkenDbContext(options);
    }
}
