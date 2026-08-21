using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Harken.Api.Data;
using Harken.Core.Contracts;
using Xunit;

namespace Harken.Api.IntegrationTests;

public class DeleteEndpointTests : IClassFixture<CustomWebApplicationFactory>
{
    private readonly CustomWebApplicationFactory _factory;

    public DeleteEndpointTests(CustomWebApplicationFactory factory)
    {
        _factory = factory;
    }

    private static MultipartFormDataContent BuildUpload(byte[] audioBytes)
    {
        var content = new MultipartFormDataContent();
        var audioContent = new ByteArrayContent(audioBytes);
        audioContent.Headers.ContentType = new MediaTypeHeaderValue("audio/wav");
        content.Add(audioContent, "audio", "recording.wav");
        content.Add(new StringContent("Microphone"), "source");
        return content;
    }

    [Fact]
    public async Task SoftDeleteHidesSessionFromListButKeepsRowAndFile()
    {
        var client = _factory.CreateClient();

        using var upload = await client.PostAsync("/sessions", BuildUpload([1, 2, 3]));
        var created = await upload.Content.ReadFromJsonAsync<SessionListItem>();
        Assert.NotNull(created);

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        var storedPath = (await db.Sessions
            .Include(s => s.Recording)
            .SingleAsync(s => s.Id == created!.Id)).Recording!.StoredPath;

        using var delete = await client.DeleteAsync($"/sessions/{created!.Id}");
        Assert.Equal(HttpStatusCode.NoContent, delete.StatusCode);

        var listed = await client.GetFromJsonAsync<List<SessionListItem>>("/sessions");
        Assert.DoesNotContain(listed!, s => s.Id == created.Id);

        var session = await db.Sessions
            .AsNoTracking()
            .Include(s => s.Recording)
            .SingleOrDefaultAsync(s => s.Id == created.Id);
        Assert.NotNull(session);
        Assert.True(session!.Deleted);
        Assert.NotNull(session.Recording);
        Assert.True(File.Exists(storedPath), "Soft delete must not remove the audio file.");
    }

    [Fact]
    public async Task SoftDeletingUnknownSessionReturnsNotFound()
    {
        var client = _factory.CreateClient();

        using var response = await client.DeleteAsync($"/sessions/{Guid.NewGuid()}");

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task PurgeRemovesRowAndAudioFilePermanently()
    {
        var client = _factory.CreateClient();

        using var upload = await client.PostAsync("/sessions", BuildUpload([1, 2, 3]));
        var created = await upload.Content.ReadFromJsonAsync<SessionListItem>();
        Assert.NotNull(created);

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        var storedPath = (await db.Sessions
            .Include(s => s.Recording)
            .SingleAsync(s => s.Id == created!.Id)).Recording!.StoredPath;
        Assert.True(File.Exists(storedPath));

        using var purge = await client.DeleteAsync($"/sessions/{created!.Id}/purge");
        Assert.Equal(HttpStatusCode.NoContent, purge.StatusCode);

        Assert.False(await db.Sessions.AsNoTracking().AnyAsync(s => s.Id == created.Id));
        Assert.False(File.Exists(storedPath), "Purge must remove the audio file from disk.");
    }

    [Fact]
    public async Task PurgingUnknownSessionReturnsNotFound()
    {
        var client = _factory.CreateClient();

        using var response = await client.DeleteAsync($"/sessions/{Guid.NewGuid()}/purge");

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
    }

    [Fact]
    public async Task PurgingASessionWithNoRecordingStillRemovesTheRow()
    {
        var sessionId = _factory.SeedSession();
        var client = _factory.CreateClient();

        using var response = await client.DeleteAsync($"/sessions/{sessionId}/purge");

        Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        Assert.False(await db.Sessions.AsNoTracking().AnyAsync(s => s.Id == sessionId));
    }
}
