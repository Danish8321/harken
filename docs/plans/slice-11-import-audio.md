# Slice 11: Import audio

Implements [ADR-0016](../adr/0016-transcode-imports-to-the-canonical-recording.md).
Branch: new, off `master`.

Bringing an audio file the user already has into Harken as a Session, by decoding it into
a canonical Recording. Two entry points — an in-app picker and an `ACTION_SEND` share
target — converging on one import path.

**No schema change.** The database stays at version 4; `schema.sh` is not involved and
`test-full.sh` is not a gate for this slice. Gates are `check.sh` and `test-fast.sh`, plus
a manual on-device pass recorded the way UI-042 and UI-045 were.

**Package name.** `import` is a Kotlin keyword and cannot be a package segment. Use
`com.harken.android.ingest` for the new code.

## Ground rules carried from the grill

- After import, nothing distinguishes an imported Session from a captured one. No `origin`
  column, no badge.
- `startedAt` is import time. The untitled name comes from the source filename, not
  `PartOfDay` — an import arrives with a name, a capture does not.
- Status on creation is the literal `"Recorded"`, the exact string the Library gates its
  Transcribe button on (`LibraryScreen.kt:554`). Import does **not** auto-transcribe.
- A decode failure creates no Session at all.
- One import at a time; none while recording.

## Tasks

### Task 1 — Downmix and decimation — **done**
**Files:** `.../kotlin/com/harken/android/audio/Resampling.kt` (new),
`.../test/kotlin/com/harken/android/audio/ResamplingTest.kt` (new).
**Change:** `Downmix.toMono` averages channels into a caller-supplied buffer (ARC-013).
`Resampler16k(sourceRate)` low-passes below 8 kHz then resamples — a 96-tap windowed sinc
over 512 precomputed phases, cutoff 7 kHz, no special-casing of 44100 vs 48000. Bare
linear interpolation is explicitly not acceptable here (ADR-0016 §2). Already-16 kHz is a
pass-through; below 16 kHz the cutoff drops to the source's own Nyquist, since there is
nothing to fold.

**Deviation from plan:** specified as pure functions; shipped as a *stateful* class. A
decoder delivers one buffer at a time and a 96-tap filter needs samples either side of
each seam, so resampling buffers independently would put a discontinuity — an audible
click — every few milliseconds. History crosses the boundary and the caller calls
`drain()` for the tail. `ResamplingTest.buffer boundaries do not change the result` is
what holds this: chunked input must produce a byte-identical result to one-shot.

**Verify:** `check.sh` OK, `test-fast.sh` OK. `ResamplingTest` 8/8.
The anti-aliasing test was falsified before being trusted — with the cutoff widened to the
source's own Nyquist (i.e. no anti-alias filter, which is what linear interpolation
amounts to), `content above 8 kHz is attenuated, not folded down` fails with "survived at
100% of its input level", and it is the only test that fails. The filter is what that test
is measuring.

### Task 2 — Decode a file to a canonical WAV — **done**
**Files:** `.../kotlin/com/harken/android/ingest/AudioImporter.kt` (new),
`.../kotlin/com/harken/android/audio/Pcm16.kt`,
`.../test/kotlin/com/harken/android/audio/Pcm16Test.kt`.
**Change:** `MediaExtractor` selects the first audio track of the source file (a video
container is fine — ADR-0016 §1); `MediaCodec` decodes it; each output buffer goes through
Task 1 and into a `WavWriter` opened on a **`cacheDir`** path, never `filesDir`
(`WavWriter` requires a fresh zero-length file, `WavWriter.kt:33`). Reports progress as
presentation-time against `KEY_DURATION`. Honours an abort flag, mirroring
`nativeSetAbort`'s role in transcription. On completion, and only then, `renameTo` the
target `filesDir/<uuid>.wav`. On any failure or abort, delete the partial and return a
typed failure — no Session, no file in `filesDir` (ADR-0016 §4).
**Additions beyond the plan:**
- `Pcm16.toBytes` — the decoder hands over samples and `WavWriter` appends bytes, and the
  byte order between them belongs to `Pcm16`, which already owns that layout, not to the
  importer. Covered by two new `Pcm16Test` cases.
- `ImportOutcome.StorageFailed` — the plan's failure list was all decode failures, but the
  move into place can fail too, and a Session pointing at nothing is worse than a refusal.
  `moveIntoPlace` renames, falls back to a copy, and only then gives up.
- Float PCM output is handled: most decoders emit 16-bit, some emit
  `ENCODING_PCM_FLOAT`, and the output format is the only place that says which.
- A mid-stream sample-rate change drains the old resampler before building the new one, so
  the seam is a seam rather than a gap.

**Verify:** `check.sh` OK, `test-fast.sh` OK (`Pcm16Test` 8/8). Real decoding needs a
device — `MediaCodec` does not exist on the JVM — so behavioural proof lands in Task 10's
manual pass, and failure-path tests that don't need a codec go in Task 9. **Nothing here
is yet proven to decode anything.**

### Task 3 — Pre-flight size gate — **done**
**Files:** `.../kotlin/com/harken/android/ingest/ImportPreflight.kt` (new),
`.../test/kotlin/com/harken/android/ingest/ImportPreflightTest.kt` (new).
**Change:** from `MediaFormat` `KEY_DURATION`, compute the exact output size using the
existing `WavFormat.BYTES_PER_SECOND` constant — do not restate `32000` (ARC-054 is the
standing lesson about a constant living in three places). Compare against `StatFs` free
space. Returns one of: fits; needs confirmation (above a large-size threshold, ~500 MB);
or refuses (won't fit, plus a margin). The Session Cap is deliberately not consulted
(ADR-0016 §5).
**Additions beyond the plan:**
- The staged source counts toward the requirement, not just the Recording it becomes: both
  exist at once, because the decode cannot release the source until it has read it.
- A **free-space margin** (256 MB), separate from "does it fit". An import that technically
  fits but leaves the phone in its own low-storage state is not a success.
- A container that states no duration is allowed through rather than refused. Some streams
  genuinely carry none; the decode stages in `cacheDir`, so the rare file that turns out
  not to fit fails somewhere Android reclaims.

**Verify:** `check.sh` OK, `test-fast.sh` OK, `ImportPreflightTest` 8/8. Free space and
byte counts are injected, so none of it touches a real filesystem. Unlike Task 1's filter,
these tests are transparent arithmetic — `an import that fits but wedges the device is
refused too` puts 100 MB into 300 MB of free space, which is plainly a pass without the
margin — so they were not separately falsified.

### Task 4 — Single-flight import state
**Files:** `.../kotlin/com/harken/android/ingest/ImportCoordinator.kt` (new).
**Change:** `AtomicReference` compare-and-set admitting one import at a time, mirroring
`TranscriptionCoordinator.kt:76`. Exposes `activeImport: StateFlow<...>` for the UI.
Refuses to start when `RecordingState` holds an in-progress capture — decode is CPU-heavy
and would degrade live capture on a 6 GB device (ADR-0014).
**Verify:** `./gradlew.bat testDebugUnitTest`. Tests: second concurrent start is refused;
start during a simulated recording is refused; state clears on success and on failure.

### Task 5 — Session creation from an import
**Files:** `SessionRepository.kt`, `.../kotlin/com/harken/android/ingest/ImportTitle.kt`
(new), `.../test/kotlin/com/harken/android/ingest/ImportTitleTest.kt` (new).
**Change:** `ImportTitle.from(sourceFileName)` strips the extension, trims, collapses
whitespace and caps length; returns null when nothing usable remains. A new repository
entry point creates the Session with `startedAt = endedAt = Instant.now()` minus the
audio duration for `startedAt` (same derivation the recorder uses,
`RecordingForegroundService.kt:296`), `durationSeconds` from
`WavFormat.durationSeconds(File)` on the finished canonical WAV — the same authority the
recorder uses, now legitimately, because the file is canonical — `localTitle` from
`ImportTitle`, and `transcriptionStatus = "Recorded"`. When `ImportTitle` returns null,
leave `localTitle` null so the existing `PartOfDay` derivation applies as the fallback.
**Verify:** `./gradlew.bat testDebugUnitTest`. Title tests cover extension stripping,
whitespace, length cap, and the empty→null fallback.

### Task 6 — ImportService
**Files:** `.../kotlin/com/harken/android/ingest/ImportService.kt` (new),
`AndroidManifest.xml`, `strings.xml`.
**Change:** a `dataSync` foreground service in the shape of `ExportService`
(manifest `:66-69`), `exported=false`. New notification channel `"importing"` and a new
notification id (1003 — 1001 and 1002 are taken). Determinate progress from Task 2, a
Cancel action mirroring `CANCEL_TRANSCRIPTION`, and on success a **Transcribe** action so
a share-sheet import doesn't dead-end. Takes a plain file path, never a `content://` Uri —
an `ACTION_SEND` grant is one-shot and activity-scoped, so it cannot survive the handoff.
Orchestrates: preflight (Task 3) → decode (Task 2) → rename → create Session (Task 5),
releasing the coordinator (Task 4) on every exit path.
**Verify:** `./gradlew.bat compileDebugKotlin` and `check.sh` (Lint will catch a missing
`foregroundServiceType` or a channel mistake). Behaviour in Task 10.

### Task 7 — Picker entry point
**Files:** `RecordScreen.kt`, `CaptureViewModel.kt` (or a new `ImportViewModel`),
`AppContainer.kt`, `strings.xml`, `LibraryScreen.kt`.
**Change:** `ActivityResultContracts.OpenDocument` with
`arrayOf("audio/*", "video/*")` — video containers are accepted (ADR-0016 §1). The button
occupies the **empty `CenterEnd` slot** in the Record screen's bottom control `Box`
(`RecordScreen.kt:299-331`), hidden while recording, where `PauseButton` at CenterStart is
its counterpart. The Library empty state gains "Import a file" as a secondary action
beside "Record something" (`LibraryScreen.kt:202-219`) — a new user whose reason for
installing Harken is a folder of existing files is currently told only to record.
On selection the ViewModel raw-copies the stream to `cacheDir` (bytes only, no decode) and
starts `ImportService` with that path. Same staging step as Task 8 so there is exactly one
import path.
**Verify:** `./gradlew.bat compileDebugKotlin`, `check.sh` (touch-target and semantics
lint, per UI-004/UI-005). Manual in Task 10.

### Task 8 — Share target
**Files:** `AndroidManifest.xml`, `MainActivity.kt`.
**Change:** an `ACTION_SEND` intent filter on `MainActivity` for `audio/*` and `video/*`.
**No `ACTION_VIEW`** — that is a playback verb, and claiming it would put Harken in the
open-with list for every audio file on the phone. `MainActivity` (already `singleTop`, so
handle both `onCreate` and `onNewIntent`) raw-copies the incoming stream to `cacheDir` and
starts `ImportService`, then routes to the Record screen. A share arriving while a
recording is in progress, or while another import runs, is refused with a message rather
than queued (Task 4).
**Verify:** `./gradlew.bat compileDebugKotlin`, `check.sh`. Manual in Task 10.

### Task 9 — Copy and failure surface
**Files:** `strings.xml`, plus the screens touched above.
**Change:** new strings for the import button, progress, cancel, the size confirmation
(naming the actual megabytes), and each typed failure: no audio track, unsupported or
DRM-protected codec, decode failed part-way, not enough space, recording in progress,
import already running. Revise two existing strings that assert microphone-only:
`library_empty_body` "Recordings appear here as soon as you **stop** one" (`:86`) and
`library_empty_action` "Record something" (`:87`). The format lines (`:54`, `:71`, `:164`)
stay as-is — they describe capture, which is still accurate.
**Verify:** `check.sh` (Lint flags hardcoded strings and missing translations).
Unit tests for the failure-to-message mapping, so a new failure type can't ship without
copy.

### Task 10 — Verification pass
**Change:** no code. Run the gates, then a real-device pass.
**Verify:**
- `.claude/scripts/check.sh` — OK.
- `.claude/scripts/test-fast.sh` — OK.
- `test-full.sh` not required: no schema change, so no migration to assert.
- On-device, on the arm64 test phone:
  - Import an m4a via the picker; confirm the Session is titled from the filename, plays
    back, and transcribes when tapped.
  - Import via the share sheet from a messaging app (Opus voice note) — the use case the
    share target exists for.
  - Import the audio from an mp4 video.
  - Confirm a cancelled import leaves **no** Session and no file in `filesDir`.
  - Confirm a large import shows the size confirmation with a plausible number.
  - Confirm an import is refused while recording, and a second import is refused while one
    runs.
  - Kill the app mid-import; confirm no partial WAV is adopted by `RecordingRecovery` as a
    Session (the partial lives in `cacheDir`, which is never scanned).
  - Re-open the app after a completed import and confirm exactly one Session exists.

Record the result in this file the way slice-09 and UI-042 recorded theirs.

## Fixtures

Tests needing real encoded audio use the existing `app/src/test/resources/
ami-es2002a-0-90s.wav` (AMI Meeting Corpus, CC BY 4.0, already attributed in
`ATTRIBUTION.md`) re-encoded to m4a and Opus at 44.1 kHz stereo. Add the derived files
alongside it and extend `ATTRIBUTION.md` — same source, same licence.

## Out of scope

- **Multiple files per import.** No `ACTION_SEND_MULTIPLE`, no multi-select. There is no
  transcription queue, and `TranscriptionCoordinator` is single-flight — three shared
  voice notes would silently drop two.
- **Auto-transcribe on import.** Blocked on the same missing queue. The notification's
  Transcribe action covers the flow at a fraction of the cost.
- **Duplicate detection.** Importing the same file twice makes two Sessions.
- **Retaining the source file** for a future higher-fidelity re-transcribe — rejected in
  ADR-0016 with reasons.
