# Plan — Slice 06: On-device recording (Android)

**Goal:** The phone becomes a capture device. Record to a local WAV file via the
foreground service, stop (manually, on silence, or at the cap), upload, poll, and read
the transcript back — the console client's flow, on Android.

**Driver:** [ADR-0007](../adr/0007-record-then-transcribe.md) (record then transcribe),
[ADR-0003](../adr/0003-mobile-foreground-service.md) (foreground service, and the
notification as the only lock-screen surface). Unblocked by
[slice-05](slice-05-remove-auth.md): no login, so capture and upload have no credential
path to worry about.

**Reuses, does not rebuild:** `RecordingForegroundService` and `AndroidAudioCapture`
have been parked and unreferenced since slice-02 (see that plan's superseded note).
They get wired back in here.

**Decisions locked (grilled before this plan):**
- **WAV, not Opus.** ~115 MB/hour on device against ~10 MB/hour. Accepted: Whisper wants
  WAV natively, the backend already only accepts WAV, and an encoder is a dependency and
  a failure mode this slice does not need. Revisit when device storage actually hurts.
- **Silence Timeout: 5 minutes.** Amplitude threshold over consecutive chunks.
- **Session Cap: 3 hours.**
- **Both auto-stop *and upload*** — not just stop. A forgotten recording should end up on
  the backend, not sitting on the device waiting to be noticed.

**Not in scope:** iOS, Opus/compression, background upload queue surviving app kill,
live captions (deleted in ADR-0007, not returning here), re-transcription.

---

## Task 1 — WAV file writer
- **Files:** `src/Harken.Core/Audio/WavWriter.cs` (new),
  `tests/Harken.Core.UnitTests/WavWriterTests.cs` (new).
- **Change:** Wrap a `Stream` and write a 44-byte RIFF/WAVE header for 16 kHz/16-bit/mono,
  append PCM chunks, and patch the two length fields on close. Pure — no MAUI, no Android
  types, so it is unit-testable off-device. This is the one piece of this slice that can
  be proven without a phone, so it is worth having as real code rather than inline in the
  service.
- **Verify:** `test-fast.sh` — write known chunks, assert header bytes (RIFF/WAVE/fmt
  sizes, sample rate, channels, bits) and that both length fields match the payload
  written. Note: `Harken.Core.UnitTests` currently contains **no test files** — this task
  puts the first ones in it.

## Task 2 — Capture to a file in the foreground service
- **Files:** `src/Harken.Mobile/Platforms/Android/RecordingForegroundService.cs`,
  `src/Harken.Mobile/Services/IRecordingService.cs`,
  `src/Harken.Mobile/Platforms/Android/AndroidRecordingService.cs`.
- **Change:** On start, open a `WavWriter` over a file in the app's private storage
  (`FileSystem.AppDataDirectory`, name derived from a client-generated recording id — see
  Task 7). Subscribe to `IAudioCapture.ChunkCaptured` and write each chunk. On destroy,
  stop capture, close the writer (patching the header), and expose the finished path.
  `IRecordingService` grows a way to report the current/last recording's path and elapsed
  time so the page can read it without touching Android types.
- **Verify:** manual on device — record ~10 seconds, pull the file
  (`adb pull`), confirm it opens in a normal audio player and the duration is right. A
  file that plays is the proof the header is correct in situ, not just in a unit test.

## Task 3 — Capture page: record/stop + permission
- **Files:** `src/Harken.Mobile/Pages/CapturePage.xaml(.cs)`.
- **Change:** Add Record/Stop controls and a recording state to the existing page (which
  today only lists and summarizes). Request `RECORD_AUDIO` at the point of first record,
  not at launch — refuse to start and explain if denied. Show elapsed time while running.
- **Verify:** manual on device — grant the prompt, record, stop, see elapsed time count
  and a file land. Deny the prompt on a fresh install and confirm it fails with a
  message rather than crashing or silently doing nothing.

## Task 4 — Upload, poll, show transcript
- **Files:** `src/Harken.Mobile/Pages/CapturePage.xaml(.cs)`.
- **Change:** On stop, `POST {baseUrl}/sessions` with the WAV as multipart (`audio` +
  `source=Microphone`), then poll `GET /sessions/{id}` until the status is terminal, and
  show the transcript — mirroring `Harken.Console/Program.cs`'s existing flow, including
  its 502-means-Ollama-is-down handling on summarize. On upload failure, keep the file and
  say where it is; do not delete a recording that never landed.
- **Verify:** manual end-to-end against the backend on the same LAN: record on the phone,
  see it upload, poll, and print a real transcript. **This is the task that makes the
  slice a vertical slice** — everything before it produces a file nobody consumes.

## Task 5 — Silence Timeout and Session Cap
- **Files:** `src/Harken.Core/Audio/SilenceDetector.cs` (new),
  `tests/Harken.Core.UnitTests/SilenceDetectorTests.cs` (new),
  `RecordingForegroundService.cs` (wire in).
- **Change:** Detector takes PCM chunks and reports whether the amplitude has stayed under
  a threshold for a configured span — pure, unit-testable, no timers of its own driving
  it. The service applies it: 5 minutes of silence, or 3 hours total, ends the recording
  **and triggers the upload** exactly as a manual stop would (ADR-0007 moved these limits
  from the server to the client; this is where they land).
- **Verify:** `test-fast.sh` — silence below threshold trips at the configured span, a
  loud chunk resets the run, and the cap fires independently of silence. Manual: a short
  build with the timeout temporarily lowered to ~15 s to confirm it actually auto-stops
  and uploads on a real device, then restore the real value.

## Task 6 — Stop control in the notification
- **Files:** `RecordingForegroundService.cs`,
  `Platforms/Android/AndroidManifest.xml` (broadcast receiver / pending intent).
- **Change:** Add a Stop action and elapsed time to the ongoing notification. Per ADR-0003
  this is the *only* surface visible or actionable with the screen locked, which is the
  whole scenario the foreground service exists for — a stop button only on an unlocked
  page does not satisfy it.
- **Verify:** manual — start recording, lock the screen, stop from the notification,
  confirm the recording finalizes and uploads.

## Task 7 — Idempotent upload (client recording id)
- **Files:** `src/Harken.Core/Contracts/` (upload contract), `src/Harken.Api/Program.cs`,
  `src/Harken.Api/Data/HarkenDbContext.cs` + migration, `src/Harken.Mobile` (send it),
  `tests/Harken.Api.IntegrationTests/UploadEndpointTests.cs`.
- **Change:** Client generates a recording id at record-start and sends it with the
  upload; the API stores it uniquely and returns the **existing** session for a repeat
  rather than creating a second one. Slice-04 carried this explicitly: *"Duplicate uploads
  after a retry are not handled. Harmless with one user on a LAN, necessary before the
  phone client, where a flaky connection makes retries routine."* Retry is routine on a
  phone, so this is the slice that owes it.
- **Verify:** `test-fast.sh` — same recording id uploaded twice yields one session and the
  same id back both times. Migration read before applying, per the schema rule.

## Task 8 — Docs
- **Files:** `README.md`, `docs/setup.md`,
  `docs/plans/slice-02-mobile-android.md` (extend its superseded note).
- **Change:** README's mobile section currently says on-device recording "lands in slice
  05" and describes a Sessions-only tab — update to the real flow. Document the WAV
  storage cost per hour on the device, the two auto-stop limits and their values, and the
  notification's stop control. Slice-02's note gets a line saying its parked capture code
  is now wired in here.
- **Verify:** fresh-eyes read; no doc still describes the Capture tab as list-only or
  defers recording to a future slice.

---

## Exit criteria
`check.sh` and `test-fast.sh` green. One recorded end-to-end run on a real Android
device: record with the screen locked mid-recording, stop from the notification, upload,
poll, and read a real transcript back — then summarize it. Auto-stop verified at least
once with a lowered timeout. Re-uploading the same recording produces no duplicate
session.

## Note on verification limits
Only Tasks 1, 5 and 7 can be proven by automated tests. Tasks 2, 3, 4, 6 are device-only
— per slice-02's own hard-won note, **do not let "it builds" stand in for "it works"**
on those. Slice-02 shipped seven tasks that had never run on a phone; that is the
mistake this slice is placed to avoid repeating, since it finally has a user-exercisable
path to run.

---

## Status
_(not started)_
