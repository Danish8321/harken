namespace Harken.Core.Audio;

/// <summary>
/// Writes 16 kHz / 16-bit / mono PCM to a stream as a RIFF WAVE file — the one format
/// the backend accepts and Whisper wants natively (ADR-0007, ADR-0008).
///
/// The two RIFF length fields cannot be known until the last chunk is written, so a
/// placeholder header goes down first and is patched on <see cref="Dispose"/>. That means
/// a recording whose process dies mid-capture leaves a file with zeroed lengths: the PCM
/// is intact but players see an empty file. <see cref="RepairHeader"/> rewrites the header
/// from the file size, no re-recording needed.
/// </summary>
public sealed class WavWriter : IDisposable
{
    public const int SampleRate = 16_000;
    public const short Channels = 1;
    public const short BitsPerSample = 16;

    /// <summary>Canonical RIFF/WAVE header for PCM: 12-byte RIFF + 24-byte fmt + 8-byte data.</summary>
    public const int HeaderLength = 44;

    private readonly Stream _stream;
    private readonly bool _leaveOpen;
    private readonly BinaryWriter _writer;

    private int _dataLength;
    private bool _disposed;

    public WavWriter(Stream stream, bool leaveOpen = false)
    {
        ArgumentNullException.ThrowIfNull(stream);
        if (!stream.CanWrite)
        {
            throw new ArgumentException("Stream must be writable.", nameof(stream));
        }
        if (!stream.CanSeek)
        {
            // Without seek there is no way to patch the length fields, which would leave
            // every file unplayable — fail here rather than at Dispose.
            throw new ArgumentException("Stream must be seekable so the header can be patched on close.", nameof(stream));
        }

        _stream = stream;
        _leaveOpen = leaveOpen;
        _writer = new BinaryWriter(stream, System.Text.Encoding.ASCII, leaveOpen: true);

        WritePlaceholderHeader();
    }

    /// <summary>Bytes of PCM written so far, excluding the header.</summary>
    public int DataLength => _dataLength;

    /// <summary>Appends raw PCM. Chunk boundaries are irrelevant to the format.</summary>
    public void Write(byte[] pcm)
    {
        ArgumentNullException.ThrowIfNull(pcm);
        Write(pcm.AsSpan());
    }

    /// <summary>Appends raw PCM. Chunk boundaries are irrelevant to the format.</summary>
    public void Write(ReadOnlySpan<byte> pcm)
    {
        ObjectDisposedException.ThrowIf(_disposed, this);

        _writer.Write(pcm);
        _dataLength += pcm.Length;
    }

    public void Dispose()
    {
        if (_disposed)
        {
            return;
        }
        _disposed = true;

        PatchLengths();
        _writer.Dispose();

        if (!_leaveOpen)
        {
            _stream.Dispose();
        }
    }

    private void WritePlaceholderHeader()
    {
        const int byteRate = SampleRate * Channels * (BitsPerSample / 8);
        const short blockAlign = (short)(Channels * (BitsPerSample / 8));

        _writer.Write("RIFF"u8);
        _writer.Write(0); // patched on close: 36 + data length
        _writer.Write("WAVE"u8);

        _writer.Write("fmt "u8);
        _writer.Write(16);            // PCM fmt chunk size
        _writer.Write((short)1);      // format 1 = uncompressed PCM
        _writer.Write(Channels);
        _writer.Write(SampleRate);
        _writer.Write(byteRate);
        _writer.Write(blockAlign);
        _writer.Write(BitsPerSample);

        _writer.Write("data"u8);
        _writer.Write(0); // patched on close: data length
    }

    private void PatchLengths()
    {
        _writer.Flush();
        var resumeAt = _stream.Position;

        // RIFF size counts everything after the first 8 bytes.
        _stream.Position = 4;
        _writer.Write(36 + _dataLength);

        _stream.Position = 40;
        _writer.Write(_dataLength);

        _writer.Flush();
        _stream.Position = resumeAt;
    }

    /// <summary>
    /// Patches a WAV file's RIFF/data length fields from its actual size on disk. For a file
    /// left behind by a process that died mid-capture (Dispose never ran, so the lengths are
    /// still the zeroed placeholder) the PCM itself is intact — only the header lied about how
    /// much of it there is. Recomputing the lengths from the file size makes it playable and
    /// uploadable again, no re-recording needed.
    ///
    /// A no-op on a file whose header already agrees with its size (already-clean recordings,
    /// or a repair running twice), and on anything shorter than <see cref="HeaderLength"/>,
    /// which is not a WAV file this can do anything with. Returns whether a patch was written.
    /// </summary>
    public static bool RepairHeader(string path)
    {
        using var stream = File.Open(path, FileMode.Open, FileAccess.ReadWrite, FileShare.None);

        if (stream.Length < HeaderLength)
        {
            return false;
        }

        var dataLength = checked((int)(stream.Length - HeaderLength));

        Span<byte> field = stackalloc byte[4];
        stream.Position = 40;
        stream.ReadExactly(field);
        if (System.Buffers.Binary.BinaryPrimitives.ReadInt32LittleEndian(field) == dataLength)
        {
            // Header already matches the file — a clean recording, or a repair re-run.
            return false;
        }

        System.Buffers.Binary.BinaryPrimitives.WriteInt32LittleEndian(field, 36 + dataLength);
        stream.Position = 4;
        stream.Write(field);

        System.Buffers.Binary.BinaryPrimitives.WriteInt32LittleEndian(field, dataLength);
        stream.Position = 40;
        stream.Write(field);

        stream.Flush();
        return true;
    }
}
