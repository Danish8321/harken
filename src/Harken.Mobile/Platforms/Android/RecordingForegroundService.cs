using Android.App;
using Android.Content;
using Android.OS;
using AndroidX.Core.App;
using Microsoft.Extensions.DependencyInjection;
using Harken.Mobile.Services;

namespace Harken.Mobile;

[Service(ForegroundServiceType = global::Android.Content.PM.ForegroundService.TypeMicrophone)]
public class RecordingForegroundService : Service
{
	private const string ChannelId = "recording";
	private const int NotificationId = 1001;

	public override IBinder? OnBind(Intent? intent) => null;

	public override StartCommandResult OnStartCommand(Intent? intent, StartCommandFlags flags, int startId)
	{
		CreateNotificationChannelIfNeeded();

		var builder = new NotificationCompat.Builder(this, ChannelId);
		builder.SetContentTitle("Harken");
		builder.SetContentText("Harken — recording…");
		builder.SetSmallIcon(global::Android.Resource.Drawable.IcMediaPlay);
		builder.SetPriority(NotificationCompat.PriorityLow);
		builder.SetOngoing(true);
		var notification = builder.Build();

		StartForeground(NotificationId, notification);

		var audioCapture = IPlatformApplication.Current!.Services.GetRequiredService<IAudioCapture>();
		audioCapture.StartCapture();

		return StartCommandResult.Sticky;
	}

	public override void OnDestroy()
	{
		var audioCapture = IPlatformApplication.Current!.Services.GetRequiredService<IAudioCapture>();
		audioCapture.StopCapture();

		if (OperatingSystem.IsAndroidVersionAtLeast(24))
		{
			StopForeground(StopForegroundFlags.Remove);
		}
		else
		{
#pragma warning disable CS0618 // obsolete on newer APIs, needed for pre-24 support
			StopForeground(true);
#pragma warning restore CS0618
		}

		base.OnDestroy();
	}

	private void CreateNotificationChannelIfNeeded()
	{
		if (!OperatingSystem.IsAndroidVersionAtLeast(26))
			return;

		var notificationManager = GetSystemService(NotificationService) as NotificationManager;
		if (notificationManager is null)
			return;

		if (notificationManager.GetNotificationChannel(ChannelId) is not null)
			return;

		var channel = new NotificationChannel(
			ChannelId,
			"Recording",
			NotificationImportance.Low)
		{
			Description = "Shows while Harken is recording audio.",
		};

		notificationManager.CreateNotificationChannel(channel);
	}
}
