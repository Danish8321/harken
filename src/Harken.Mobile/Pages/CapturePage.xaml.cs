using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using Harken.Core.Contracts;
using Harken.Mobile.Services;

namespace Harken.Mobile.Pages;

/// <summary>
/// Reads the sessions this account owns and summarizes one.
/// Recording returns in slice 05 as capture-to-file plus upload (ADR-0007); until then
/// the page deliberately offers nothing it cannot actually do.
/// </summary>
public partial class CapturePage : ContentPage, IDisposable
{
	private readonly AppSettings _appSettings;
	private readonly AuthService _authService;
	private readonly HttpClient _httpClient = new();

	public Guid? SessionId { get; private set; }

	public CapturePage(AppSettings appSettings, AuthService authService)
	{
		InitializeComponent();
		_appSettings = appSettings;
		_authService = authService;
	}

	private sealed record SessionRow(Guid Id, string Display);

	protected override async void OnAppearing()
	{
		base.OnAppearing();

		// No token means no session: every path from here needs one.
		if (await _authService.GetTokenAsync() is null)
		{
			await Routes.GoToLoginAsync();
			return;
		}

		await LoadSessionsAsync();
	}

	/// <summary>
	/// Clears the stored token and returns to login. Called whenever the API says 401 —
	/// the token has expired or been revoked, and nothing else will succeed with it.
	/// </summary>
	private async Task HandleUnauthorizedAsync()
	{
		_authService.SignOut();
		StatusLabel.Text = "Session expired. Please sign in again.";
		await Routes.GoToLoginAsync();
	}

	private async Task<HttpRequestMessage?> AuthorizedRequestAsync(HttpMethod method, string path)
	{
		var token = await _authService.GetTokenAsync();
		if (token is null)
		{
			await HandleUnauthorizedAsync();
			return null;
		}

		var request = new HttpRequestMessage(method, $"{_appSettings.BaseUrl}{path}");
		request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
		return request;
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
			using var request = await AuthorizedRequestAsync(HttpMethod.Get, "/sessions");
			if (request is null)
			{
				return;
			}

			using var response = await _httpClient.SendAsync(request);
			if (response.StatusCode == HttpStatusCode.Unauthorized)
			{
				await HandleUnauthorizedAsync();
				return;
			}

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
			using var request = await AuthorizedRequestAsync(HttpMethod.Post, $"/sessions/{SessionId}/summary");
			if (request is null)
			{
				return;
			}

			using var response = await _httpClient.SendAsync(request);
			if (response.StatusCode == HttpStatusCode.Unauthorized)
			{
				await HandleUnauthorizedAsync();
				return;
			}

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
