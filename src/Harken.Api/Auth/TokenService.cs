using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Text;
using Microsoft.AspNetCore.Identity;
using Microsoft.IdentityModel.Tokens;
using Harken.Core.Contracts;

namespace Harken.Api.Auth;

/// <summary>Issues signed JWT bearer tokens for authenticated Identity users.</summary>
public sealed class TokenService
{
    // Harken is a personal/family app with no refresh-token flow yet, so a long-lived
    // token keeps console and mobile clients from re-prompting constantly. Revisit
    // (shorter expiry + refresh) before any public exposure.
    private static readonly TimeSpan Lifetime = TimeSpan.FromDays(7);

    private readonly JwtOptions _options;

    public TokenService(JwtOptions options) => _options = options;

    public TokenResponse CreateToken(IdentityUser user)
    {
        ArgumentNullException.ThrowIfNull(user);

        var expiresAt = DateTimeOffset.UtcNow.Add(Lifetime);

        var claims = new List<Claim>
        {
            new(JwtRegisteredClaimNames.Sub, user.Id),
            new(ClaimTypes.NameIdentifier, user.Id),
            new(JwtRegisteredClaimNames.Jti, Guid.NewGuid().ToString()),
        };

        if (!string.IsNullOrEmpty(user.Email))
        {
            claims.Add(new Claim(JwtRegisteredClaimNames.Email, user.Email));
            claims.Add(new Claim(ClaimTypes.Email, user.Email));
        }

        var credentials = new SigningCredentials(
            new SymmetricSecurityKey(Encoding.UTF8.GetBytes(_options.Key)),
            SecurityAlgorithms.HmacSha256);

        var token = new JwtSecurityToken(
            issuer: _options.Issuer,
            audience: _options.Audience,
            claims: claims,
            notBefore: DateTime.UtcNow,
            expires: expiresAt.UtcDateTime,
            signingCredentials: credentials);

        return new TokenResponse(new JwtSecurityTokenHandler().WriteToken(token), expiresAt);
    }
}
