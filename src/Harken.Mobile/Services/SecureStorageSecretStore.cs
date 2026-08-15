using Harken.Core.Client;
using Microsoft.Maui.Storage;

namespace Harken.Mobile.Services;

/// <summary>
/// <see cref="ISecretStore"/> backed by MAUI <c>SecureStorage</c> (Android Keystore).
/// Deliberately not <c>Preferences</c>: that is plain, unencrypted shared prefs.
/// </summary>
public sealed class SecureStorageSecretStore : ISecretStore
{
	private readonly ISecureStorage _secureStorage;

	public SecureStorageSecretStore()
		: this(SecureStorage.Default)
	{
	}

	public SecureStorageSecretStore(ISecureStorage secureStorage)
	{
		_secureStorage = secureStorage;
	}

	public Task<string?> GetAsync(string key) => _secureStorage.GetAsync(key);

	public Task SetAsync(string key, string value) => _secureStorage.SetAsync(key, value);

	public bool Remove(string key) => _secureStorage.Remove(key);
}
