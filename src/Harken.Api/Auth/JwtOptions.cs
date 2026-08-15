namespace Harken.Api.Auth;

/// <summary>
/// JWT signing configuration, bound from the "Jwt" configuration section.
/// The key is never committed — it comes from user-secrets (dev) or the environment.
/// </summary>
public sealed record JwtOptions(string Key, string Issuer, string Audience)
{
    public const string SectionName = "Jwt";
}
