namespace Harken.Api.Storage;

/// <summary>
/// Where Recordings live on disk. Filenames are always server-generated — a client
/// never gets to choose the name a file is written under, which is what keeps a crafted
/// name (e.g. "../../etc/passwd") from writing outside the storage root.
/// </summary>
public sealed class RecordingStorage
{
    private readonly string _rootPath;

    public RecordingStorage(string rootPath)
    {
        _rootPath = Path.GetFullPath(rootPath);
        Directory.CreateDirectory(_rootPath);
    }

    /// <summary>
    /// Streams <paramref name="content"/> to a new file under the storage root and
    /// returns the path to it. The name is generated here, from
    /// <paramref name="sessionId"/> alone — nothing from the request reaches the
    /// filesystem.
    /// </summary>
    public async Task<string> SaveAsync(Guid sessionId, Stream content, CancellationToken ct)
    {
        var path = Path.Combine(_rootPath, $"{sessionId:N}.wav");

        await using var file = File.Create(path);
        await content.CopyToAsync(file, ct);

        return path;
    }

    /// <summary>Permanently removes a Session's audio file from disk. Only the purge
    /// path calls this — a soft delete never touches the filesystem.</summary>
    public void Delete(string storedPath)
    {
        if (File.Exists(storedPath))
        {
            File.Delete(storedPath);
        }
    }
}
