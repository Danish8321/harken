namespace Harken.Core.Client;

/// <summary>
/// Stores the bearer token acquired from <c>POST /auth/login</c>. The backing
/// <see cref="ISecretStore"/> must be encrypted at rest — on Android that means
/// <c>SecureStorage</c>, never <c>Preferences</c> (ADR-0004). The password itself
/// is never stored: only the token the server issues in exchange for it.
/// </summary>
public sealed class TokenStore
{
    public const string TokenKey = "auth_token";

    private readonly ISecretStore _secrets;

    public TokenStore(ISecretStore secrets)
    {
        ArgumentNullException.ThrowIfNull(secrets);
        _secrets = secrets;
    }

    /// <summary>Returns the stored token, or null when there is none.</summary>
    public async Task<string?> GetTokenAsync()
    {
        var token = await _secrets.GetAsync(TokenKey).ConfigureAwait(false);
        return string.IsNullOrWhiteSpace(token) ? null : token;
    }

    public Task SetTokenAsync(string token)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(token);
        return _secrets.SetAsync(TokenKey, token);
    }

    /// <summary>Clears the token — used on sign-out and on any 401 from the API.</summary>
    public void Clear() => _secrets.Remove(TokenKey);
}
