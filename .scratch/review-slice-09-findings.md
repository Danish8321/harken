# Code review findings — `feat/on-device-transcription` (slice-09)

Opened 2026-08-28. Two-axis review of `git diff master...HEAD` (fixed point `master` =
2f8c21a, 16 commits, 32 non-vendored files). Vendored `src/Harken.Android/app/src/main/cpp/whisper`
excluded — third-party, not ours to review.

Status: triaged and closed out on 2026-08-30 (slice 11). See **Disposition** at the
bottom for what was fixed, what died with the architecture, and what is still open.

---

## Standards axis

### Hard violations (documented repo standards)

#### S1. `README.md` stale, now contradicts the branch's own architecture
Still states "ADR-0007 keeps all transcription on the backend, so the phone never runs a
model itself" and "First launch runs a 3-step onboarding wizard: enter the backend base URL
… before it saves". Branch makes on-device the default and onboarding 4 steps,
backend-optional (ADR-0011 §3). README is a root standards source; a slice that inverts its
stated architecture must update it in the same slice.

#### S2. Verification contract — no automated tests for new pure-JVM logic
Only new automated test on the branch is `SessionDatabaseMigrationTest.kt`, which is
instrumented and therefore excluded from `test-fast.sh`. `TranscriptionCoordinator`,
`ModelDownloadManager`, and `OnDeviceTranscriber` are all pure-JVM-testable and ship with
zero tests. CLAUDE.md's "tests at every tier crossed" is not met.

#### S3. Model download has no integrity check
`ModelDownloadManager.MODEL_DOWNLOAD_URL` is fetched with no checksum and no size
validation. A truncated 200 response is renamed straight to `ggml-base.en.bin` and is then
indistinguishable from a good model — a plausible contributor to native crashes.

### Judgement calls

#### S4. DRY / Data Clump — duplicated download-collection in two ViewModels
`OnboardingViewModel.downloadModel()` and `SettingsViewModel.updateModel()` are the same
~20-line `catch`/`onCompletion`/`collect` block verbatim, with the same
`MutableStateFlow(… if (isModelPresent()) Ready else NotStarted)` init and the same three
`modelDownloadState` / `modelDownloadProgress` / `modelDownloadError` fields travelling
together. Extract one `ModelDownloadUiState` plus a shared `collectDownload()`.

#### S5. Duplicated Code inside `ModelDownloadManager`
`ensureModel()` and `downloadProgress()` both repeat mkdirs → `.tmp` → `downloadTo` →
`renameTo` → delete-on-failure. `ensureModel()` should be `downloadProgress().collect {}`
plus the path, or both should call one private `download()`.

#### S6. YAGNI — provider switching is dead code
`AppSettings.setTranscriptionProvider` has zero callers. `AzureBatch` is therefore
unreachable, making `CaptureViewModel`'s `if (cachedProvider == WhisperLocal)` branch and
the whole `uploadToBackend` path dead in practice. Either wire the picker or drop the enum
for this slice. See also SP1 — this is the same defect seen from the spec side.

#### S7. Primitive Obsession / Shotgun Surgery — `transcriptionStatus` as raw String
Compared against string literals in at least four places: `SessionDao` SQL
(`'Running'`/`'Succeeded'`/`'Failed'`), `LibraryScreen` (`session.status == "Recorded"`),
`LibraryViewModel.subtitle`, and `SessionSheet`. Adding "Recorded" in this branch required
edits in all of them. A `TranscriptionStatus` enum with one mapping point would localize it.

#### S8. KISS — `downloadProgress()` uses `callbackFlow` for no reason
Wraps a blocking `withContext(Dispatchers.IO)` inside `callbackFlow` and ends with an
unreachable `awaitClose {}` after `close()`. A plain `flow { }` with `flowOn(IO)` expresses
the same thing without the channel machinery. See SP7 — this also has a correctness
consequence.

#### S9. Feature Envy — `TranscriptionCoordinator` takes its collaborators per call
`transcribe(repository, modelDownloadManager, onDeviceTranscriber, …)` is a singleton
receiving dependencies on every call, and `LibraryViewModel.transcribe(session)` reaches
into `session.pendingUploadPath` to feed it. That's the shape of a class that should hold
its dependencies.

#### S10. Mysterious Name — `SessionRow.pendingUploadPath`
Now stores the audio path for local-only sessions that will *never* be uploaded; the name
asserts the opposite. `recordingPath` (with upload-pending derived from status) is honest.

---

## Spec axis

Spec sources: `docs/plans/slice-09-on-device-transcription.md`, `docs/adr/0011-on-device-transcription.md`.

### Missing / partial

#### SP1. Provider picker never built; Azure became unselectable
ADR-0011 decision 5: "Azure Batch Transcription is unaffected: still backend-mediated, still
requires a configured `baseUrl` to be selectable at all (the provider picker already degrades
unavailable choices)." No provider picker exists in the Android app.
`AppSettings.transcriptionProvider` is written by nothing, so `cachedProvider` is permanently
`WhisperLocal`. Azure transcription is not "unaffected" — it is unreachable.

#### SP2. Plan and ADR text now contradict the shipped code
Plan Task 4 says "run `OnDeviceTranscriber` against the recording file, then
`completeLocalTranscription(...)`", and Task 6's manual gate says "confirm the app reaches the
main screen and a recording can be made and transcribed". After `6300f16` nothing transcribes
automatically — it's an explicit Library action — and neither doc was updated. Two more live
contradictions:
- Plan Task 6: "No new step for model download — that stays lazy (first recording triggers it
  per Task 3)" vs. the shipped 4-step onboarding with an explicit download step.
- ADR §Decision 3 orders "a one-time, skippable model-download step, then an optional connect
  step" vs. code where connect is step 1 and model download is step 4.

Follow-up #1 in `slice-09-followups.md` marks the onboarding step "done" but doesn't record
that the plan/ADR text is now wrong.

#### SP3. Onboarding step-3 copy asserts removed behaviour
Still reads "Recording transcribes right there on your phone the moment you stop" — false
since `6300f16`.

### Scope creep

#### SP4. Slice-10 artifacts landed in the slice-09 branch
`docs/adr/0013-organic-design-system-adoption.md`, `docs/plans/slice-10-organic-design-system.md`,
and `docs/design/claude-design-modernization/` (~2,900 lines including two `.dc.html` mocks and
`organic-styles.css`). Slice-10's own plan says its branch starts "only after
`feat/on-device-transcription` (slice-09) merges" — its artifacts shouldn't be inside slice-09.

#### SP5. Unrequested removals and additions
- `SessionRepository.softDelete` deleted — no caller, but no task asked for it.
- `LibraryScreen`'s new "Recorded" status + Transcribe button and `SessionSheet`'s
  `canPlayAudio` gating are reasonable but not in the plan. Note ADR-0011 decision 2 promises
  "Library, playback, and reading the transcript all work with no backend configured" — which
  playback now does not.

### Implemented but wrong

#### SP6. `durationSeconds` computed from the wrong value — user-visible
`TranscriptionCoordinator`: `durationSeconds = segments.maxOfOrNull { it.offsetSeconds }`.
That's the *start offset of the last segment*, not the recording length. Drives the Library
duration bar and the player's "of MM:SS". Worst finding on this axis.

#### SP7. Mid-download cancellation cannot work
`ModelDownloadManager.downloadProgress()` runs a blocking `withContext(Dispatchers.IO)` inside
`callbackFlow` before `awaitClose`, so collector cancellation cannot abort an in-flight
download. The "kill app mid-download" manual check in `slice-09-followups.md` item 3 is
likely to fail. Same root shape as S8.

#### SP8. `OnDeviceTranscriber.release()` never called
The native model handle is held for process lifetime. Relevant to
[bug-ggml-sigsegv-vec-dot-f16.md](bug-ggml-sigsegv-vec-dot-f16.md).

---

## Summary

Standards: 10 findings (3 hard, 7 judgement). Worst — S1, README contradicting the branch's
own architecture.

Spec: 8 findings. Worst — SP6, wrong duration shipping visibly incorrect values to users.

Not yet triaged into merge-blockers vs. follow-ups.


---

## Disposition (2026-08-30, slice 11)

This review was written against a branch that never merged. `master` moved on: the
backend stopped being a product surface (ADR-0014), the device floor rose (ADR-0015),
and slice 11 ran a truth pass over the repo. Every finding is settled below, so this file
is history rather than a queue.

### Fixed

| | Where |
| --- | --- |
| S1 README contradicts the architecture | Rewritten for the two Modes, slice 11 |
| S2 no automated tests for new JVM logic | `TranscriptionCoordinatorTest` (4), `ModelDownloadManagerTest` (5) |
| S3 model download has no integrity check | Bytes are compared against the declared length; a response with no length is refused |
| SP6 `durationSeconds` from the wrong value | Derived from the WAV byte length on `master` |
| SP8 `release()` never called | Called in `transcribe`'s `finally` on `master` |

### Moot — the thing they were about no longer exists

- **S6, SP1** (provider switching, Azure unreachable): there is no provider setting and no
  Azure provider. Which engine runs follows from the Mode (ADR-0014).
- **SP2, SP3** (plan/ADR text vs. shipped onboarding): onboarding is two steps and has no
  connect-a-backend step at all. ADR-0011's status now records that decision 3 is
  superseded.
- **SP4, SP5** (slice-10 artifacts in the branch, unrequested removals): the branch is
  being deleted rather than merged; its two useful docs commits were cherry-picked.

### Still open, deliberately

Refactors, not truths — none of them makes the app lie to a user, so none was in slice
11's scope. They are real debt and stay recorded here:

- **S5 / S8 / SP7** — `ensureModel()` and `downloadProgress()` duplicate the whole
  mkdirs / `.tmp` / rename / cleanup dance, `downloadProgress()` wraps a blocking
  `withContext` in a `callbackFlow`, and because of that a collector cancelling cannot
  abort an in-flight download. One `flow { }` with `flowOn(IO)`, called by both, closes
  all three.
- **S4** — `OnboardingViewModel` and `SettingsViewModel` still hold the same three
  download fields and the same collect block, verbatim.
- **S7** — `transcriptionStatus` is still a raw `String` compared against literals in the
  DAO, `LibraryScreen`, `LibraryViewModel` and `SessionSheet`.
- **S9** — `TranscriptionCoordinator` still takes its collaborators on every call.
- **S10** — `SessionRow.pendingUploadPath` still names an upload that never happens. It
  is a Room column, so the rename is a migration, not an edit.
