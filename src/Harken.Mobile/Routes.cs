namespace Harken.Mobile;

/// <summary>Shell route names, kept in one place so navigation strings cannot drift.</summary>
public static class Routes
{
	public const string Login = "LoginPage";
	public const string Capture = "CapturePage";
	public const string Settings = "SettingsPage";

	/// <summary>Sends the user back to the login page after a 401 or sign-out.</summary>
	public static Task GoToLoginAsync() => Shell.Current.GoToAsync($"//{Login}");
}
