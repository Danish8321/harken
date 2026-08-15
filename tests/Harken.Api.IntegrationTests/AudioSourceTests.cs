using Microsoft.AspNetCore.SignalR;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Harken.Api.Data;
using Harken.Api.Hubs;
using Harken.Api.Speech;
using Harken.Core;
using Harken.Core.Contracts;
using Xunit;

namespace Harken.Api.IntegrationTests;

/// <summary>The Source is declared by the client, not assumed by the server.</summary>
public class AudioSourceTests : IClassFixture<CustomWebApplicationFactory>
{
    private readonly CustomWebApplicationFactory _factory;

    public AudioSourceTests(CustomWebApplicationFactory factory)
    {
        _factory = factory;
    }

    [Fact]
    public async Task SystemAudioSessionPersistsAsSystemAudio()
    {
        var (_, userId) = await _factory.CreateAuthenticatedClientAsync();
        var hub = CreateHub(userId);

        await hub.StreamAudio(EmptyChunks(), AudioSource.SystemAudio, CancellationToken.None);

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        var session = await db.Sessions.AsNoTracking().SingleAsync(s => s.OwnerId == userId);

        Assert.Equal(AudioSource.SystemAudio, session.Source);
    }

    [Fact]
    public async Task UndefinedAudioSourceIsRejectedAndNotPersisted()
    {
        var (_, userId) = await _factory.CreateAuthenticatedClientAsync();
        var hub = CreateHub(userId);

        // Enums cross the wire as ints; 99 is not a defined AudioSource.
        await Assert.ThrowsAsync<HubException>(
            () => hub.StreamAudio(EmptyChunks(), (AudioSource)99, CancellationToken.None));

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();

        Assert.False(await db.Sessions.AsNoTracking().AnyAsync(s => s.OwnerId == userId));
    }

    private CaptionHub CreateHub(string userId)
    {
        var scopeFactory = _factory.Services.GetRequiredService<IServiceScopeFactory>();

        return new CaptionHub(scopeFactory, new SilentTranscriber())
        {
            Context = new FakeHubCallerContext(userId),
            Clients = new FakeHubCallerClients(),
        };
    }

    private static async IAsyncEnumerable<byte[]> EmptyChunks()
    {
        await Task.CompletedTask;
        yield break;
    }
}
