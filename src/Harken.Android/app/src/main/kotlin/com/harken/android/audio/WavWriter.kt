package com.harken.android.audio

import java.io.RandomAccessFile

// Ports src/Harken.Core/Audio/WavWriter.cs — same format constants, same
// placeholder-header-then-patch-on-close pattern, same orphaned-header repair
// (commit 503fed7) so a file written by this app is byte-identical in shape to one
// written by the MAUI client.
object WavFormat {
    const val SampleRate = 16000
    const val Channels = 1
    const val BitsPerSample = 16
    const val HeaderLength = 44
    const val BytesPerSecond = SampleRate * Channels * (BitsPerSample / 8)

    /**
     * How many seconds of audio a finished recording holds, from its own byte length.
     *
     * The file is the only authority on this. A duration timed with a clock is a second,
     * weaker source — the wall clock is settable, and a segment offset only marks where
     * speech was found — so the recorder, the recovery pass and the transcriber all read
     * the length here rather than each deriving it (ARC-009).
     */
    fun durationSeconds(file: java.io.File): Int =
        ((file.length() - HeaderLength).coerceAtLeast(0) / BytesPerSecond).toInt()
}

class WavWriter(private val file: RandomAccessFile) : AutoCloseable {
    private var dataLength: Long = 0

    init {
        require(file.length() == 0L) { "WavWriter requires a fresh file" }
        writePlaceholderHeader()
    }

    private fun writePlaceholderHeader() {
        val byteRate = WavFormat.SampleRate * WavFormat.Channels * (WavFormat.BitsPerSample / 8)
        val blockAlign = WavFormat.Channels * (WavFormat.BitsPerSample / 8)

        file.seek(0)
        file.writeBytes("RIFF")
        writeIntLE(0) // RIFF chunk size, patched on close
        file.writeBytes("WAVE")
        file.writeBytes("fmt ")
        writeIntLE(16) // fmt chunk size (PCM)
        writeShortLE(1) // audio format: PCM
        writeShortLE(WavFormat.Channels)
        writeIntLE(WavFormat.SampleRate)
        writeIntLE(byteRate)
        writeShortLE(blockAlign)
        writeShortLE(WavFormat.BitsPerSample)
        file.writeBytes("data")
        writeIntLE(0) // data chunk size, patched on close
    }

    /**
     * Appends to the data chunk. No seek: the pointer is left at the end of the data by
     * the placeholder header and by every write, and the only thing that ever moves it is
     * [patchLengths], which runs once, on close. Seeking to where the pointer already was,
     * 6.25 times a second for three hours, was a syscall per chunk for nothing (ARC-030).
     */
    fun write(pcm: ByteArray, offset: Int, length: Int) {
        file.write(pcm, offset, length)
        dataLength += length
    }

    override fun close() {
        patchLengths()
        file.close()
    }

    private fun patchLengths() {
        // Both header fields are unsigned 32-bit, so the format itself cannot describe a
        // longer file. The app's three-hour cap is 345 MB, a hundredth of this, so it
        // cannot fire today — it is here because this is the value that decides whether
        // the recording is readable at all, and a silent narrowing would write a negative
        // length into a file the user believes they still have.
        require(dataLength + RiffHeaderOverhead <= MaxRiffLength) {
            "WAV data length $dataLength exceeds the format's 4 GB limit"
        }
        file.seek(4)
        writeIntLE((36 + dataLength).toInt())
        file.seek(40)
        writeIntLE(dataLength.toInt())
    }

    private fun writeIntLE(value: Int) {
        file.write(byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte(),
        ))
    }

    private fun writeShortLE(value: Int) {
        file.write(byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
        ))
    }

    companion object {
        /** Bytes of RIFF header counted by the size field at offset 4, beside the data. */
        private const val RiffHeaderOverhead = 36

        /** The largest value an unsigned 32-bit RIFF size field can hold. */
        private const val MaxRiffLength = 0xFFFF_FFFFL

        // Repairs a WAV file left with a zero/placeholder data-length header because the
        // process died mid-capture (e.g. killed foreground service) before close() patched
        // it. Returns true if a repair was made, false if the header already matched.
        fun repairHeader(path: String): Boolean {
            RandomAccessFile(path, "rw").use { file ->
                if (file.length() < WavFormat.HeaderLength) return false

                val dataLength = file.length() - WavFormat.HeaderLength

                file.seek(40)
                val existing = readIntLE(file)

                if (existing.toLong() == dataLength) return false

                file.seek(4)
                writeIntLEStatic(file, (36 + dataLength).toInt())
                file.seek(40)
                writeIntLEStatic(file, dataLength.toInt())
                return true
            }
        }

        private fun readIntLE(file: RandomAccessFile): Int {
            val b = ByteArray(4)
            file.readFully(b)
            return (b[0].toInt() and 0xFF) or
                ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or
                ((b[3].toInt() and 0xFF) shl 24)
        }

        private fun writeIntLEStatic(file: RandomAccessFile, value: Int) {
            file.write(byteArrayOf(
                (value and 0xFF).toByte(),
                ((value shr 8) and 0xFF).toByte(),
                ((value shr 16) and 0xFF).toByte(),
                ((value shr 24) and 0xFF).toByte(),
            ))
        }
    }
}
