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

	/// <summary>
	/// Sent by the notification's Stop button. Routed back to this service rather than to a
	/// broadcast receiver: the service is already declared and already owns the writer, so a
	/// receiver would be a second component that only forwards.
	/// </summary>
	public const string ActionStop = "harken.action.STOP";

	/// <summary>Guards the writer: chunks arrive on the capture thread while OnDestroy runs
	/// on the main thread, and a write landing after Dispose would throw out there where
	/// nothing catches it.</summary>
	private readonly object _writerGate = new();

	/// <summary>
	/// ADR-0007 moved both limits from the server to the client. Values locked in the
	/// slice-06 plan: on a phone these bound battery and storage, not spend.
	/// </summary>
	public static readonly TimeSpan SilenceTimeout = TimeSpan.FromMinutes(5);
	public static readonly TimeSpan SessionCap = TimeSpan.FromHours(3);

	private WavWriter? _writer;
	private SilenceDetector? _silenceDetector;
	private IAudioCapture? _audioCapture;
	private Action<byte[]>? _onChunk;
	private RecordingStopReason _stopReason = RecordingStopReason.None;

	public override IBinder? OnBind(Intent? intent) => null;

	public override StartCommandResult OnStartCommand(Intent? intent, StartCommandFlags flags, int startId)
	{
		// Checked before anything else: the stop intent carries no file path, and letting it
		// fall through would run the no-path branch below, which reports the recording
		// finished while the writer is still open and the header still unpatched.
		if (intent?.Action == ActionStop)
		{
			StopSelf();
			return StartCommandResult.NotSticky;
		}

		CreateNotificationChannelIfNeeded();
		StartForeground(NotificationId, BuildNotification());

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
				_silenceDetector = new SilenceDetector(SilenceTimeout, SessionCap);
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
		RecordingStopReason reason;

		lock (_writerGate)
		{
			if (_writer is null)
			{
				return;
			}

			_writer.Write(chunk);
			reason = _silenceDetector?.Add(chunk) ?? RecordingStopReason.None;

			if (reason == RecordingStopReason.None)
			{
				return;
			}

			// Recorded before stopping so OnDestroy can report why. Nulling the detector
			// makes this fire once even if another chunk is already in flight.
			_stopReason = reason;
			_silenceDetector = null;
		}

		// StopSelf outside the lock: it runs OnDestroy, which takes the same lock to close
		// the writer, and taking it twice from this thread would be a deadlock waiting to
		// happen the moment either side stops being reentrant.
		StopSelf();
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
			_silenceDetector = null;
		}

		// After the writer is closed, so the file the handler uploads has a patched header.
		IPlatformApplication.Current!.Services.GetRequiredService<RecordingState>().MarkStopped(_stopReason);

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

	/// <summary>
	/// ADR-0003: with the screen locked this notification is the only surface the user can
	/// see or act on, which is the scenario the foreground service exists for. So it carries
	/// a working Stop control and a live elapsed time, not just a passive "recording" notice.
	/// </summary>
	private Notification BuildNotification()
	{
		var stopIntent = new Intent(this, typeof(RecordingForegroundService));
		stopIntent.SetAction(ActionStop);

		// Immutable is required from API 31 and wanted everywhere it exists: nothing
		// downstream should be able to rewrite this intent's target or action. It only
		// exists from API 23, hence the branch.
		var flags = PendingIntentFlags.UpdateCurrent;
		if (OperatingSystem.IsAndroidVersionAtLeast(23))
		{
			flags |= PendingIntentFlags.Immutable;
		}

		var stopPendingIntent = PendingIntent.GetService(this, requestCode: 0, stopIntent, flags);

		var builder = new NotificationCompat.Builder(this, ChannelId);
		builder.SetContentTitle("Harken");
		builder.SetContentText("Recording…");
		builder.SetSmallIcon(global::Android.Resource.Drawable.IcMediaPlay);
		builder.SetPriority(NotificationCompat.PriorityLow);
		builder.SetOngoing(true);

		// Android renders and ticks the counter itself from this timestamp. Re-posting the
		// notification once a second to redraw a clock would cost wakeups all recording long.
		builder.SetWhen(Java.Lang.JavaSystem.CurrentTimeMillis());
		builder.SetShowWhen(true);
		builder.SetUsesChronometer(true);

		builder.AddAction(global::Android.Resource.Drawable.IcMediaPause, "Stop", stopPendingIntent);

		// A foreground service with no notification is killed on the spot, so there is no
		// degraded mode to fall back to here.
		return builder.Build()
			?? throw new InvalidOperationException("Could not build the recording notification.");
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
