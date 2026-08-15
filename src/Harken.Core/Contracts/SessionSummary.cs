namespace Harken.Core.Contracts;

public record SessionSummary(Guid SessionId, string Summary, DateTimeOffset GeneratedAt);
