using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using NAudio.Wave;
using Harken.Core;
using Harken.Core.Contracts;

// Slice 04 Task 7: record from the mic, upload, poll for the transcript, print it, offer
// Summarize. Recording itself needs no token (WaveIn writes to a local file); only the
// upload step does, so an expired token means "log in again and retry the upload", never
// a lost recording.

const string BaseUrl = "http://localhost:5057";
const int SampleRateHz = 16_000; // Whisper's native rate — recording at it skips a resample.

using var http = new HttpClient();

// --- Sign in ---
Console.Write("Email: ");
var email = Console.ReadLine();
var password = ReadPassword();

if (string.IsNullOrWhiteSpace(email) || string.IsNullOrEmpty(password))
{
    Console.WriteLine("Email and password are required.");
    return 1;
}

if (!await SignInAsync(email, password))
{
    return 1;
}

// The password is out of scope from here on: only the token is held, and neither is
// written to disk or logged.
password = "";

Console.WriteLine($"Signed in as {email}.");

// --- Main menu ---
while (true)
{
    Console.WriteLine();
    Console.WriteLine("[R]ecord a new session   [L]ist sessions   [Q]uit");
    Console.Write("> ");
    var choice = Console.ReadLine()?.Trim().ToUpperInvariant();

    switch (choice)
    {
        case "R":
            await RecordAndUploadAsync();
            break;
        case "L":
            await ListAndSummarizeAsync();
            break;
        case "Q":
        case null:
            return 0;
        default:
            Console.WriteLine("Unrecognized choice.");
            break;
    }
}

// --- Recording ---
async Task RecordAndUploadAsync()
{
    var wavPath = Path.Combine(Path.GetTempPath(), $"harken-recording-{Guid.NewGuid():N}.wav");

    Console.WriteLine();
    Console.WriteLine("Recording... press ENTER to stop.");

    var startedAt = DateTimeOffset.UtcNow;
    using (var waveIn = new WaveInEvent { WaveFormat = new WaveFormat(SampleRateHz, bits: 16, channels: 1) })
    using (var writer = new WaveFileWriter(wavPath, waveIn.WaveFormat))
    {
        waveIn.DataAvailable += (_, e) => writer.Write(e.Buffer, 0, e.BytesRecorded);

        var stopped = new TaskCompletionSource();
        waveIn.RecordingStopped += (_, _) => stopped.TrySetResult();

        waveIn.StartRecording();

        var stopwatch = System.Diagnostics.Stopwatch.StartNew();
        var stopSignal = Task.Run(Console.ReadLine);
        while (!stopSignal.IsCompleted)
        {
            Console.Write($"\r  {stopwatch.Elapsed:mm\\:ss}");
            await Task.Delay(200);
        }
        Console.WriteLine();

        waveIn.StopRecording();
        await stopped.Task;
    }

    var duration = new FileInfo(wavPath).Length is > 44
        ? TimeSpan.FromSeconds((new FileInfo(wavPath).Length - 44) / (double)(SampleRateHz * 2))
        : TimeSpan.Zero;
    Console.WriteLine($"Recorded {duration:mm\\:ss}.");

    // --- Upload (token required from here on) ---
    Guid sessionId;
    while (true)
    {
        try
        {
            sessionId = await UploadAsync(wavPath, startedAt);
            break;
        }
        catch (UnauthorizedAccessException)
        {
            Console.WriteLine("Session expired. Sign in again to upload this recording.");
            Console.Write("Email: ");
            var retryEmail = Console.ReadLine();
            var retryPassword = ReadPassword();
            if (string.IsNullOrWhiteSpace(retryEmail) || string.IsNullOrEmpty(retryPassword)
                || !await SignInAsync(retryEmail, retryPassword))
            {
                Console.WriteLine("Login failed. The recording is still on disk at:");
                Console.WriteLine($"  {wavPath}");
                return;
            }
            retryPassword = "";
        }
    }

    File.Delete(wavPath);

    Console.WriteLine("Uploaded. Transcribing...");
    var detail = await PollForTranscriptAsync(sessionId);
    if (detail is null)
    {
        return;
    }

    PrintTranscript(detail);
    await MaybeSummarizeAsync(sessionId);
}

async Task<Guid> UploadAsync(string wavPath, DateTimeOffset startedAt)
{
    using var content = new MultipartFormDataContent();
    await using var audioStream = File.OpenRead(wavPath);
    var audioContent = new StreamContent(audioStream);
    audioContent.Headers.ContentType = new MediaTypeHeaderValue("audio/wav");
    content.Add(audioContent, "audio", "recording.wav");
    content.Add(new StringContent(nameof(AudioSource.Microphone)), "source");

    using var response = await http.PostAsync($"{BaseUrl}/sessions", content);
    if (response.StatusCode == HttpStatusCode.Unauthorized)
    {
        throw new UnauthorizedAccessException();
    }

    response.EnsureSuccessStatusCode();
    var created = await response.Content.ReadFromJsonAsync<SessionListItem>();
    return created?.Id ?? throw new InvalidOperationException("Upload succeeded but returned no session id.");
}

async Task<SessionDetail?> PollForTranscriptAsync(Guid sessionId)
{
    // Decision 3 (slice-04): the client polls rather than holding a connection open
    // across a job that can take minutes.
    var stopwatch = System.Diagnostics.Stopwatch.StartNew();
    while (true)
    {
        using var response = await http.GetAsync($"{BaseUrl}/sessions/{sessionId}");
        if (response.StatusCode == HttpStatusCode.Unauthorized)
        {
            Console.WriteLine("Session expired — sign in again and check this session from the list.");
            return null;
        }

        response.EnsureSuccessStatusCode();
        var detail = await response.Content.ReadFromJsonAsync<SessionDetail>();
        if (detail is null)
        {
            Console.WriteLine("Session not found.");
            return null;
        }

        if (detail.TranscriptionStatus == TranscriptionStatus.Succeeded)
        {
            Console.WriteLine($"Transcribed in {stopwatch.Elapsed:mm\\:ss}.");
            return detail;
        }

        if (detail.TranscriptionStatus == TranscriptionStatus.Failed)
        {
            Console.WriteLine($"Transcription failed: {detail.TranscriptionFailureReason}");
            return detail;
        }

        Console.Write($"\r  {detail.TranscriptionStatus} ({stopwatch.Elapsed:mm\\:ss})");
        await Task.Delay(1000);
    }
}

void PrintTranscript(SessionDetail detail)
{
    Console.WriteLine();
    if (detail.Segments.Count == 0)
    {
        Console.WriteLine("(no speech recognized)");
        return;
    }

    foreach (var segment in detail.Segments)
    {
        Console.WriteLine($"[{segment.Offset:mm\\:ss}] {segment.Text}");
    }
}

async Task MaybeSummarizeAsync(Guid sessionId)
{
    Console.Write("Summarize this session? (y/N) ");
    if (Console.ReadLine()?.Trim().ToUpperInvariant() != "Y")
    {
        return;
    }

    using var response = await http.PostAsync($"{BaseUrl}/sessions/{sessionId}/summary", null);
    if (response.StatusCode == HttpStatusCode.Unauthorized)
    {
        Console.WriteLine("Session expired — sign in again.");
        return;
    }

    if (response.StatusCode == HttpStatusCode.BadGateway)
    {
        Console.WriteLine("Summarize failed: the model host is unreachable. Is Ollama running?");
        return;
    }

    response.EnsureSuccessStatusCode();
    var summary = await response.Content.ReadFromJsonAsync<SessionSummary>();
    Console.WriteLine();
    Console.WriteLine(summary?.Summary);
}

// --- Sessions list + summarize (pre-Task-7 flow, kept as-is) ---
async Task ListAndSummarizeAsync()
{
    List<SessionListItem>? sessions;
    using (var response = await http.GetAsync($"{BaseUrl}/sessions"))
    {
        if (response.StatusCode == HttpStatusCode.Unauthorized)
        {
            Console.WriteLine("Session expired — sign in again.");
            return;
        }

        response.EnsureSuccessStatusCode();
        sessions = await response.Content.ReadFromJsonAsync<List<SessionListItem>>();
    }

    if (sessions is null || sessions.Count == 0)
    {
        Console.WriteLine("No sessions yet.");
        return;
    }

    Console.WriteLine();
    for (var i = 0; i < sessions.Count; i++)
    {
        var s = sessions[i];
        var summarized = s.HasSummary ? "summarized" : "no summary";
        Console.WriteLine($"[{i + 1}] {s.StartedAt:yyyy-MM-dd HH:mm}  {s.Source}  {s.SegmentCount} segments  ({summarized})");
    }

    Console.WriteLine();
    Console.Write("Summarize which? (number, or ENTER to go back) ");
    if (!int.TryParse(Console.ReadLine(), out var choice) || choice < 1 || choice > sessions.Count)
    {
        return;
    }

    await MaybeSummarizeForcedAsync(sessions[choice - 1].Id);
}

async Task MaybeSummarizeForcedAsync(Guid sessionId)
{
    using var response = await http.PostAsync($"{BaseUrl}/sessions/{sessionId}/summary", null);
    if (response.StatusCode == HttpStatusCode.Unauthorized)
    {
        Console.WriteLine("Session expired — sign in again.");
        return;
    }

    if (response.StatusCode == HttpStatusCode.BadGateway)
    {
        Console.WriteLine("Summarize failed: the model host is unreachable. Is Ollama running?");
        return;
    }

    response.EnsureSuccessStatusCode();
    var summary = await response.Content.ReadFromJsonAsync<SessionSummary>();
    Console.WriteLine();
    Console.WriteLine(summary?.Summary);
}

async Task<bool> SignInAsync(string signInEmail, string signInPassword)
{
    using var response = await http.PostAsJsonAsync($"{BaseUrl}/auth/login", new LoginRequest(signInEmail, signInPassword));
    if (response.StatusCode == HttpStatusCode.Unauthorized)
    {
        Console.WriteLine("Login failed: invalid email or password.");
        return false;
    }

    response.EnsureSuccessStatusCode();
    var auth = await response.Content.ReadFromJsonAsync<TokenResponse>();
    if (auth is null)
    {
        Console.WriteLine("Login failed: no token returned.");
        return false;
    }

    http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", auth.Token);
    return true;
}

// Reads a password without echoing it, so it never appears on screen or in a
// scrollback buffer.
static string ReadPassword()
{
    Console.Write("Password: ");

    if (Console.IsInputRedirected)
    {
        // No console to suppress echo on (piped input): read the line as-is.
        var line = Console.ReadLine() ?? "";
        Console.WriteLine();
        return line;
    }

    var builder = new System.Text.StringBuilder();
    while (true)
    {
        var key = Console.ReadKey(intercept: true);
        if (key.Key == ConsoleKey.Enter)
        {
            Console.WriteLine();
            return builder.ToString();
        }

        if (key.Key == ConsoleKey.Backspace)
        {
            if (builder.Length > 0)
            {
                builder.Length--;
            }

            continue;
        }

        if (!char.IsControl(key.KeyChar))
        {
            builder.Append(key.KeyChar);
        }
    }
}
