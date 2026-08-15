using Microsoft.Maui.Storage;

namespace Harken.Mobile.Services;

public class AppSettings
{
	private const string BaseUrlKey = "BaseUrl";

	private readonly IPreferences _preferences;

	public AppSettings()
		: this(Preferences.Default)
	{
	}

	public AppSettings(IPreferences preferences)
	{
		_preferences = preferences;
	}

	public string? BaseUrl
	{
		get => _preferences.Get(BaseUrlKey, "");
		set => _preferences.Set(BaseUrlKey, value ?? "");
	}

	public static bool TryValidate(string? url, out string? error)
	{
		if (string.IsNullOrWhiteSpace(url))
		{
			error = "Base URL cannot be empty.";
			return false;
		}

		if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
		{
			error = "Base URL must be a valid absolute URL.";
			return false;
		}

		if (uri.Scheme != Uri.UriSchemeHttp && uri.Scheme != Uri.UriSchemeHttps)
		{
			error = "Base URL must use http or https.";
			return false;
		}

		error = null;
		return true;
	}
}
