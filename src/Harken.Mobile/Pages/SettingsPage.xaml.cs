using Harken.Mobile.Services;

namespace Harken.Mobile.Pages;

public partial class SettingsPage : ContentPage
{
	private readonly AppSettings _appSettings;

	public SettingsPage(AppSettings appSettings)
	{
		InitializeComponent();
		_appSettings = appSettings;
		BaseUrlEntry.Text = _appSettings.BaseUrl;
	}

	private async void OnSaveClicked(object? sender, EventArgs e)
	{
		if (!AppSettings.TryValidate(BaseUrlEntry.Text, out var error))
		{
			await DisplayAlertAsync("Invalid URL", error, "OK");
			return;
		}

		_appSettings.BaseUrl = BaseUrlEntry.Text;
		await DisplayAlertAsync("Saved", "Base URL saved.", "OK");
	}
}
