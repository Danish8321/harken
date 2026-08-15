using Harken.Mobile.Services;

namespace Harken.Mobile.Pages;

public partial class LoginPage : ContentPage
{
	private readonly AuthService _authService;

	public LoginPage(AuthService authService)
	{
		InitializeComponent();
		_authService = authService;
	}

	private async void OnSignInClicked(object? sender, EventArgs e)
	{
		SignInButton.IsEnabled = false;
		StatusLabel.Text = "Signing in...";

		try
		{
			var error = await _authService.LoginAsync(EmailEntry.Text, PasswordEntry.Text);
			if (error is not null)
			{
				StatusLabel.Text = error;
				return;
			}

			// Drop the password from the UI as soon as it has been exchanged for a
			// token; it is never stored or logged anywhere.
			PasswordEntry.Text = "";
			StatusLabel.Text = "";

			await Shell.Current.GoToAsync($"//{Routes.Capture}");
		}
		finally
		{
			SignInButton.IsEnabled = true;
		}
	}
}
