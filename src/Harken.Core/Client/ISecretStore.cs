namespace Harken.Core.Client;

/// <summary>
/// Minimal key/value store for secrets. Shaped to match MAUI's <c>ISecureStorage</c>
/// so the platform adapter is a one-liner, but declared here so the storage logic
/// can be unit-tested without any MAUI types.
/// </summary>
public interface ISecretStore
{
    Task<string?> GetAsync(string key);

    Task SetAsync(string key, string value);

    bool Remove(string key);
}
