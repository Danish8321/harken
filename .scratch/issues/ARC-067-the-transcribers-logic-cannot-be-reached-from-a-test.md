# ARC-067 — Nothing inside `OnDeviceTranscriber` can be reached from a JVM test

- **Severity:** low
- **Area:** `speech/OnDeviceTranscriber.kt`, `audio/WavPayload.kt`, `telemetry/Telemetry.kt`
- **Status:** fixed

## Problem

Slice-09 review finding **S2**, the part of it still open: `TranscriptionCoordinator` and
`ModelDownloadManager` got tests (ARC-063, ARC-066), `OnDeviceTranscriber` never did. The
finding called it "pure-JVM-testable". It was not, and two probes said so:

```
PROBE ctor=java.lang.UnsatisfiedLinkError: no harken_whisper_jni in java.library.path
PROBE json length=0
ProbeTest > probe org json FAILED
```

1. `companion object { init { System.loadLibrary("harken_whisper_jni") } }` compiles into the
   *outer* class's static initialiser, so touching `OnDeviceTranscriber` at all — even
   calling its constructor — throws on a JVM runner. Every one of its private helpers was
   behind that: the WAV reader, the span-offset arithmetic, the telemetry format string.
2. `org.json` ships in `android.jar`, and the unit-test `android.jar` is stubs. With
   `isReturnDefaultValues = true` (there since the error-surfacing pass, for `android.util.Log`)
   those stubs return `0` and `null` rather than throwing, so `JSONArray(realJson).length()`
   is `0`. A test of `parseNativeSegments` written against the stub passes while reading
   nothing.

So the code with the actual defect history — the per-sample `readFully` that cost 11.4
seconds of a five-minute decode, the `Locale` that emitted `realtimeFactor=1,84` (ARC-029) —
was untestable by construction, not by omission.

## The fix

Move what is not JNI out of the class, each piece to where it belongs rather than to one
"testable bits" file:

- **`audio/WavPayload.kt`** — `sampleCount`, `windowRms`, `samples`, formerly
  `pcmSampleCount` / `scanWindowRms` / `readSamples`. They are written in terms of
  `WavFormat`, `SpeechSpans` and `Pcm16`, all of which live in `audio`, and `WavFormat`
  already reads a `File`. Each still takes an open `RandomAccessFile`: a decode reads one
  file once per span, and a reader that opened the path itself would trade the seek for an
  open.
- **`Telemetry.realtimeFactor`** — joins `elapsedMsSince`, which already owns the shape of a
  telemetry value, next to the `Locale.ROOT` rule the rest of that file is written around.
- **`speech/NativeSegments.kt`** — `LocalTranscribedSegment`, `NativeSegment`,
  `parseNativeSegments`, and `List<NativeSegment>.atSpan(startSample)`, which is the offset
  arithmetic that used to be an inline `map` inside the span loop.

`OnDeviceTranscriber` keeps what only it can do: the native calls, the abort flag and the
`ensureActive` that turns it into a cancellation, the span loop, the breadcrumb, and the
three telemetry events. The file goes from 363 lines to 238 and imports neither `org.json`
nor `java.nio`.

One new test-only dependency, `org.json:json`, declared in `libs.versions.toml` with the
reason. It shadows the stub on the unit-test classpath and reaches no build of the app; the
device keeps using Android's own `org.json`.

## Evidence

`.claude/scripts/check.sh` and `.claude/scripts/test-fast.sh` both pass (260 tests, up from
244). Sixteen new: `WavPayloadTest` 7, `NativeSegmentsTest` 6, `TelemetryTest` 3.

`WavPayloadTest` writes real WAVs with the real `WavWriter`, because every defect this code
has had was byte arithmetic and a hand-built fixture would have been built with the same
arithmetic the reader uses. It covers a span read across more than one 64 KiB block, a span
whose last block is partial, the header not being counted as audio, a file too short to hold
a header, one level per window, and a recording that ends mid-window.

Mutation checks, one at a time, everything else in place:

```
# testImplementation(libs.json) commented out
NativeSegmentsTest > both fields are read, text exactly as whisper wrote it FAILED
NativeSegmentsTest > text that is not JSON is raised, not read as silence FAILED
260 tests completed, 2 failed

# `startSample * 1000L` narrowed to `startSample * 1000`
NativeSegmentsTest > a span an hour in is not truncated to an int of milliseconds FAILED
260 tests completed, 1 failed

# Locale.ROOT replaced with Locale.getDefault()
TelemetryTest > the number is a decimal point on a phone that writes decimal commas FAILED
260 tests completed, 1 failed

# the header dropped from the seek in WavPayload.samples
WavPayloadTest > a span is read back exactly, across more than one block FAILED
WavPayloadTest > the last block of a span is read to its own length FAILED
260 tests completed, 2 failed
```

All four restored; both gates green.

The first of those is worth reading twice: it is the stub lying, not a missing library. Note
also that `an empty array is a span whisper heard nothing in` **passed** under the stub, for
the wrong reason — which is exactly the vacuous test this dependency exists to prevent, and
the reason the other two assert on content.

## What this does not cover

- **The span loop itself.** `transcribe` — model load, the abort flag, `ensureActive` after
  each span, the breadcrumb around the native call, the three telemetry events, and the
  progress fraction — is still reachable only with the native library loaded. Covering it
  means `androidTest`, which is outside `test-fast.sh`. Nothing here changed that, and no
  JVM test can.
- **Android's `org.json` is not the one under test.** It is a reimplementation, not the
  reference one now on the test classpath. For two fields read by name from JSON this app's
  own JNI writes, the risk is thin — but a test passing here is not proof about the device's
  parser.
- Nothing was run on a device for this change.
