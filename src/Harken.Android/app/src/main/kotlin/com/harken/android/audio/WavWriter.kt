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

    fun write(pcm: ByteArray, offset: Int, length: Int) {
        file.seek(WavFormat.HeaderLength.toLong() + dataLength)
        file.write(pcm, offset, length)
        dataLength += length
    }

    override fun close() {
        patchLengths()
        file.close()
    }

    private fun patchLengths() {
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
