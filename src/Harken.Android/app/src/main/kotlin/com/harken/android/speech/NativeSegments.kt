package com.harken.android.speech

import com.harken.android.audio.WavFormat
import org.json.JSONArray

/**
 * A single decoded segment from an on-device whisper.cpp transcription. Deliberately its
 * own type rather than a shape shared with a server: this is a local-only, on-device
 * concept, and the backend's transcription client was deleted outright (ADR-0011).
 */
data class LocalTranscribedSegment(
    val offsetSeconds: Int,
    val text: String,
)

/**
 * The wire shape [OnDeviceTranscriber.nativeTranscribe] returns, before a span's own
 * position is added back. Offsets are relative to what whisper was handed, which is one
 * span, not the recording.
 */
internal data class NativeSegment(
    val offsetMs: Long,
    val text: String,
)

/**
 * Decodes the JSON one span of native transcription returns:
 * `[{"offsetMs": 0, "text": " Hello."}, ...]`.
 *
 * Read field by field rather than through a reflective mapper. A mapper needs the field
 * names and the concrete class to survive minification, and when they do not the
 * transcription fails on release builds only — which is how it failed the first time R8 was
 * enabled: "Abstract classes can't be instantiated ... Class name: n2.f". Two fields do not
 * justify carrying that risk, or the dependency.
 *
 * An empty array is a span whisper heard nothing in, and is not an error. A decode that
 * actually failed throws before this is reached (ARC-058).
 */
internal fun parseNativeSegments(json: String): List<NativeSegment> {
    val array = JSONArray(json)
    return (0 until array.length()).map { index ->
        val segment = array.getJSONObject(index)
        NativeSegment(
            offsetMs = segment.getLong("offsetMs"),
            text = segment.getString("text"),
        )
    }
}

/**
 * Moves a span's segments into the recording's own timeline, given where the span starts.
 *
 * Whisper times each segment from the start of what it was given, so without this every
 * span's transcript would appear to begin at zero and the second half of a meeting would
 * be stamped over the first.
 */
internal fun List<NativeSegment>.atSpan(startSample: Int): List<LocalTranscribedSegment> {
    val spanOffsetMs = startSample * 1000L / WavFormat.SAMPLE_RATE
    return map { segment ->
        LocalTranscribedSegment(
            offsetSeconds = ((spanOffsetMs + segment.offsetMs) / 1000L).toInt(),
            text = segment.text,
        )
    }
}
