using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.AI;
using OllamaSharp;
using Harken.Api.Agents;
using Harken.Api.Data;
using Harken.Api.Hubs;
using Harken.Api.Speech;

var builder = WebApplication.CreateBuilder(args);

var connectionString = builder.Configuration.GetConnectionString("Harken")
    ?? "Data Source=harken.db";

builder.Services.AddDbContext<HarkenDbContext>(o => o.UseSqlite(connectionString));

var speechSection = builder.Configuration.GetSection("AzureSpeech");
var speechOptions = new AzureSpeechOptions(
    speechSection["Key"] ?? "",
    speechSection["Region"] ?? "");
builder.Services.AddSingleton(speechOptions);

// One recognizer lifetime per Session — transient.
builder.Services.AddTransient<ISpeechTranscriber, AzureSpeechTranscriber>();

builder.Services.AddSignalR();

// Provider seam: only this registration changes when swapping to Azure Foundry.
var ollamaEndpoint = builder.Configuration["Ollama:Endpoint"]
    ?? Environment.GetEnvironmentVariable("OLLAMA_ENDPOINT")
    ?? "http://localhost:11434";
var ollamaModel = builder.Configuration["Ollama:Model"]
    ?? Environment.GetEnvironmentVariable("OLLAMA_MODEL_NAME")
    ?? "gemma3:4b";
builder.Services.AddKeyedSingleton<IChatClient>("chat-model", (_, _) =>
    new OllamaApiClient(ollamaEndpoint, ollamaModel));

builder.Services.AddScoped<SummarizeAgent>();

var app = builder.Build();

app.MapGet("/", () => "Hello World!");

app.MapHub<CaptionHub>("/hub/captions");

app.MapPost("/sessions/{id:guid}/summary", async (Guid id, SummarizeAgent agent, CancellationToken ct) =>
{
    try
    {
        var summary = await agent.SummarizeAsync(id, ct);
        return Results.Ok(summary);
    }
    catch (Exception ex)
    {
        return Results.Problem(detail: ex.Message, statusCode: StatusCodes.Status502BadGateway);
    }
});

app.Run();

public partial class Program { }
