using System.Buffers.Binary;
using System.Text;
using Harken.Core.Audio;
using Xunit;

namespace Harken.Core.UnitTests;

/// <summary>
/// The header is the whole risk in <see cref="WavWriter"/>: PCM passes through untouched,
/// but a wrong length field or sample rate yields a file that plays as silence, at the
/// wrong speed, or not at all — and the phone is the only place that would surface it
/// (slice-06 Task 1). These assert the bytes directly rather than round-tripping through
/// a decoder, so a failure names the field that is wrong.
/// </summary>
public class WavWriterTests
{
    private static byte[] WriteWav(params byte[][] chunks)
    {
        var stream = new MemoryStream();
        using (var writer = new WavWriter(stream, leaveOpen: true))
        {
            foreach (var chunk in chunks)
            {
                writer.Write(chunk);
            }
        }
        return stream.ToArray();
    }

    private static string Ascii(byte[] wav, int offset, int length)
        => Encoding.ASCII.GetString(wav, offset, length);

    private static int Int32At(byte[] wav, int offset)
        => BinaryPrimitives.ReadInt32LittleEndian(wav.AsSpan(offset, 4));

    private static short Int16At(byte[] wav, int offset)
        => BinaryPrimitives.ReadInt16LittleEndian(wav.AsSpan(offset, 2));

    [Fact]
    public void Header_HasRiffWaveFmtAndDataMarkers()
    {
        var wav = WriteWav([1, 2, 3, 4]);

        Assert.Equal("RIFF", Ascii(wav, 0, 4));
        Assert.Equal("WAVE", Ascii(wav, 8, 4));
        Assert.Equal("fmt ", Ascii(wav, 12, 4));
        Assert.Equal("data", Ascii(wav, 36, 4));
    }

    [Fact]
    public void Header_DeclaresSixteenKilohertzSixteenBitMono()
    {
        var wav = WriteWav([1, 2]);

        Assert.Equal(16, Int32At(wav, 16));   // PCM fmt chunk size
        Assert.Equal(1, Int16At(wav, 20));    // format 1 = uncompressed PCM
        Assert.Equal(1, Int16At(wav, 22));    // mono
        Assert.Equal(16_000, Int32At(wav, 24));
        Assert.Equal(32_000, Int32At(wav, 28)); // byte rate = 16000 * 1 * 2
        Assert.Equal(2, Int16At(wav, 32));     // block align = channels * bytes/sample
        Assert.Equal(16, Int16At(wav, 34));    // bits per sample
    }

    [Fact]
    public void Dispose_PatchesBothLengthFieldsToMatchThePayload()
    {
        var wav = WriteWav(new byte[10], new byte[6]);

        Assert.Equal(WavWriter.HeaderLength + 16, wav.Length);
        Assert.Equal(36 + 16, Int32At(wav, 4));  // RIFF size counts everything after byte 8
        Assert.Equal(16, Int32At(wav, 40));      // data size counts payload only
    }

    [Fact]
    public void ChunkBoundariesDoNotAffectTheBytesWritten()
    {
        // The capture loop hands over whatever AudioRecord returned, so the same audio
        // arrives in different chunk sizes run to run; the file must not vary with it.
        var oneChunk = WriteWav([1, 2, 3, 4, 5, 6]);
        var manyChunks = WriteWav([1, 2], [3], [4, 5, 6]);

        Assert.Equal(oneChunk, manyChunks);
    }

    [Fact]
    public void PayloadFollowsTheHeaderUnmodified()
    {
        byte[] pcm = [0xDE, 0xAD, 0xBE, 0xEF];

        var wav = WriteWav(pcm);

        Assert.Equal(pcm, wav.AsSpan(WavWriter.HeaderLength).ToArray());
    }

    [Fact]
    public void EmptyRecordingIsAValidHeaderWithNoPayload()
    {
        // A user who taps record then stop immediately must not produce a corrupt file.
        var wav = WriteWav();

        Assert.Equal(WavWriter.HeaderLength, wav.Length);
        Assert.Equal(36, Int32At(wav, 4));
        Assert.Equal(0, Int32At(wav, 40));
    }

    [Fact]
    public void DataLength_TracksWhatHasBeenWritten()
    {
        var stream = new MemoryStream();
        using var writer = new WavWriter(stream, leaveOpen: true);

        Assert.Equal(0, writer.DataLength);

        writer.Write(new byte[8]);
        Assert.Equal(8, writer.DataLength);

        writer.Write(new byte[4]);
        Assert.Equal(12, writer.DataLength);
    }

    [Fact]
    public void Write_AfterDispose_Throws()
    {
        var stream = new MemoryStream();
        var writer = new WavWriter(stream, leaveOpen: true);
        writer.Dispose();

        Assert.Throws<ObjectDisposedException>(() => writer.Write(new byte[4]));
    }

    [Fact]
    public void Dispose_IsIdempotent()
    {
        // The foreground service can stop by user action, silence timeout, or session cap
        // (slice-06 Task 5); more than one of those can land on the same recording.
        var stream = new MemoryStream();
        var writer = new WavWriter(stream, leaveOpen: true);
        writer.Write(new byte[4]);

        writer.Dispose();
        var afterFirst = stream.ToArray();
        writer.Dispose();

        Assert.Equal(afterFirst, stream.ToArray());
    }

    [Fact]
    public void NonSeekableStream_IsRejectedUpFront()
    {
        // Rejected at construction, not at Dispose: the lengths could never be patched,
        // so every file would be unplayable and the failure would surface far from here.
        using var nonSeekable = new NonSeekableStream();

        Assert.Throws<ArgumentException>(() => new WavWriter(nonSeekable));
    }

    [Fact]
    public void LeaveOpenFalse_DisposesTheUnderlyingStream()
    {
        var stream = new MemoryStream();

        using (var writer = new WavWriter(stream))
        {
            writer.Write(new byte[4]);
        }

        Assert.False(stream.CanWrite);
    }

    private sealed class NonSeekableStream : MemoryStream
    {
        public override bool CanSeek => false;
    }
}
