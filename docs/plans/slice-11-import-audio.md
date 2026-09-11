# Slice 11: Import audio

Implements [ADR-0016](../adr/0016-transcode-imports-to-the-canonical-recording.md).
Branch: `feat/import-audio`, off `master`.

**Status (2026-09-11): merged to `master` as `1157353`.** All eleven tasks done, both
gates green, and the on-device pass recorded under Task 10. The two follow-ups that pass
turned up (ARC-055, ARC-056) were fixed on the same branch rather than left open, so the
merge carries them.

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

### Task 4 — Single-flight import state — **done**
**Files:** `.../kotlin/com/harken/android/ingest/ImportCoordinator.kt` (new),
`.../test/kotlin/com/harken/android/ingest/ImportCoordinatorTest.kt` (new).
**Change:** `AtomicReference` compare-and-set admitting one import at a time, mirroring
`TranscriptionCoordinator.kt:76`. Exposes `activeImport: StateFlow<...>` for the UI.
Refuses to start when `RecordingState` holds an in-progress capture — decode is CPU-heavy
and would degrade live capture on a 6 GB device (ADR-0014).
**Deviation from plan:** it owns no coroutine. `TranscriptionCoordinator` runs the work it
admits; this only decides who may run, because `ImportService` (Task 6) is what holds the
process up. Admission returns the per-import cancel flag rather than the coordinator
holding a `Job`.

**Known gap, deliberate.** The guard is one-directional: an import is refused while the
microphone is open, but a recording started *during* an import is not refused. Blocking
the app's primary function to protect a convenience is the wrong trade, and pausing a
decode mid-file is more machinery than this slice should carry. What it may cost is
capture buffer overruns while a decode saturates the CPU — added to Task 10's device pass
to find out whether it is real on a 6 GB device.

**Verify:** `check.sh` OK, `test-fast.sh` OK, `ImportCoordinatorTest` 8/8. Covers: a
second concurrent import refused; refusal while recording, which must not claim the slot;
a late finisher unable to clear the import that replaced it; and a cancelled import not
poisoning the next one's flag.

### Task 5 — Session creation from an import — **done**
**Files:** `SessionRepository.kt`, `.../kotlin/com/harken/android/ingest/ImportTitle.kt`
(new), `.../test/kotlin/com/harken/android/ingest/ImportTitleTest.kt` (new).
**Change:** `ImportTitle.from(fileName)` strips the last extension, collapses whitespace,
caps length, and returns null when nothing usable remains — which is what hands the
Library back its existing `PartOfDay` derivation. Only the extension is removed:
underscores and capitalisation are the user's own naming, and prettifying them would be
inventing a title rather than reading one.

**Deviation from plan:** no new repository entry point. `createLocalSession` took an
optional `localTitle` instead. A second creation method would have been a near-duplicate
of an eight-line insert, and the glossary is explicit that an imported Session is a
Session — one creation path says that in code. `SessionRepository.createLocalSession`
remains the only writer of a session row.

**Clarification on `startedAt`.** Q3 settled on import time; the row uses the recorder's
own convention for that — `endedAt = now`, `startedAt = now - durationSeconds`
(`RecordingForegroundService.kt:296`). The import *ends* now, so the row stays internally
coherent instead of claiming a Session with four hours of audio and no elapsed time, and
any realistic import still sorts to the top of the Library. Duration itself is never
derived from this span: `durationSeconds` is the authority (ARC-009).

**Verify:** `check.sh` OK, `test-fast.sh` OK, `ImportTitleTest` 9/9 — extension stripping,
multi-dot names, no-extension names, whitespace, the length cap, and all three routes to
the null fallback (extension-only, blank, and a Uri carrying no display name at all).

### Task 6 — ImportService — **done**
**Files:** `.../kotlin/com/harken/android/ingest/ImportService.kt` (new),
`LiveUpdateNotification.kt`, `TranscriptionService.kt`, `AndroidManifest.xml`,
`strings.xml`.
**Change:** a `dataSync` foreground service in the shape of `ExportService`
(manifest `:66-69`), `exported=false`. New notification channel `"importing"` and
notification id **1004** — 1003 is `ExportService`, correcting this plan's earlier claim.
Determinate progress from Task 2, a Cancel action mirroring `CANCEL_TRANSCRIPTION`, and on
success a **Transcribe** action so a share-sheet import doesn't dead-end. Takes a plain
file path, never a `content://` Uri — an `ACTION_SEND` grant is one-shot and
activity-scoped, so it cannot survive the handoff. Orchestrates: preflight (Task 3) →
decode (Task 2) → rename → create Session (Task 5), releasing the coordinator (Task 4) on
every exit path.

**Deviations from plan.**
- Progress is **indeterminate when the container carries no duration**, rather than
  always determinate: `MediaFormat.KEY_DURATION` is optional, and a bar pinned at 0% reads
  as a stall.
- Three notification ids, not one. 1004 is the ongoing import and dies with
  `stopForeground(STOP_FOREGROUND_REMOVE)`; failures use 1005; a completion uses an id
  derived from the session, so a second import's Transcribe action cannot displace a
  first's — that action is the only route back for someone who arrived from the share
  sheet and is not in the app.
- A refused start still calls `startForeground` before refusing, because
  `startForegroundService` gives every start five seconds to produce a notification. It
  posts to the same id and only tears the foreground down when no decode is running, or an
  `AlreadyImporting` refusal would demote the service out from under the import it
  refused for.
- `TranscriptionService` gained an `intent(...)` factory so the Transcribe action can wrap
  it in a `PendingIntent`; `start` now goes through it, and the extra keys stay private.
- One new string beyond the planned set, `import_failed_unknown`: the Session insert is the
  last step, and an exception there leaves a whole Recording in `filesDir` for
  `RecordingRecovery` to adopt — calling that "damaged" would be a lie.
- `notification_imported_untitled` was written and then deleted unused: an untitled import
  gets its name from `recordingTitle(null, PartOfDay.now())`, the same derivation the
  Library row will show, so the notification and the row cannot disagree.

**Verify:** `check.sh` OK, `test-fast.sh` OK. Lint's `MissingClass` on the manifest entry
was what proved the registration is real. Behaviour in Task 10.

### Task 7 — Picker entry point — **done**
**Files:** `.../kotlin/com/harken/android/ingest/ImportStaging.kt` (new),
`.../kotlin/com/harken/android/ui/ImportViewModel.kt` (new),
`.../kotlin/com/harken/android/ui/ImportPicker.kt` (new), `RecordScreen.kt`,
`LibraryScreen.kt`, `components/HarkenStates.kt`, `strings.xml`.
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

**Deviations from plan.**
- **No `CaptureViewModel` or `AppContainer` change.** A separate `ImportViewModel` holds the
  pre-service flow, and `ImportStaging` — the raw copy plus the display-name lookup — is a
  plain object both entry points call, so Task 8 can stage from an Activity that has no
  ViewModel. Capture and import share a screen, not a state machine.
- **The size confirmation is here, not in Task 9.** It cannot live in `ImportService`: by
  then there is no screen in front of the user to ask. Task 9 keeps the copy revision and
  the failure-mapping tests. Its strings came forward with it, plus `import_refused_title`
  ("Not right now" — a refusal is not a failure) and `import_failed_copy`, because mapping
  an unreadable stream onto "not enough space" would be naming the wrong cause.
- **`EmptyState` gained a secondary action**, shaped like `ErrorState`'s, rather than the
  Library composing its own second button.
- The dialog reuses `LibraryExporter.formatBytes` rather than growing a second byte
  formatter, and names the size of the *finished Recording* — the surprise is that it and
  the picked file differ.
- The ViewModel checks the refusals itself before staging. `ImportCoordinator` is still the
  invariant; this only avoids copying a gigabyte that is about to be thrown away.
- The button carries a busy state. Staging is the one part of an import with no
  notification behind it — the service does not exist until the bytes are in `cacheDir`.

**Verify:** `check.sh` OK, `test-fast.sh` OK. Behaviour in Task 10.

### Task 8 — Share target — **done**
**Files:** `AndroidManifest.xml`, `MainActivity.kt`,
`.../kotlin/com/harken/android/ingest/PendingImport.kt` (new), `ui/ImportPicker.kt`.
**Change:** an `ACTION_SEND` intent filter on `MainActivity` for `audio/*` and `video/*`.
**No `ACTION_VIEW`** — that is a playback verb, and claiming it would put Harken in the
open-with list for every audio file on the phone. `MainActivity` (already `singleTop`, so
handle both `onCreate` and `onNewIntent`) raw-copies the incoming stream to `cacheDir` and
starts `ImportService`, then routes to the Record screen. A share arriving while a
recording is in progress, or while another import runs, is refused with a message rather
than queued (Task 4).

**Deviations from plan.**
- **The Activity does not stage or start the service.** It reads the Uri — the one thing
  only it can do, while the grant is alive — and leaves it in `PendingImport` for
  `ImportViewModel` to pick up on the next composition. A share then gets the same size
  question and the same refusal dialogs a picked file does, instead of a second path where
  a refusal could only be a notification and a large import could not be declined at all.
  The Uri is taken exactly once.
- The `EXTRA_STREAM` is removed as it is read. Without that a rotation re-delivers the same
  intent and imports the file twice — two Sessions of the same audio, at twice the storage.
- **No routing to the Record screen.** A cold-start share already lands there, and pulling
  someone off the Library — the screen where the imported Session actually appears — to the
  one screen that shows neither the import nor its result would be worse than leaving them
  where they are. The progress notification is the import's surface.

**Verify:** `check.sh` OK, `test-fast.sh` OK. Behaviour in Task 10.

### Task 9 — Copy and failure surface — **done**
**Files:** `strings.xml`, `.../kotlin/com/harken/android/ingest/ImportMessages.kt` (new),
`ImportCoordinator.kt`, `ImportService.kt`, `ui/ImportViewModel.kt`,
`.../test/kotlin/com/harken/android/ingest/ImportMessagesTest.kt` (new).
**Change:** new strings for the import button, progress, cancel, the size confirmation
(naming the actual megabytes), and each typed failure: no audio track, unsupported or
DRM-protected codec, decode failed part-way, not enough space, recording in progress,
import already running. Revise two existing strings that assert microphone-only:
`library_empty_body` "Recordings appear here as soon as you **stop** one" (`:86`) and
`library_empty_action` "Record something" (`:87`). The format lines (`:54`, `:71`, `:164`)
stay as-is — they describe capture, which is still accurate.

**Deviations from plan.**
- **The mapping is one file, not a test.** Both callers — `ImportService`, which has only a
  notification, and `ImportViewModel`, which asks the same question before copying anything
  — had grown their own copy of it. They are now one `ImportOutcome.messageRes()` and one
  `ImportAdmission.messageRes()` in `ImportMessages.kt`, both exhaustive `when`s with **no
  `else`**. That, not the test, is what makes a new failure type unable to ship without
  copy: it is a compile error rather than a silent fall-through to "something went wrong".
  The tests then cover what exhaustiveness cannot — a case added by copying the line above
  it, which compiles fine and tells the user the wrong cause.
- `ImportCoordinator.refusalNow()` was added so the ViewModel asks the coordinator why an
  import would be refused instead of re-deriving it from `RecordingState`. It claims
  nothing; `begin` still decides, and still asks again.
- **`library_empty_action` was left as "Record something".** The plan called for revising
  it, but it now labels the *primary* of two buttons, with "Import a file" beside it — it
  names that button correctly, and a generic verb covering both would name neither.
  `library_empty_title` was revised instead ("Nothing recorded yet" was the assertion),
  along with `library_empty_body`.
- Tests assert distinctness and non-zero ids, not wording. These are plain JVM tests with no
  Robolectric in the project, so there is no resource table to read text from; the wording
  lives in `strings.xml` and is the translator's to change.

**Verify:** `check.sh` OK (Lint's hardcoded-string and missing-translation checks included),
`test-fast.sh` OK, `ImportMessagesTest` 5/5. Falsified: pointing `DecodeFailed` at
`import_failed_no_audio` fails `no two failures share a string` and nothing else, which is
the mistake the test exists to catch.

### Task 10 — Verification pass — **done**
**Change:** no code. Run the gates, then a real-device pass.
**Verify:**
- `.claude/scripts/check.sh` — OK, on the tree committed as Task 9.
- `.claude/scripts/test-fast.sh` — OK, same tree. The import unit tests are
  `ImportPreflightTest`, `ImportCoordinatorTest`, `ImportTitleTest` and `ImportMessagesTest`;
  none of them decodes anything, so **nothing below is proven by them**.
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
  - Start a **recording during an import** — the direction the coordinator deliberately
    does not guard (Task 4). Confirm the capture is clean: no dropped audio, no gap in the
    WAV, waveform still live.
  - Kill the app mid-import; confirm no partial WAV is adopted by `RecordingRecovery` as a
    Session (the partial lives in `cacheDir`, which is never scanned).
  - Re-open the app after a completed import and confirm exactly one Session exists.

Record the result in this file the way slice-09 and UI-042 recorded theirs.

**Device pass — run 2026-09-10.** Nothing Phone (2), Android 16, arm64, 7270 MB
(`device_capability belowMinimum=false`), `com.harken.android.debug` 0.1.0-debug (242),
`gitSha=231cee1` — the tree committed as Task 9. Driven over adb: `input tap` against
`uiautomator dump` bounds, `screencap` for the copy, `logcat` for telemetry, `run-as` for
`filesDir`, `cacheDir` and the database.

- **m4a via the picker.** No true audio-only m4a exists on the phone — the stock recorder
  keeps its files app-private — so the fixture was an `.m4a`-named copy of an AAC track:
  imported in 484 ms, Session titled `harken-aac` from the filename, plays, and
  transcribes when tapped.
- **Share sheet, Opus voice note.** `import_shared_in mimeType=audio/ogg` then
  `import_finished outcome=Imported sourceBytes=24296 elapsedMs=417`; Session
  `harken-voice-note`, 10 s. This is the path the share target exists for and it is the
  one that found the bug in Task 11.
- **Audio from an mp4.** Both a short clip (`VID-20240108-WA0009`, 7 s) and a 53-minute
  one (`091 Final Office Hours - Morning`, 3225 s, a 103,204,384-byte WAV).
- **Cancel from the Live Update notification.** `import_finished outcome=Cancelled`, no
  session row, `cacheDir` cleared, nothing left in `filesDir`.
- **Large import.** A 5.24-hour WAV raised "That's a long one … about 576 MB"; **Not now**
  deleted the 604 MB staged copy. Accepting it instead produced an 18,874 s Session, so
  the number on the dialog is the one the import actually costs.
- **Refusals.** A second import while one runs: "Not right now / One import at a time."
  Refusal *while recording* is unreachable from inside the app — the import button is
  hidden while the microphone is open — so the only route is a share arriving mid-capture:
  sharing an Opus from the Files app during a recording gave "Not right now / Finish the
  recording first — importing while the microphone is open would compete with it.", with
  no staging attempted and the capture running on untouched.
- **Recording started during an import** (the direction Task 4 deliberately does not
  guard): `recording_stopped chunks=283 bytes=1446400 maxChunkWriteMs=1 slowChunks=0` —
  no slow chunk, no gap — while the import finished normally at `elapsedMs=109193`. The
  known gap costs nothing measurable on this phone.
- **Killed mid-import.** No Session, no partial in `filesDir`; the `*.partial.wav` stayed
  in `cacheDir`, which recovery never scans.
- **Re-open after a completed import.** The Library holds exactly the imports that
  finished, and no others.
- **Unreadable stream** (a Uri whose grant had lapsed): "Import failed / That file could
  not be read."

**One bug found, fixed in Task 11:** a share arriving while the microphone is open starts
a second `MainActivity`, whose launch-time `RecordingRecovery` adopted the *live*
recording's WAV. The audio survived intact (header 248.42 s, matching the bytes), but the
Library got a Session dated to the share and 30 s short, and the recorder's own save then
failed — `recording_save_failed error=UNIQUE_constraint_failed:_sessions.id`.

**Two follow-ups, ticketed here and since fixed on this branch:** `cacheDir` was never
swept at startup, so a killed import left its staged source and `*.partial.wav` there
until Android reclaimed them (ARC-055 — now swept on launch, and skipped while an import
holds the coordinator slot, the same guard `RecordingRecovery` uses); and a `transcribing`
notification (id 1002) could still be posted after transcription had finished (ARC-056 — a
late progress callback re-posting it after the service stopped, closed with a lock rather
than a flag, since a flag only narrows the window). Neither has had an on-device pass;
both tickets say so.

### Task 11 — Recovery must not adopt the recording in progress — **done**
**Files:** `.../kotlin/com/harken/android/recording/RecordingRecovery.kt`,
`.../test/kotlin/com/harken/android/recording/RecordingRecoveryTest.kt`.
**Why:** found by Task 10's device pass. `RecordingRecovery` runs on every launch and
identifies an orphan as "a WAV in `filesDir` whose id has no session row". A capture in
progress is exactly that — the row is only written when the recording stops (ARC-016) — so
until now the only thing keeping recovery off it was that nothing started a fresh
`MainActivity` mid-recording. The share target does: an `ACTION_SEND` from another app
lands in its own task and runs the launch path again.
**Change:** `orphanRecordings` takes the in-progress recording id and never returns it;
`RecordingRecovery` reads it from `RecordingState.recordingId` through a default-argument
seam, the same shape as `ImportCoordinator.isRecording`. Not a `RecordingState.isRecording`
check: the question is which *file* is being written, and excluding one id keeps recovery
doing its job for every other orphan on disk while a capture runs.

**Verify:** `check.sh` OK, `test-fast.sh` OK, `RecordingRecoveryTest` 6/6. Falsified:
dropping the `id == inProgressId` clause fails `the recording being written right now is
not adopted` and nothing else.

**On-device, same phone, build 244 `gitSha=8cd53ea`:** shared the same Opus into Harken
12 seconds into a capture. No `RecordingRecovery: Recovered` line, no import, the refusal
dialog instead, and the waveform still live behind it. Stopping the capture gave
`recording_stopped session=c436164c elapsedMs=82031 bytes=2621440` with no
`recording_save_failed`, and the row the recorder wrote reads 81 s between its own
`startedAt` and `endedAt` — the arithmetic the adopted row got wrong.

The pass also took three tries to stage, which is worth knowing for the next one: the
capture auto-stops after 5 minutes of silence (`reason=SilenceTimeout`), and in a quiet
room that expires while adb is still walking the file picker. Driving the whole
record-switch-share sequence in one shell pass is what made it land.

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
