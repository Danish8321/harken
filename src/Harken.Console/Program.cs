using System.Net.Http.Json;
using System.Threading.Channels;
using Microsoft.AspNetCore.SignalR.Client;
using NAudio.Wave;
using Harken.Core.Contracts;

const string BaseUrl = "http://localhost:5057";

Guid? sessionId = null;

var connection = new HubConnectionBuilder()
    .WithUrl($"{BaseUrl}/hub/captions")
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

var streamTask = connection.InvokeAsync("StreamAudio", channel.Reader.ReadAllAsync(cts.Token), cts.Token);

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
        using var http = new HttpClient();
        var response = await http.PostAsync($"{BaseUrl}/sessions/{id}/summary", null);
        response.EnsureSuccessStatusCode();
        var summary = await response.Content.ReadFromJsonAsync<SessionSummary>();
        Console.WriteLine(summary?.Summary);
    }
}

await connection.StopAsync();
