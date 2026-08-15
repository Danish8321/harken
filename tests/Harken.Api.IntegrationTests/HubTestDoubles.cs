using System.Security.Claims;
using Microsoft.AspNetCore.Http.Features;
using Microsoft.AspNetCore.SignalR;
using Harken.Api.Speech;
using Harken.Core.Contracts;

namespace Harken.Api.IntegrationTests;

/// <summary>
/// Test doubles for driving <c>CaptionHub</c> directly, without a SignalR connection.
/// Shared so the hub's fakes exist once: a second copy would drift from this one.
/// </summary>
internal sealed class SilentTranscriber : ISpeechTranscriber
{
    // Never raised: this fake produces no recognitions.
    public event Action<string>? PartialRecognized { add { } remove { } }

    public event Action<string>? FinalRecognized { add { } remove { } }

    public Task StartAsync(CancellationToken ct) => Task.CompletedTask;

    public void PushAudio(ReadOnlyMemory<byte> chunk) { }

    public Task StopAsync() => Task.CompletedTask;

    public ValueTask DisposeAsync() => ValueTask.CompletedTask;
}

internal sealed class FakeCaptionClient : ICaptionClient
{
    public Task ReceiveCaption(CaptionUpdate update) => Task.CompletedTask;

    public Task SessionStarted(Guid sessionId) => Task.CompletedTask;
}

internal sealed class FakeHubCallerClients : IHubCallerClients<ICaptionClient>
{
    public FakeHubCallerClients()
        : this(new FakeCaptionClient())
    {
    }

    public FakeHubCallerClients(ICaptionClient caller) => Caller = caller;

    public ICaptionClient Caller { get; }

    public ICaptionClient Others => Caller;

    public ICaptionClient All => Caller;

    public ICaptionClient AllExcept(IReadOnlyList<string> excludedConnectionIds) => Caller;

    public ICaptionClient Client(string connectionId) => Caller;

    public ICaptionClient Clients(IReadOnlyList<string> connectionIds) => Caller;

    public ICaptionClient Group(string groupName) => Caller;

    public ICaptionClient Groups(IReadOnlyList<string> groupNames) => Caller;

    public ICaptionClient GroupExcept(string groupName, IReadOnlyList<string> excludedConnectionIds) => Caller;

    public ICaptionClient OthersInGroup(string groupName) => Caller;

    public ICaptionClient User(string userId) => Caller;

    public ICaptionClient Users(IReadOnlyList<string> userIds) => Caller;
}

internal sealed class FakeHubCallerContext : HubCallerContext
{
    public FakeHubCallerContext(string userId)
    {
        User = new ClaimsPrincipal(new ClaimsIdentity(
            [new Claim(ClaimTypes.NameIdentifier, userId)], "Test"));
        UserIdentifier = userId;
    }

    public override string ConnectionId { get; } = Guid.NewGuid().ToString("N");

    public override string? UserIdentifier { get; }

    public override ClaimsPrincipal? User { get; }

    public override IDictionary<object, object?> Items { get; } = new Dictionary<object, object?>();

    public override IFeatureCollection Features { get; } = new FeatureCollection();

    public override CancellationToken ConnectionAborted => CancellationToken.None;

    public override void Abort() { }
}
