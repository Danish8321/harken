using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using Harken.Core.Contracts;

// Recording lives in slice 04 Task 7: capture to a WAV file, upload, poll, read the
// transcript. Until then this client covers what the API actually offers — signing in,
// listing the sessions you own, and summarizing one.

const string BaseUrl = "http://localhost:5057";

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

TokenResponse? auth;
using (var loginResponse = await http.PostAsJsonAsync($"{BaseUrl}/auth/login", new LoginRequest(email, password)))
{
    if (loginResponse.StatusCode == HttpStatusCode.Unauthorized)
    {
        Console.WriteLine("Login failed: invalid email or password.");
        return 1;
    }

    loginResponse.EnsureSuccessStatusCode();
    auth = await loginResponse.Content.ReadFromJsonAsync<TokenResponse>();
}

if (auth is null)
{
    Console.WriteLine("Login failed: no token returned.");
    return 1;
}

// The password is out of scope from here on: only the token is held, and neither is
// written to disk or logged.
password = "";
http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", auth.Token);

Console.WriteLine($"Signed in as {email}.");

// --- Sessions ---
List<SessionListItem>? sessions;
using (var response = await http.GetAsync($"{BaseUrl}/sessions"))
{
    if (response.StatusCode == HttpStatusCode.Unauthorized)
    {
        Console.WriteLine("Session expired — sign in again.");
        return 1;
    }

    response.EnsureSuccessStatusCode();
    sessions = await response.Content.ReadFromJsonAsync<List<SessionListItem>>();
}

if (sessions is null || sessions.Count == 0)
{
    Console.WriteLine("No sessions yet. Recording arrives with slice 04 Task 7.");
    return 0;
}

Console.WriteLine();
for (var i = 0; i < sessions.Count; i++)
{
    var s = sessions[i];
    var summarized = s.HasSummary ? "summarized" : "no summary";
    Console.WriteLine($"[{i + 1}] {s.StartedAt:yyyy-MM-dd HH:mm}  {s.Source}  {s.SegmentCount} segments  ({summarized})");
}

Console.WriteLine();
Console.Write("Summarize which? (number, or ENTER to quit) ");
if (!int.TryParse(Console.ReadLine(), out var choice) || choice < 1 || choice > sessions.Count)
{
    return 0;
}

var sessionId = sessions[choice - 1].Id;

using (var response = await http.PostAsync($"{BaseUrl}/sessions/{sessionId}/summary", null))
{
    if (response.StatusCode == HttpStatusCode.Unauthorized)
    {
        Console.WriteLine("Session expired — sign in again.");
        return 1;
    }

    if (response.StatusCode == HttpStatusCode.BadGateway)
    {
        // The summarize agent talks to Ollama; a 502 means the model host is the problem,
        // not the transcript.
        Console.WriteLine("Summarize failed: the model host is unreachable. Is Ollama running?");
        return 1;
    }

    response.EnsureSuccessStatusCode();
    var summary = await response.Content.ReadFromJsonAsync<SessionSummary>();
    Console.WriteLine();
    Console.WriteLine(summary?.Summary);
}

return 0;

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
