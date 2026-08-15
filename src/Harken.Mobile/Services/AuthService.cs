using System.Net;
using System.Net.Http.Json;
using Harken.Core.Client;
using Harken.Core.Contracts;

namespace Harken.Mobile.Services;

/// <summary>
/// Exchanges credentials for a bearer token and owns the stored token's lifetime.
/// The password is used for the single login request and never stored or logged.
/// </summary>
public sealed class AuthService
{
	private readonly AppSettings _appSettings;
	private readonly TokenStore _tokenStore;
	private readonly HttpClient _httpClient;

	public AuthService(AppSettings appSettings, TokenStore tokenStore)
		: this(appSettings, tokenStore, new HttpClient())
	{
	}

	public AuthService(AppSettings appSettings, TokenStore tokenStore, HttpClient httpClient)
	{
		_appSettings = appSettings;
		_tokenStore = tokenStore;
		_httpClient = httpClient;
	}

	public Task<string?> GetTokenAsync() => _tokenStore.GetTokenAsync();

	/// <summary>Signs in. Returns null on success, or a message to show the user.</summary>
	public async Task<string?> LoginAsync(string? email, string? password, CancellationToken ct = default)
	{
		if (!AppSettings.TryValidate(_appSettings.BaseUrl, out var settingsError))
		{
			return settingsError;
		}

		if (string.IsNullOrWhiteSpace(email) || string.IsNullOrEmpty(password))
		{
			return "Email and password are required.";
		}

		try
		{
			using var response = await _httpClient.PostAsJsonAsync(
				$"{_appSettings.BaseUrl}/auth/login",
				new LoginRequest(email, password),
				ct);

			if (response.StatusCode == HttpStatusCode.Unauthorized)
			{
				// Matches the server's deliberately generic 401: no hint about
				// whether the account exists.
				return "Invalid email or password.";
			}

			if (!response.IsSuccessStatusCode)
			{
				return $"Login failed ({(int)response.StatusCode}).";
			}

			var token = await response.Content.ReadFromJsonAsync<TokenResponse>(ct);
			if (token is null || string.IsNullOrWhiteSpace(token.Token))
			{
				return "Login failed: no token returned.";
			}

			await _tokenStore.SetTokenAsync(token.Token);
			return null;
		}
		catch (HttpRequestException ex)
		{
			return $"Could not reach the server: {ex.Message}";
		}
	}

	public void SignOut() => _tokenStore.Clear();
}
