package com.harken.android.audio

import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Mirrors tests/Harken.Core.UnitTests/Audio/WavWriterTests.cs — header patch-on-close
// and the orphaned-header repair from commit 503fed7.
class WavWriterTest {

    @Test
    fun closePatchesRiffAndDataLengths() {
        val file = kotlin.io.path.createTempFile().toFile()
        file.delete()
        WavWriter(RandomAccessFile(file, "rw")).use { it.write(ByteArray(100), 0, 100) }

        val raf = RandomAccessFile(file, "r")
        raf.seek(4)
        assertEquals(136, readIntLE(raf)) // 36 + 100
        raf.seek(40)
        assertEquals(100, readIntLE(raf))
        assertEquals(144L, file.length()) // 44 header + 100 data
        raf.close()
        file.delete()
    }

    @Test
    fun repairHeaderFixesAnOrphanedFile() {
        val file = kotlin.io.path.createTempFile().toFile()
        file.delete()
        val raf = RandomAccessFile(file, "rw")
        val writer = WavWriter(raf)
        // Simulate process death: write data but never close (so lengths stay 0).
        val chunk = ByteArray(200)
        writer.write(chunk, 0, chunk.size)
        raf.close()

        assertTrue(WavWriter.repairHeader(file.absolutePath))

        val check = RandomAccessFile(file, "r")
        check.seek(40)
        assertEquals(200, readIntLE(check))
        check.close()
        file.delete()
    }

    @Test
    fun repairHeaderIsANoOpWhenAlreadyCorrect() {
        val file = kotlin.io.path.createTempFile().toFile()
        file.delete()
        WavWriter(RandomAccessFile(file, "rw")).use { it.write(ByteArray(50), 0, 50) }

        assertFalse(WavWriter.repairHeader(file.absolutePath))
        file.delete()
    }

    private fun readIntLE(file: RandomAccessFile): Int {
        val b = ByteArray(4)
        file.readFully(b)
        return (b[0].toInt() and 0xFF) or
            ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or
            ((b[3].toInt() and 0xFF) shl 24)
    }
}
