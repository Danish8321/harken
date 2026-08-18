using System.Globalization;
using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using Harken.Core;
using Harken.Core.Audio;
using Harken.Core.Contracts;
using Harken.Mobile.Services;

namespace Harken.Mobile.Pages;

/// <summary>
/// Records to a local WAV file, then reads the sessions on the backend and summarizes one.
/// ADR-0009: MVP 1 has no authentication, so every request is anonymous — there is no token
/// to attach. Uploading the recording lands in slice-06 Task 4; until then a finished
/// recording is reported by path rather than sent anywhere.
/// </summary>
public partial class CapturePage : ContentPage, IDisposable
{
	/// <summary>
	/// How long to watch a transcription before handing the user back their page. The job
	/// keeps running on the backend either way — this bounds the wait, not the work, so a
	/// long recording is picked up by Refresh rather than pinning the page indefinitely.
	/// </summary>
	private static readonly TimeSpan TranscriptionPollTimeout = TimeSpan.FromMinutes(10);

	/// <summary>Runs the orphan recovery scan at most once per app process — the pages are
	/// transient (DI), but the files it looks for only ever appear once per crash, not once
	/// per navigation.</summary>
	private static bool s_orphanScanDone;

	private readonly AppSettings _appSettings;
	private readonly IRecordingService _recordingService;
	private readonly HttpClient _httpClient = new();
	private readonly IDispatcherTimer _elapsedTimer;

	public Guid? SessionId { get; private set; }

	public CapturePage(AppSettings appSettings, IRecordingService recordingService)
	{
		InitializeComponent();
		_appSettings = appSettings;
		_recordingService = recordingService;

		// Ticks only while recording. Reads elapsed off the recording service rather than
		// counting its own ticks, so a missed or delayed tick cannot drift the display away
		// from the length of the file actually being written.
		_elapsedTimer = Dispatcher.CreateTimer();
		_elapsedTimer.Interval = TimeSpan.FromSeconds(1);
		_elapsedTimer.Tick += (_, _) => ElapsedLabel.Text = Format(_recordingService.Elapsed);

		// Subscribed for the page's whole life, not just while it is visible: an auto-stop
		// can land with the screen locked, and it must still upload (ADR-0003, ADR-0007).
		_recordingService.Completed += OnRecordingCompleted;
	}

	private static string Format(TimeSpan elapsed) =>
		elapsed.TotalHours >= 1
			? elapsed.ToString(@"h\:mm\:ss", CultureInfo.InvariantCulture)
			: elapsed.ToString(@"mm\:ss", CultureInfo.InvariantCulture);

	private async void OnRecordClicked(object? sender, EventArgs e)
	{
		if (_recordingService.IsRecording)
		{
			return;
		}

		// Asked at first record rather than at launch: a permission prompt makes sense to a
		// user who just tapped Record, and means nothing to one who just opened the app.
		var microphone = await Permissions.CheckStatusAsync<Permissions.Microphone>();
		if (microphone != PermissionStatus.Granted)
		{
			microphone = await Permissions.RequestAsync<Permissions.Microphone>();
		}

		if (microphone != PermissionStatus.Granted)
		{
			// Denied is a normal answer, not a crash. Say what is blocked and how to undo it.
			await DisplayAlertAsync(
				"Microphone Needed",
				"Harken cannot record without microphone access. Grant it in Settings → Apps → Harken → Permissions, then try again.",
				"OK");
			StatusLabel.Text = "Microphone permission denied.";
			return;
		}

		// Best-effort, and deliberately not blocking: the recording still runs without it,
		// but ADR-0003 makes the notification the only control surface on a locked screen,
		// so it is worth asking before the user needs it.
		if (await Permissions.CheckStatusAsync<Permissions.PostNotifications>() != PermissionStatus.Granted)
		{
			await Permissions.RequestAsync<Permissions.PostNotifications>();
		}

		try
		{
			_recordingService.StartRecording();
		}
		catch (Exception ex)
		{
			await DisplayAlertAsync("Could Not Start Recording", ex.Message, "OK");
			StatusLabel.Text = "Recording failed to start.";
			return;
		}

		RecordButton.IsEnabled = false;
		StopButton.IsEnabled = true;
		ElapsedLabel.Text = Format(TimeSpan.Zero);
		_elapsedTimer.Start();
		StatusLabel.Text = "Recording…";
	}

	private async void OnStopClicked(object? sender, EventArgs e)
	{
		if (!_recordingService.IsRecording)
		{
			return;
		}

		// Only asks the service to stop. The upload hangs off Completed instead, because a
		// silence timeout or session cap must upload exactly the same way — and routing both
		// through one trigger is what keeps a Stop tap racing an auto-stop from uploading the
		// same file twice.
		_recordingService.StopRecording();
		StatusLabel.Text = "Stopping…";
	}

	private void OnRecordingCompleted(RecordingCompleted completed)
	{
		// Raised from the foreground service's OnDestroy, not on the UI thread.
		Dispatcher.Dispatch(async () =>
		{
			_elapsedTimer.Stop();
			RecordButton.IsEnabled = true;
			StopButton.IsEnabled = false;
			ElapsedLabel.Text = Format(TimeSpan.Zero);

			// A start that failed before capture began still reports completion, leaving
			// either no file or a bare header. Neither is worth uploading.
			if (!File.Exists(completed.FilePath) || new FileInfo(completed.FilePath).Length <= WavWriter.HeaderLength)
			{
				StatusLabel.Text = "Recording stopped before any audio was captured.";
				return;
			}

			if (completed.StopReason != RecordingStopReason.None)
			{
				await DisplayAlertAsync("Recording Ended", DescribeStop(completed.StopReason), "OK");
			}

			await UploadAndTranscribeAsync(completed.FilePath, completed.RecordingId);
		});
	}

	private static string DescribeStop(RecordingStopReason reason) => reason switch
	{
		RecordingStopReason.SilenceTimeout =>
			$"Nothing was heard for {RecordingForegroundService.SilenceTimeout.TotalMinutes:0} minutes, so recording stopped. Uploading what was captured.",
		RecordingStopReason.SessionCap =>
			$"Recording reached its {RecordingForegroundService.SessionCap.TotalHours:0}-hour limit and stopped. Uploading what was captured.",
		_ => "Recording stopped.",
	};

	/// <summary>
	/// Mirrors <c>Harken.Console</c>'s flow: upload the WAV, poll until transcription
	/// reaches a terminal state, show the transcript (ADR-0007 — the backend does all the
	/// transcribing; the phone only captures and uploads).
	/// </summary>
	private async Task UploadAndTranscribeAsync(string path, Guid recordingId)
	{
		if (!AppSettings.TryValidate(_appSettings.BaseUrl, out var error))
		{
			await DisplayAlertAsync("Invalid Settings", error, "OK");
			KeepRecording(path, "the backend address is not configured");
			return;
		}

		var sizeKb = new FileInfo(path).Length / 1024;
		StatusLabel.Text = $"Uploading {sizeKb} KB…";

		Guid sessionId;
		try
		{
			sessionId = await UploadAsync(path, recordingId);
		}
		catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
		{
			KeepRecording(path, ex.Message);
			return;
		}

		// Only now is the recording safe to remove: it exists on the backend. A retry story
		// for the failure path above lands with the client recording id in Task 7.
		TryDelete(path);

		SessionId = sessionId;
		SummarizeButton.IsEnabled = true;
		StatusLabel.Text = "Uploaded. Transcribing…";

		SessionDetail? detail;
		try
		{
			detail = await PollForTranscriptAsync(sessionId);
		}
		catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException)
		{
			StatusLabel.Text = $"Uploaded, but polling failed: {ex.Message}. Refresh to check later.";
			return;
		}

		if (detail is null)
		{
			StatusLabel.Text = "Uploaded, but the session could not be read back.";
			return;
		}

		if (detail.TranscriptionStatus == TranscriptionStatus.Failed)
		{
			TranscriptEditor.Text = "";
			StatusLabel.Text = $"Transcription failed: {detail.TranscriptionFailureReason}";
			return;
		}

		TranscriptEditor.Text = detail.Segments.Count == 0
			? "(no speech recognized)"
			: string.Join(
				Environment.NewLine,
				detail.Segments.Select(s => $"[{s.Offset.ToString(@"mm\:ss", CultureInfo.InvariantCulture)}] {s.Text}"));

		StatusLabel.Text = $"Transcribed — {detail.Segments.Count} segment(s).";
		await LoadSessionsAsync();
	}

	private async Task<Guid> UploadAsync(string path, Guid recordingId)
	{
		using var content = new MultipartFormDataContent();
		await using var audioStream = File.OpenRead(path);
		var audioContent = new StreamContent(audioStream);
		audioContent.Headers.ContentType = new MediaTypeHeaderValue("audio/wav");
		content.Add(audioContent, "audio", "recording.wav");
		content.Add(new StringContent(nameof(AudioSource.Microphone)), "source");

		// Generated when recording started, so a retry after a dropped connection resolves to
		// the same session rather than a duplicate. Retries are routine on a phone.
		content.Add(new StringContent(recordingId.ToString()), "recordingId");

		using var response = await _httpClient.PostAsync($"{_appSettings.BaseUrl}/sessions", content);
		response.EnsureSuccessStatusCode();

		var created = await response.Content.ReadFromJsonAsync<SessionListItem>();
		return created?.Id ?? throw new HttpRequestException("Upload succeeded but returned no session id.");
	}

	private async Task<SessionDetail?> PollForTranscriptAsync(Guid sessionId)
	{
		// Polling rather than a held-open connection: transcription can take minutes, and a
		// phone's connection will not survive that (slice-04 Decision 3).
		var startedAt = DateTimeOffset.UtcNow;
		while (DateTimeOffset.UtcNow - startedAt < TranscriptionPollTimeout)
		{
			using var response = await _httpClient.GetAsync($"{_appSettings.BaseUrl}/sessions/{sessionId}");
			if (response.StatusCode == HttpStatusCode.NotFound)
			{
				return null;
			}

			response.EnsureSuccessStatusCode();
			var detail = await response.Content.ReadFromJsonAsync<SessionDetail>();
			if (detail is null)
			{
				return null;
			}

			if (detail.TranscriptionStatus is TranscriptionStatus.Succeeded or TranscriptionStatus.Failed)
			{
				return detail;
			}

			var waited = DateTimeOffset.UtcNow - startedAt;
			StatusLabel.Text = $"{detail.TranscriptionStatus} ({Format(waited)})…";
			await Task.Delay(1000);
		}

		StatusLabel.Text = "Still transcribing — refresh later to read it.";
		return null;
	}

	/// <summary>
	/// Upload failed, so the recording is all that is left of it. Say where it is instead of
	/// deleting it — an unrecoverable loss is worse than a file the user has to find.
	/// </summary>
	private void KeepRecording(string path, string reason)
	{
		StatusLabel.Text = $"Upload failed ({reason}). The recording is kept at {path}";
	}

	private static void TryDelete(string path)
	{
		try
		{
			File.Delete(path);
		}
		catch (IOException)
		{
			// A leftover file costs storage; failing the flow over it would cost the transcript.
		}
	}


	private sealed record SessionRow(Guid Id, string Display);

	protected override async void OnAppearing()
	{
		base.OnAppearing();
		SyncRecordingControls();
		await RecoverOrphanedRecordingsAsync();
		await LoadSessionsAsync();
	}

	/// <summary>
	/// A process killed mid-capture never runs the foreground service's <c>OnDestroy</c>, so
	/// the WAV it was writing is left behind with a zeroed header and never reaches
	/// <see cref="OnRecordingCompleted"/> or a Completed event — nothing else finds it.
	/// One scan per process, on the first page appearance while nothing is actively
	/// recording, patches each leftover file's header from its size on disk (or leaves it
	/// alone if the header already matches — a retry of an upload that failed rather than a
	/// crash) and uploads it exactly like a normal completion.
	/// </summary>
	private async Task RecoverOrphanedRecordingsAsync()
	{
		if (s_orphanScanDone || _recordingService.IsRecording)
		{
			return;
		}
		s_orphanScanDone = true;

		string[] candidates;
		try
		{
			candidates = Directory.GetFiles(FileSystem.AppDataDirectory, "*.wav");
		}
		catch (IOException)
		{
			return;
		}

		foreach (var path in candidates)
		{
			// Recording files are named "{recordingId:N}.wav" (AndroidRecordingService); a
			// filename that doesn't parse isn't one of ours.
			if (!Guid.TryParseExact(Path.GetFileNameWithoutExtension(path), "N", out var recordingId))
			{
				continue;
			}

			try
			{
				WavWriter.RepairHeader(path);
			}
			catch (IOException)
			{
				// Still open elsewhere, or gone by the time we got to it — leave it for the
				// next scan rather than fail the whole recovery pass over one file.
				continue;
			}

			if (!File.Exists(path) || new FileInfo(path).Length <= WavWriter.HeaderLength)
			{
				TryDelete(path);
				continue;
			}

			StatusLabel.Text = "Recovering a recording from before the app closed…";
			await UploadAndTranscribeAsync(path, recordingId);
		}
	}

	/// <summary>
	/// The foreground service outlives this page — recording survives navigating away and,
	/// per ADR-0003, the screen being locked. So the controls are derived from the service's
	/// state on every appearance rather than from what this instance last did.
	/// </summary>
	private void SyncRecordingControls()
	{
		var recording = _recordingService.IsRecording;

		RecordButton.IsEnabled = !recording;
		StopButton.IsEnabled = recording;
		ElapsedLabel.Text = Format(_recordingService.Elapsed);

		if (recording)
		{
			_elapsedTimer.Start();
		}
		else
		{
			_elapsedTimer.Stop();
		}
	}

	private async Task LoadSessionsAsync()
	{
		if (!AppSettings.TryValidate(_appSettings.BaseUrl, out var error))
		{
			await DisplayAlertAsync("Invalid Settings", error, "OK");
			return;
		}

		StatusLabel.Text = "Loading sessions...";

		try
		{
			using var response = await _httpClient.GetAsync($"{_appSettings.BaseUrl}/sessions");
			response.EnsureSuccessStatusCode();

			var sessions = await response.Content.ReadFromJsonAsync<List<SessionListItem>>() ?? [];
			SessionsView.ItemsSource = sessions
				.Select(s => new SessionRow(
					s.Id,
					$"{s.StartedAt:yyyy-MM-dd HH:mm}  ·  {s.Source}  ·  {s.SegmentCount} segments{(s.HasSummary ? "  ·  summarized" : "")}"))
				.ToList();

			StatusLabel.Text = sessions.Count == 0
				? "No sessions yet."
				: $"{sessions.Count} session(s).";
		}
		catch (Exception ex)
		{
			await DisplayAlertAsync("Could not load sessions", ex.Message, "OK");
			StatusLabel.Text = "Load failed.";
		}
	}

	private async void OnRefreshClicked(object? sender, EventArgs e) => await LoadSessionsAsync();

	private void OnSessionSelected(object? sender, SelectionChangedEventArgs e)
	{
		SessionId = e.CurrentSelection.Count > 0 ? (e.CurrentSelection[0] as SessionRow)?.Id : null;
		SummarizeButton.IsEnabled = SessionId is not null;
		SummaryEditor.Text = "";
		// The transcript on screen belongs to the recording that was just uploaded, not to
		// whatever was tapped. Clearing beats showing one session's text under another's.
		TranscriptEditor.Text = "";
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
			using var response = await _httpClient.PostAsync($"{_appSettings.BaseUrl}/sessions/{SessionId}/summary", null);
			if (response.StatusCode == HttpStatusCode.BadGateway)
			{
				// The summarize agent talks to Ollama on the backend host; a 502 means the
				// model host is the problem, not the transcript.
				StatusLabel.Text = "Summarize failed: the model host is unreachable.";
				return;
			}

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
			SummarizeButton.IsEnabled = SessionId is not null;
		}
	}

	protected override void OnDisappearing()
	{
		// Stop the display, not the recording — the foreground service is the whole reason
		// capture continues once this page is gone.
		_elapsedTimer.Stop();
		base.OnDisappearing();
	}

	public void Dispose()
	{
		_elapsedTimer.Stop();
		// The recording service is a singleton and outlives this page — leaving the handler
		// attached would keep a dead page alive and upload through it.
		_recordingService.Completed -= OnRecordingCompleted;
		_httpClient.Dispose();
		GC.SuppressFinalize(this);
	}
}
