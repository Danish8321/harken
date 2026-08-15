using System.Net.Http.Json;
using System.Text;
using System.Threading.Channels;
using Microsoft.AspNetCore.SignalR.Client;
using Harken.Core.Contracts;
using Harken.Mobile.Services;

namespace Harken.Mobile.Pages;

public partial class CapturePage : ContentPage, IDisposable
{
	private readonly IRecordingService _recordingService;
	private readonly IAudioCapture _audioCapture;
	private readonly AppSettings _appSettings;
	private readonly HttpClient _httpClient = new();

	private readonly StringBuilder _finalText = new();
	private string _partialText = "";

	private HubConnection? _connection;
	private Channel<byte[]>? _channel;
	private CancellationTokenSource? _streamCts;
	private Task? _streamTask;
	private Action<byte[]>? _chunkHandler;

	public Guid? SessionId { get; private set; }

	public CapturePage(IRecordingService recordingService, IAudioCapture audioCapture, AppSettings appSettings)
	{
		InitializeComponent();
		_recordingService = recordingService;
		_audioCapture = audioCapture;
		_appSettings = appSettings;
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

	private async void OnStartClicked(object? sender, EventArgs e)
	{
		if (!AppSettings.TryValidate(_appSettings.BaseUrl, out var error))
		{
			await DisplayAlertAsync("Invalid Settings", error, "OK");
			return;
		}

		_finalText.Clear();
		_partialText = "";
		CaptionLabel.Text = "";
		SessionId = null;

		_connection = new HubConnectionBuilder()
			.WithUrl($"{_appSettings.BaseUrl}/hub/captions")
			.Build();

		_connection.On<Guid>("SessionStarted", id =>
		{
			SessionId = id;
		});

		_connection.On<CaptionUpdate>("ReceiveCaption", update =>
		{
			MainThread.BeginInvokeOnMainThread(() =>
			{
				if (update.IsFinal)
				{
					_finalText.AppendLine(update.Text);
					_partialText = "";
				}
				else
				{
					_partialText = update.Text;
				}

				CaptionLabel.Text = _finalText + _partialText;
			});
		});

		await _connection.StartAsync();
		StatusLabel.Text = "Connected. Recording...";

		_channel = Channel.CreateUnbounded<byte[]>();
		_streamCts = new CancellationTokenSource();

		_chunkHandler = chunk => _channel.Writer.TryWrite(chunk);
		_audioCapture.ChunkCaptured += _chunkHandler;

		_recordingService.StartRecording();

		_streamTask = _connection.InvokeAsync("StreamAudio", _channel.Reader.ReadAllAsync(_streamCts.Token), _streamCts.Token);

		StartButton.IsEnabled = false;
		StopButton.IsEnabled = true;
	}

	private async void OnStopClicked(object? sender, EventArgs e)
	{
		_recordingService.StopRecording();

		if (_chunkHandler is not null)
		{
			_audioCapture.ChunkCaptured -= _chunkHandler;
			_chunkHandler = null;
		}

		_channel?.Writer.Complete();

		if (_streamTask is not null)
		{
			await _streamTask;
		}

		if (_connection is not null)
		{
			await _connection.StopAsync();
			await _connection.DisposeAsync();
			_connection = null;
		}

		_streamCts?.Dispose();
		_streamCts = null;
		_streamTask = null;
		_channel = null;

		StatusLabel.Text = "Stopped.";
		StartButton.IsEnabled = true;
		StopButton.IsEnabled = false;
		SummarizeButton.IsEnabled = SessionId is not null;
	}

	private async void OnSummarizeClicked(object? sender, EventArgs e)
	{
		if (SessionId is null)
		{
			return;
		}

		SummarizeButton.IsEnabled = false;
		StatusLabel.Text = "Summarizing...";

		try
		{
			var response = await _httpClient.PostAsync($"{_appSettings.BaseUrl}/sessions/{SessionId}/summary", content: null);
			response.EnsureSuccessStatusCode();

			var summary = await response.Content.ReadFromJsonAsync<SessionSummary>();
			SummaryEditor.Text = summary?.Summary ?? "";
			StatusLabel.Text = "Summary ready.";
		}
		catch (Exception ex)
		{
			await DisplayAlertAsync("Summarize Failed", ex.Message, "OK");
			StatusLabel.Text = "Summarize failed.";
		}
		finally
		{
			SummarizeButton.IsEnabled = true;
		}
	}

	public void Dispose()
	{
		if (_chunkHandler is not null)
		{
			_audioCapture.ChunkCaptured -= _chunkHandler;
			_chunkHandler = null;
		}

		_streamCts?.Dispose();
		GC.SuppressFinalize(this);
	}
}
