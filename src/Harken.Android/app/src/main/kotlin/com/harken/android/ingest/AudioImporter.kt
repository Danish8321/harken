package com.harken.android.ingest

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.harken.android.audio.Downmix
import com.harken.android.audio.Pcm16
import com.harken.android.audio.Resampler16k
import com.harken.android.audio.WavFormat
import com.harken.android.audio.WavWriter
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How an import ended. Every path but [Imported] leaves nothing behind — no Session, and
 * no file in the recordings directory (ADR-0016 §4).
 */
sealed interface ImportOutcome {
    /** The finished canonical Recording, already moved into place. */
    data class Imported(
        val file: File,
        val durationSeconds: Int,
    ) : ImportOutcome

    /** A file with no audio in it — a photo, a document, a video with a silent track. */
    object NoAudioTrack : ImportOutcome

    /** Nothing on this device can decode it, or it is DRM-protected. */
    object UnsupportedFormat : ImportOutcome

    /** Decoding began and then failed — a truncated or corrupt file. */
    object DecodeFailed : ImportOutcome

    /** The user stopped it. */
    object Cancelled : ImportOutcome

    /** The decoded audio would not fit, or the finished file could not be moved into place. */
    object StorageFailed : ImportOutcome
}

/**
 * Turns a file the user already has into a canonical Recording.
 *
 * Decoding is the platform's job — [MediaExtractor] and [MediaCodec] read every container
 * Android knows, which is why this needs no dependency and no ffmpeg. What arrives from
 * them is whatever the file held: 48 kHz stereo AAC, 8 kHz mono AMR, 44.1 kHz MP3. What
 * leaves is always [WavFormat]: 16 kHz, 16-bit, mono. Nothing downstream — the player, the
 * exporter, `OnDeviceTranscriber`'s hardcoded 44-byte offset — learns that this Session
 * began life as anything else (ADR-0016).
 *
 * **Nothing is written where a recording lives until it is whole.** The decode goes to a
 * partial file in the staging directory and is renamed into place only on success. A
 * half-written WAV in `filesDir` would be adopted by `RecordingRecovery` as a real
 * recording and `repairHeader` would patch it into something that *looks* valid — a
 * Session whose audio silently stops in the middle.
 */
class AudioImporter(
    private val stagingDir: File,
) {
    /**
     * Decodes [source] into [target], reporting progress from 0f to 1f. Checks [cancelled]
     * between buffers, so a cancel lands within a few milliseconds rather than at the end
     * of the file.
     */
    fun import(
        source: File,
        target: File,
        cancelled: AtomicBoolean,
        onProgress: (Float) -> Unit,
    ): ImportOutcome {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(source.absolutePath)
        } catch (_: Exception) {
            extractor.release()
            return ImportOutcome.UnsupportedFormat
        }

        val track = audioTrackOf(extractor)
        if (track < 0) {
            extractor.release()
            return ImportOutcome.NoAudioTrack
        }

        val format = extractor.getTrackFormat(track)
        val mime = format.getString(MediaFormat.KEY_MIME)
        if (mime == null) {
            extractor.release()
            return ImportOutcome.NoAudioTrack
        }

        val codec =
            try {
                MediaCodec.createDecoderByType(mime).also {
                    // A null MediaCrypto against a protected track throws here, which is
                    // the only signal this API gives that the file is DRM-locked.
                    it.configure(format, null, null, 0)
                    it.start()
                }
            } catch (_: Exception) {
                extractor.release()
                return ImportOutcome.UnsupportedFormat
            }

        extractor.selectTrack(track)

        val partial = File(stagingDir, "${target.nameWithoutExtension}.partial.wav")
        partial.delete() // WavWriter requires a fresh file, and a previous run may have died here.

        return try {
            decode(extractor, codec, format, partial, cancelled, onProgress).let { outcome ->
                if (outcome is Decoded.Complete) moveIntoPlace(partial, target) else outcome.asOutcome()
            }
        } catch (_: Exception) {
            ImportOutcome.DecodeFailed
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
            partial.delete()
        }
    }

    private fun audioTrackOf(extractor: MediaExtractor): Int {
        for (track in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME)
            // The audio track of a video container counts: the AAC inside an mp4 is the
            // same codec as the m4a beside it, and reached identically (ADR-0016 §1).
            if (mime != null && mime.startsWith("audio/")) return track
        }
        return -1
    }

    private sealed interface Decoded {
        object Complete : Decoded

        object Cancelled : Decoded

        object Failed : Decoded

        fun asOutcome(): ImportOutcome =
            when (this) {
                is Cancelled -> ImportOutcome.Cancelled
                else -> ImportOutcome.DecodeFailed
            }
    }

    private fun decode(
        extractor: MediaExtractor,
        codec: MediaCodec,
        inputFormat: MediaFormat,
        partial: File,
        cancelled: AtomicBoolean,
        onProgress: (Float) -> Unit,
    ): Decoded {
        val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) inputFormat.getLong(MediaFormat.KEY_DURATION) else 0L
        var sourceRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var floatOutput = false
        var resampler = Resampler16k(sourceRate)

        var interleaved = ShortArray(SCRATCH_SAMPLES)
        var mono = ShortArray(SCRATCH_SAMPLES)
        var resampled = ShortArray(SCRATCH_SAMPLES)
        var bytes = ByteArray(SCRATCH_SAMPLES * 2)

        val info = MediaCodec.BufferInfo()
        var sawInputEnd = false
        var sawOutputEnd = false
        var lastReported = -1f

        WavWriter(RandomAccessFile(partial, "rw")).use { writer ->
            while (!sawOutputEnd) {
                if (cancelled.get()) return Decoded.Cancelled

                if (!sawInputEnd) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: return Decoded.Failed
                        val read = extractor.readSampleData(buffer, 0)
                        if (read < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            sawInputEnd = true
                        } else {
                            codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // The decoder, not the container, is the authority on what it is
                        // about to hand over — and for some sources this is the first
                        // truthful statement of the rate.
                        val output = codec.outputFormat
                        val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatOutput =
                            output.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                        if (rate != sourceRate) {
                            // Flush the old filter's tail before the new one starts, so a
                            // mid-file rate change is a seam rather than a gap.
                            val tail = ShortArray(resampler.maxDrainOutput())
                            val drained = resampler.drain(tail)
                            if (drained > 0) {
                                if (bytes.size < drained * 2) bytes = ByteArray(drained * 2)
                                Pcm16.toBytes(tail, drained, bytes)
                                writer.write(bytes, 0, drained * 2)
                            }
                            sourceRate = rate
                            resampler = Resampler16k(sourceRate)
                        }
                    }

                    else -> {
                        if (index < 0) return Decoded.Failed
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index) ?: return Decoded.Failed
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)

                            val count = if (floatOutput) info.size / 4 else info.size / 2
                            if (interleaved.size < count) interleaved = ShortArray(count)
                            if (floatOutput) {
                                val floats = buffer.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                for (i in 0 until count) {
                                    val scaled = (floats.get(i) * Short.MAX_VALUE).toInt()
                                    interleaved[i] = scaled.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                                }
                            } else {
                                buffer.order(ByteOrder.nativeOrder()).asShortBuffer().get(interleaved, 0, count)
                            }

                            val monoCount = Downmix.monoLength(count, channels)
                            if (mono.size < monoCount) mono = ShortArray(monoCount)
                            Downmix.toMono(interleaved, count, channels, mono)

                            val capacity = resampler.maxOutputFor(monoCount)
                            if (resampled.size < capacity) resampled = ShortArray(capacity)
                            val produced = resampler.process(mono, monoCount, resampled)
                            if (produced > 0) {
                                if (bytes.size < produced * 2) bytes = ByteArray(produced * 2)
                                Pcm16.toBytes(resampled, produced, bytes)
                                writer.write(bytes, 0, produced * 2)
                            }

                            if (durationUs > 0) {
                                val fraction = (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                                // A notification cannot show more than whole percents, and
                                // a decoder delivers buffers far faster than that.
                                if (fraction - lastReported >= PROGRESS_STEP) {
                                    onProgress(fraction)
                                    lastReported = fraction
                                }
                            }
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEnd = true
                    }
                }
            }

            val tail = ShortArray(resampler.maxDrainOutput())
            val drained = resampler.drain(tail)
            if (drained > 0) {
                if (bytes.size < drained * 2) bytes = ByteArray(drained * 2)
                Pcm16.toBytes(tail, drained, bytes)
                writer.write(bytes, 0, drained * 2)
            }
        }

        onProgress(1f)
        return Decoded.Complete
    }

    /**
     * Moves the finished decode to where recordings live.
     *
     * A rename is atomic and is what normally happens — the staging directory and the
     * recordings directory are both internal storage. The copy is for the case where they
     * are not, because a rename that silently fails would leave a Session pointing at
     * nothing.
     */
    private fun moveIntoPlace(
        partial: File,
        target: File,
    ): ImportOutcome {
        if (!partial.renameTo(target)) {
            val copied = runCatching { partial.copyTo(target, overwrite = true) }.isSuccess
            if (!copied) {
                target.delete()
                return ImportOutcome.StorageFailed
            }
        }
        return ImportOutcome.Imported(target, WavFormat.durationSeconds(target))
    }

    private companion object {
        /** How long to wait on a codec buffer. Long enough not to spin, short enough that a cancel lands. */
        const val TIMEOUT_US = 10_000L

        /** Starting size of the reusable scratch buffers; they grow to whatever the codec hands over. */
        const val SCRATCH_SAMPLES = 8192

        /** Report progress at whole-percent granularity, which is all a notification can draw. */
        const val PROGRESS_STEP = 0.01f
    }
}
