using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Threading.Channels;
using Microsoft.AspNetCore.SignalR.Client;
using NAudio.Wave;
using Harken.Core;
using Harken.Core.Contracts;

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
var token = auth.Token;

http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);

Console.WriteLine($"Signed in as {email}.");

Guid? sessionId = null;

var connection = new HubConnectionBuilder()
    .WithUrl($"{BaseUrl}/hub/captions", options =>
    {
        // AccessTokenProvider, not a raw header: WebSocket transports cannot set an
        // Authorization header, so SignalR appends the token as the `access_token`
        // query param — which is the only form the server accepts on /hub paths.
        options.AccessTokenProvider = () => Task.FromResult<string?>(token);
    })
    .Build();

connection.On<Guid>("SessionStarted", id =>
{
    sessionId = id;
});

connection.On<CaptionUpdate>("ReceiveCaption", update =>
{
    if (!update.IsFinal)
    {
        Console.Write($"\r{update.Text}".PadRight(Console.WindowWidth > 0 ? Console.WindowWidth - 1 : 80));
    }
    else
    {
        Console.WriteLine($"\r{update.Text}".PadRight(Console.WindowWidth > 0 ? Console.WindowWidth - 1 : 80));
    }
});

await connection.StartAsync();
Console.WriteLine("Connected to captions hub.");

var channel = Channel.CreateUnbounded<byte[]>();

using var waveIn = new WaveInEvent
{
    WaveFormat = new WaveFormat(16000, 16, 1),
};

waveIn.DataAvailable += (_, e) =>
{
    var buffer = new byte[e.BytesRecorded];
    Array.Copy(e.Buffer, buffer, e.BytesRecorded);
    channel.Writer.TryWrite(buffer);
};

using var cts = new CancellationTokenSource();

Console.WriteLine("Recording... press ENTER to stop.");
waveIn.StartRecording();

var streamTask = connection.InvokeAsync("StreamAudio", channel.Reader.ReadAllAsync(cts.Token), AudioSource.Microphone, cts.Token);

Console.ReadLine();

waveIn.StopRecording();
channel.Writer.Complete();

await streamTask;

Console.WriteLine("Streaming complete.");

if (sessionId is { } id)
{
    Console.Write("Summarize? (y/n) ");
    var answer = Console.ReadLine();
    if (string.Equals(answer, "y", StringComparison.OrdinalIgnoreCase))
    {
        using var response = await http.PostAsync($"{BaseUrl}/sessions/{id}/summary", null);
        if (response.StatusCode == HttpStatusCode.Unauthorized)
        {
            Console.WriteLine("Session expired — sign in again.");
            await connection.StopAsync();
            return 1;
        }

        response.EnsureSuccessStatusCode();
        var summary = await response.Content.ReadFromJsonAsync<SessionSummary>();
        Console.WriteLine(summary?.Summary);
    }
}

await connection.StopAsync();
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
