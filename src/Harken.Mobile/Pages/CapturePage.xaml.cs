using System.Net;
using System.Net.Http.Json;
using Harken.Core.Contracts;
using Harken.Mobile.Services;

namespace Harken.Mobile.Pages;

/// <summary>
/// Reads the sessions on the backend and summarizes one. ADR-0009: MVP 1 has no
/// authentication, so every request is anonymous — there is no token to attach.
/// Recording returns in slice 06 as capture-to-file plus upload (ADR-0007); until then
/// the page deliberately offers nothing it cannot actually do.
/// </summary>
public partial class CapturePage : ContentPage, IDisposable
{
	private readonly AppSettings _appSettings;
	private readonly HttpClient _httpClient = new();

	public Guid? SessionId { get; private set; }

	public CapturePage(AppSettings appSettings)
	{
		InitializeComponent();
		_appSettings = appSettings;
	}

	private sealed record SessionRow(Guid Id, string Display);

	protected override async void OnAppearing()
	{
		base.OnAppearing();
		await LoadSessionsAsync();
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

	public void Dispose()
	{
		_httpClient.Dispose();
		GC.SuppressFinalize(this);
	}
}
