using Android.App;
using Android.Content;
using Android.OS;
using AndroidX.Core.App;
using Microsoft.Extensions.DependencyInjection;
using Harken.Core.Audio;
using Harken.Mobile.Services;

namespace Harken.Mobile;

[Service(ForegroundServiceType = global::Android.Content.PM.ForegroundService.TypeMicrophone)]
public class RecordingForegroundService : Service
{
	private const string ChannelId = "recording";
	private const int NotificationId = 1001;

	/// <summary>Guards the writer: chunks arrive on the capture thread while OnDestroy runs
	/// on the main thread, and a write landing after Dispose would throw out there where
	/// nothing catches it.</summary>
	private readonly object _writerGate = new();

	private WavWriter? _writer;
	private IAudioCapture? _audioCapture;
	private Action<byte[]>? _onChunk;

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

		var services = IPlatformApplication.Current!.Services;
		var state = services.GetRequiredService<RecordingState>();

		// Sticky restarts hand back a null intent, and a restart has no file to resume
		// writing — a half-written WAV cannot be appended to without re-reading its header.
		// Better to end cleanly than to silently record into a second file nobody polls.
		var filePath = intent?.GetStringExtra(AndroidRecordingService.FilePathExtra);
		if (filePath is null)
		{
			state.MarkStopped();
			StopSelf();
			return StartCommandResult.NotSticky;
		}

		try
		{
			lock (_writerGate)
			{
				_writer = new WavWriter(File.Create(filePath));
			}
		}
		catch (IOException)
		{
			// No storage, no recording. Stop rather than capture into nothing.
			state.MarkStopped();
			StopSelf();
			return StartCommandResult.NotSticky;
		}

		_audioCapture = services.GetRequiredService<IAudioCapture>();
		_onChunk = WriteChunk;
		_audioCapture.ChunkCaptured += _onChunk;
		_audioCapture.StartCapture();

		return StartCommandResult.NotSticky;
	}

	private void WriteChunk(byte[] chunk)
	{
		lock (_writerGate)
		{
			_writer?.Write(chunk);
		}
	}

	public override void OnDestroy()
	{
		// Order matters: stop the capture loop first so no further chunks are produced,
		// then unsubscribe, then close the writer — which is what patches the RIFF length
		// fields and makes the file playable.
		_audioCapture?.StopCapture();

		if (_audioCapture is not null && _onChunk is not null)
		{
			_audioCapture.ChunkCaptured -= _onChunk;
		}
		_onChunk = null;
		_audioCapture = null;

		lock (_writerGate)
		{
			_writer?.Dispose();
			_writer = null;
		}

		IPlatformApplication.Current!.Services.GetRequiredService<RecordingState>().MarkStopped();

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
