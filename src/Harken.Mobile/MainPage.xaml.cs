namespace Harken.Mobile;

public partial class MainPage : ContentPage
{
	int count;

	public MainPage()
	{
		InitializeComponent();
	}

	protected override async void OnAppearing()
	{
		base.OnAppearing();
		await RequestRecordingPermissionsAsync();
	}

	private static async Task RequestRecordingPermissionsAsync()
	{
		var micStatus = await Permissions.CheckStatusAsync<Permissions.Microphone>();
		if (micStatus != PermissionStatus.Granted)
			await Permissions.RequestAsync<Permissions.Microphone>();

#if ANDROID
		if (OperatingSystem.IsAndroidVersionAtLeast(33))
		{
			var activity = global::Android.App.Application.Context as global::Android.App.Activity
				?? Platform.CurrentActivity;

			if (activity is not null
				&& AndroidX.Core.Content.ContextCompat.CheckSelfPermission(activity, global::Android.Manifest.Permission.PostNotifications)
					!= global::Android.Content.PM.Permission.Granted)
			{
				AndroidX.Core.App.ActivityCompat.RequestPermissions(
					activity,
					[global::Android.Manifest.Permission.PostNotifications],
					1001);
			}
		}
#endif
	}

	private void OnCounterClicked(object? sender, EventArgs e)
	{
		count++;

		if (count == 1)
			CounterBtn.Text = $"Clicked {count} time";
		else
			CounterBtn.Text = $"Clicked {count} times";

		SemanticScreenReader.Announce(CounterBtn.Text);
	}
}
