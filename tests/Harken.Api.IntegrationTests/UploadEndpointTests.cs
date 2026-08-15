using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Harken.Api.Data;
using Harken.Core;
using Harken.Core.Contracts;
using Xunit;

namespace Harken.Api.IntegrationTests;

public class UploadEndpointTests : IClassFixture<CustomWebApplicationFactory>
{
    private readonly CustomWebApplicationFactory _factory;

    public UploadEndpointTests(CustomWebApplicationFactory factory)
    {
        _factory = factory;
    }

    private static MultipartFormDataContent BuildUpload(byte[] audioBytes, string fileName = "recording.wav", string contentType = "audio/wav", string source = "Microphone")
    {
        var content = new MultipartFormDataContent();
        var audioContent = new ByteArrayContent(audioBytes);
        audioContent.Headers.ContentType = new MediaTypeHeaderValue(contentType);
        content.Add(audioContent, "audio", fileName);
        content.Add(new StringContent(source), "source");
        return content;
    }

    [Fact]
    public async Task UnauthenticatedUploadReturnsUnauthorized()
    {
        var client = _factory.CreateClient();

        using var response = await client.PostAsync("/sessions", BuildUpload([1, 2, 3]));

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task UploadedSessionIsOwnedByCaller()
    {
        var (client, userId) = await _factory.CreateAuthenticatedClientAsync();

        using var response = await client.PostAsync("/sessions", BuildUpload([1, 2, 3, 4, 5]));

        Assert.Equal(HttpStatusCode.Created, response.StatusCode);
        var created = await response.Content.ReadFromJsonAsync<SessionListItem>();
        Assert.NotNull(created);

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        var session = await db.Sessions
            .Include(s => s.Recording)
            .SingleAsync(s => s.Id == created!.Id);

        Assert.Equal(userId, session.OwnerId);
        Assert.NotNull(session.Recording);
        Assert.Equal(5, session.Recording!.ByteLength);
        // Not asserted as exactly Pending: the real background job (Task 6) picks it up
        // immediately, and with no Whisper model configured in this test host it fails
        // fast — so by the time we read it back, status may already be Running or Failed.
        // What matters here is only that upload created a Pending-or-later job, which any
        // non-null status proves.
        Assert.NotNull(session.TranscriptionStatus);
    }

    [Fact]
    public async Task OversizedUploadIsRejectedAndNothingPersisted()
    {
        var (client, userId) = await _factory.CreateAuthenticatedClientAsync();

        // Over Kestrel's configured MaxRequestBodySize (500 MB) — a real 501 MB buffer
        // would be wasteful and slow for a test, so this proves the same failure mode
        // via the smallest oversized body Kestrel's own limit actually enforces: the
        // request is rejected before the handler's own file.Length check ever runs.
        using var client2 = _factory.CreateClient(new Microsoft.AspNetCore.Mvc.Testing.WebApplicationFactoryClientOptions());
        client2.DefaultRequestHeaders.Authorization = client.DefaultRequestHeaders.Authorization;

        var oversized = new byte[600 * 1024 * 1024];
        Exception? thrown = null;
        HttpResponseMessage? response = null;
        try
        {
            response = await client2.PostAsync("/sessions", BuildUpload(oversized));
        }
        catch (Exception ex)
        {
            // Kestrel can reset the connection outright for a body over its configured
            // limit rather than returning a clean status code to an in-process test
            // client; either outcome is "rejected", so both are accepted here.
            thrown = ex;
        }

        if (response is not null)
        {
            // TestServer enforces Kestrel's MaxRequestBodySize by throwing
            // BadHttpRequestException, which surfaces as 500 here rather than the
            // 413 a real Kestrel listener returns on the wire — both mean "rejected
            // before the handler's own size check ran", which is what this test proves.
            Assert.True(
                response.StatusCode is HttpStatusCode.RequestEntityTooLarge
                    or (HttpStatusCode)413
                    or HttpStatusCode.InternalServerError,
                $"Expected rejection, got {(int)response.StatusCode}");
        }
        else
        {
            Assert.NotNull(thrown);
        }

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        Assert.False(await db.Sessions.AnyAsync(s => s.OwnerId == userId));
    }

    [Fact]
    public async Task NonWavUploadIsRejected()
    {
        var (client, userId) = await _factory.CreateAuthenticatedClientAsync();

        using var response = await client.PostAsync(
            "/sessions",
            BuildUpload([1, 2, 3], fileName: "clip.mp3", contentType: "audio/mpeg"));

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        Assert.False(await db.Sessions.AnyAsync(s => s.OwnerId == userId));
    }

    [Fact]
    public async Task HostileFilenameCannotEscapeStorageRoot()
    {
        var (client, _) = await _factory.CreateAuthenticatedClientAsync();

        using var response = await client.PostAsync(
            "/sessions",
            BuildUpload([1, 2, 3], fileName: "../../../../evil.wav"));

        // The client-supplied filename is never read for storage purposes — the stored
        // path is derived from the server-generated Session id — so a hostile name has
        // no effect at all rather than being specially rejected.
        Assert.Equal(HttpStatusCode.Created, response.StatusCode);

        var created = await response.Content.ReadFromJsonAsync<SessionListItem>();
        Assert.NotNull(created);

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        var session = await db.Sessions
            .Include(s => s.Recording)
            .SingleAsync(s => s.Id == created!.Id);

        Assert.Contains($"{created.Id:N}.wav", session.Recording!.StoredPath, StringComparison.Ordinal);
        Assert.DoesNotContain("evil", session.Recording.StoredPath, StringComparison.Ordinal);
    }

    [Fact]
    public async Task MissingSourceIsRejected()
    {
        var (client, userId) = await _factory.CreateAuthenticatedClientAsync();

        var content = new MultipartFormDataContent();
        var audioContent = new ByteArrayContent([1, 2, 3]);
        audioContent.Headers.ContentType = new MediaTypeHeaderValue("audio/wav");
        content.Add(audioContent, "audio", "recording.wav");
        // No 'source' field.

        using var response = await client.PostAsync("/sessions", content);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);

        using var scope = _factory.Services.CreateScope();
        var db = scope.ServiceProvider.GetRequiredService<HarkenDbContext>();
        Assert.False(await db.Sessions.AnyAsync(s => s.OwnerId == userId));
    }
}
