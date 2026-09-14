# Code review findings — `feat/on-device-transcription` (slice-09)

Opened 2026-08-28. Two-axis review of `git diff master...HEAD` (fixed point `master` =
2f8c21a, 16 commits, 32 non-vendored files). Vendored `src/Harken.Android/app/src/main/cpp/whisper`
excluded — third-party, not ours to review.

Status: re-audited 2026-09-13 against master at `2e204ce`. Of the 18 findings, 15 are fixed,
1 is moot, 1 is closed by decision (SP5 — no soft delete), and 1 remains open (S9) — S8 and
SP7 were fixed on 2026-09-13 as ARC-063,
S7 the same day as ARC-064, SP1/SP2 on 2026-09-14 by correcting the documents themselves, S4
the same day as ARC-065, S5 as ARC-066 and S2 as ARC-067, and S10 was found on 2026-09-14 to
have been fixed by ARC-015 before this re-audit was written.
The verdicts are below; the original text of each finding is kept underneath, unedited, so
the two can be read against each other.

## Re-audit, 2026-09-13

| | Verdict | What changed, or what is left |
|---|---|---|
| S1 | fixed | `README.md:3-4,60-63` now describes on-device transcription and the 2-step onboarding. |
| S2 | fixed | The transcriber's non-JNI logic moved to `WavPayload`, `Telemetry.realtimeFactor` and `NativeSegments`, and is tested (ARC-067). |
| S3 | fixed | SHA-256 verification plus a content-length truncation check — `ModelDownloadManager.kt:222-251,397-401`. |
| S4 | fixed | `ui/ModelDownload.kt` holds one `ModelDownloadUi` and the fold both ViewModels now delegate to (ARC-065). |
| S5 | fixed | `installIfMissing(replaceExisting, onProgress)` is now the one locked path both entry points call (ARC-066). |
| S6 | fixed | `AzureBatch` and `setTranscriptionProvider` are gone from the source entirely. |
| S7 | fixed | `data/TranscriptionStatus.kt` is now the vocabulary; the DAO binds it and the UI holds the type (ARC-064). |
| S8 | fixed | Same defect as SP7, fixed with it as ARC-063. |
| S9 | open | `TranscriptionCoordinator.transcribe(...)` still takes its three collaborators per call (`TranscriptionCoordinator.kt:67-75`). |
| S10 | fixed | ARC-015's `MIGRATION_2_3` renamed the column to `audioPath`; `pendingUploadPath` survives only inside that migration. |
| SP1 | fixed | ADR-0011's Status and its new "What actually shipped" section record that no provider picker exists and Azure is unreachable. |
| SP2 | fixed | Same section covers decisions 3–5 and the explicit-transcribe change; the slice-09 plan carries a header note naming the four divergences. |
| SP3 | fixed | No automatic-transcription copy survives in `strings.xml`. |
| SP4 | moot | Branch-hygiene complaint about work merged long ago. |
| SP5 | closed | Playback sub-point fixed — `SessionSheetViewModel.kt:158` gates on local file existence only. `softDelete` stays absent by decision (2026-09-14); the scope complaint itself is history. |
| SP6 | fixed | `TranscriptionCoordinator.kt:178` reads the real WAV length via `WavFormat.durationSeconds`, not segment offsets. |
| SP7 | fixed | Confirmed by reading the code, then fixed as ARC-063 — see below. |
| SP8 | fixed | `onDeviceTranscriber.release()` now runs in the `finally` of every attempt (`TranscriptionCoordinator.kt:152`). |

### S8 / SP7 were one defect, and it was the sharpest one left — fixed as ARC-063

`ModelDownloadManager.downloadProgress()` (`ModelDownloadManager.kt:282-309`) runs the entire
transfer inside `withContext(Dispatchers.IO)` within the `callbackFlow` block, then calls
`close()` at :307, leaving `awaitClose {}` at :308 unreachable. The transfer itself is a
blocking `input.read()` loop (`streamTo`, :384) with no cancellation check, and the OkHttp
call is never cancelled. A blocking read is not a suspension point, so cancelling the
collector does not stop the download — it runs to completion regardless, and the user has no
way to abort 148 MB once it starts. Partially softened by resume support (the `Range` header
at :355), which at least means an abandoned transfer is not repaid in full next time.

Fixed 2026-09-13 — `.scratch/issues/ARC-063-a-model-download-cannot-be-cancelled.md`.
`callbackFlow` became `flow { … }.flowOn(Dispatchers.IO)`, the read loop checks
`ensureActive()` between chunks, the OkHttp call is cancelled with the coroutine, and a
cancelled transfer reports `outcome=cancelled` rather than `failed`. Confirmed on the
emulator: Back during a Settings update froze the partial at 96,107,838 of 147,964,211 bytes,
and the next attempt resumed from there with a 206.

### S7: part of the status vocabulary is unreachable — fixed 2026-09-13

`"Pending"` is compared against in `LibraryViewModel.kt:50,215` and `LibraryScreen.kt:158,675`
and is **written nowhere in the codebase**. The statuses anything actually writes are
`"Recorded"` (`SessionRepository.kt:145`) and, from SQL, `'Running'` / `'Succeeded'` /
`'Failed'` (`SessionDao.kt:80,86,114,135`). So a guard on a destructive action — the
multi-select delete added in `2e204ce` — is half-written against a state the app cannot
produce, and nothing about reading the code says so. That is the concrete cost S7 predicted:
the vocabulary drifted and no single place defines it.

**Fixed 2026-09-13 as ARC-064.** `data/TranscriptionStatus.kt` is now the vocabulary, each
member carrying the string it persists as; the DAO binds those values instead of writing SQL
literals, and `SessionRepository.toView` maps the column through `TranscriptionStatus.of`, so
everything above the repository holds the type. An unrecognised column value — `"Pending"`
included, and the null the column still permits — reads as `Recorded`, which offers
Transcribe; `SessionCard`'s `else -> Transcribed`, which claimed finished work about a row it
did not recognise, is gone with the `when` now exhaustive.

The grill on this finding turned up a second defect it did not name: `isSelectable` asked only
the row, while `SessionCard` ORed in `transcribingSessionId`, so during the coroutine hop
between the Transcribe tap and Room's write a recording being decoded could be long-pressed
and deleted. `LibraryUiState.statusOf` is now the one answer to "is this transcribing?", and
two tests in `LibrarySelectionTest` hold it.

---

## Standards axis

### Hard violations (documented repo standards)

#### S1. `README.md` stale, now contradicts the branch's own architecture
Still states "ADR-0007 keeps all transcription on the backend, so the phone never runs a
model itself" and "First launch runs a 3-step onboarding wizard: enter the backend base URL
… before it saves". Branch makes on-device the default and onboarding 4 steps,
backend-optional (ADR-0011 §3). README is a root standards source; a slice that inverts its
stated architecture must update it in the same slice.

#### S2. Verification contract — no automated tests for new pure-JVM logic — fixed 2026-09-14
Only new automated test on the branch is `SessionDatabaseMigrationTest.kt`, which is
instrumented and therefore excluded from `test-fast.sh`. `TranscriptionCoordinator`,
`ModelDownloadManager`, and `OnDeviceTranscriber` are all pure-JVM-testable and ship with
zero tests. CLAUDE.md's "tests at every tier crossed" is not met.

**Fixed 2026-09-14 as ARC-067**, the last of the three. `TranscriptionCoordinator` was
covered by ARC-063 and `ModelDownloadManager` by ARC-063 and ARC-066.

The finding's premise about the third was wrong, and worth recording. `OnDeviceTranscriber`
was **not** pure-JVM-testable: its companion object's `System.loadLibrary` compiles into the
outer class's static initialiser, so even constructing it throws `UnsatisfiedLinkError` on
the runner, and every helper it held was behind that. Separately, `org.json` in the unit-test
`android.jar` is a stub that `isReturnDefaultValues = true` makes return `0` instead of
throwing, so a test of `parseNativeSegments` written in place would have passed while parsing
nothing. Both were probed before anything was changed.

So the code moved to where it could be reached: `audio/WavPayload.kt` (the WAV payload
reader), `Telemetry.realtimeFactor`, and `speech/NativeSegments.kt` (the wire decode and the
span-offset arithmetic), plus `org.json:json` as a test-only dependency. 16 new tests, four
mutation checks. The class keeps the native calls, the abort wiring, the span loop and the
telemetry events — none of which any JVM test can reach, which ARC-067 states rather than
leaves implied.

#### S3. Model download has no integrity check
`ModelDownloadManager.MODEL_DOWNLOAD_URL` is fetched with no checksum and no size
validation. A truncated 200 response is renamed straight to `ggml-base.en.bin` and is then
indistinguishable from a good model — a plausible contributor to native crashes.

### Judgement calls

#### S4. DRY / Data Clump — duplicated download-collection in two ViewModels — fixed 2026-09-14
`OnboardingViewModel.downloadModel()` and `SettingsViewModel.updateModel()` are the same
~20-line `catch`/`onCompletion`/`collect` block verbatim, with the same
`MutableStateFlow(… if (isModelPresent()) Ready else NotStarted)` init and the same three
`modelDownloadState` / `modelDownloadProgress` / `modelDownloadError` fields travelling
together. Extract one `ModelDownloadUiState` plus a shared `collectDownload()`.

**Fixed 2026-09-14 as ARC-065.** `ui/ModelDownload.kt` now holds `ModelDownloadUi` (the four
fields as one value), `of(present)` as the single init rule, and
`Flow<Int>.asDownloadUi(start, presentOnFailure)` — the fold both ViewModels delegate to,
each now one `launch` and one `collect`. Written against `Flow<Int>` rather than
`ModelDownloadManager` so it is reachable from a JVM test: `ModelDownloadTest` is nine tests
where there were none, two of them mutation-checked. `ModelDownloadState` moved out of
`OnboardingScreen.kt` in the same change.

#### S5. Duplicated Code inside `ModelDownloadManager` — fixed 2026-09-14
`ensureModel()` and `downloadProgress()` both repeat mkdirs → `.tmp` → `downloadTo` →
`renameTo` → delete-on-failure. `ensureModel()` should be `downloadProgress().collect {}`
plus the path, or both should call one private `download()`.

**Fixed 2026-09-14 as ARC-066**, by the second of those two. `installIfMissing(replaceExisting,
onProgress)` holds the locked section — re-check, `mkdirs`, `downloadTo`, `installPartial` —
and is deliberately silent and dispatcher-free, so each entry point keeps its own result shape,
its own log line and its own cancellation handling. `ensureModel` was not rewritten to run the
flow: that would hand a caller wanting a path and a `Result` a Flow it does not use, and would
fold together the two cancellation stories ARC-063 kept apart. Two new tests pin
`replaceExisting`, which nothing had exercised.

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

#### S10. Mysterious Name — `SessionRow.pendingUploadPath` — fixed by ARC-015
Now stores the audio path for local-only sessions that will *never* be uploaded; the name
asserts the opposite. `recordingPath` (with upload-pending derived from status) is honest.

**Already fixed when the 2026-09-13 re-audit was written; the verdict above was wrong and was
corrected on 2026-09-14.** ARC-015's `MIGRATION_2_3` (`SessionDao.kt:246-283`) rebuilt the
table and renamed the column to `audioPath` — the rebuild *is* the rename, because minSdk 26
ships SQLite 3.18, which has neither `RENAME COLUMN` nor `DROP COLUMN`. In
`src/Harken.Android/app/src/main`, `pendingUploadPath` now occurs only at `SessionDao.kt:232`
and `:239` (the migration's own KDoc) and `:276` (the `SELECT` that reads the old column into
the new one), which is exactly where the old name must survive: a migration names the schema
it is migrating *from*. `SessionDao.kt:262`, which the re-audit cited as the offender, is
`` `audioPath` TEXT `` in the new table's DDL. The remaining occurrences are in
`androidTest/…/SessionDatabaseMigrationTest.kt`, which asserts the rename carries the value
across — also the old name used correctly.

---

## Spec axis

Spec sources: `docs/plans/slice-09-on-device-transcription.md`, `docs/adr/0011-on-device-transcription.md`.

### Missing / partial

#### SP1. Provider picker never built; Azure became unselectable — fixed 2026-09-14
ADR-0011 decision 5: "Azure Batch Transcription is unaffected: still backend-mediated, still
requires a configured `baseUrl` to be selectable at all (the provider picker already degrades
unavailable choices)." No provider picker exists in the Android app.
`AppSettings.transcriptionProvider` is written by nothing, so `cachedProvider` is permanently
`WhisperLocal`. Azure transcription is not "unaffected" — it is unreachable.

**Fixed 2026-09-14, in the document rather than the code.** There is nothing to build here:
`AppSettings` survives but holds no backend fields, and a grep of
`src/Harken.Android/app/src/main` finds no occurrence of `transcriptionProvider`, `Azure`,
`baseUrl` or `Summarize`. ADR-0011 now says
so, under "What actually shipped" — decision 5's own text is left as written, because it was
overtaken rather than reversed. The three dead links to `0010-azure-batch-transcription-provider.md`,
an ADR that has never existed in this repository's history, were removed at the same time;
the citations stay, now marked as never written.

#### SP2. Plan and ADR text now contradict the shipped code — fixed 2026-09-14
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

**Fixed 2026-09-14.** ADR-0011's "What actually shipped" section covers decisions 3, 4 and 5
plus the explicit-transcribe change that no decision ever spoke to. The slice-09 plan keeps
every task as written — ADR-0015 already ruled that `docs/plans/slice-*.md` stay — and gains
a header note naming four divergences: transcription is an explicit Library action, no
Summarize action exists, onboarding has an explicit model-download step, and `isLocalOnly`
was dropped again by ARC-015's `MIGRATION_2_3`.

#### SP3. Onboarding step-3 copy asserts removed behaviour
Still reads "Recording transcribes right there on your phone the moment you stop" — false
since `6300f16`.

### Scope creep

#### SP4. Slice-10 artifacts landed in the slice-09 branch
`docs/adr/0013-organic-design-system-adoption.md`, `docs/plans/slice-10-organic-design-system.md`,
and `docs/design/claude-design-modernization/` (~2,900 lines including two `.dc.html` mocks and
`organic-styles.css`). Slice-10's own plan says its branch starts "only after
`feat/on-device-transcription` (slice-09) merges" — its artifacts shouldn't be inside slice-09.

#### SP5. Unrequested removals and additions — closed 2026-09-14
- `SessionRepository.softDelete` deleted — no caller, but no task asked for it.
- `LibraryScreen`'s new "Recorded" status + Transcribe button and `SessionSheet`'s
  `canPlayAudio` gating are reasonable but not in the plan. Note ADR-0011 decision 2 promises
  "Library, playback, and reading the transcript all work with no backend configured" — which
  playback now does not.

**Closed 2026-09-14: no soft delete, by decision.** Delete stays a real delete — the row and
the WAV both go, as `SessionRepository.purge` already does, and as the multi-select delete
shipped against. Restoring `softDelete` would put an undo path back with no screen asking for
one. The playback sub-point was fixed separately; the "unrequested" complaint is about a merge
that happened, so nothing else here is actionable.

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

*Superseded by the re-audit at the top of this file — kept for the record.*

Standards: 10 findings (3 hard, 7 judgement). Worst — S1, README contradicting the branch's
own architecture.

Spec: 8 findings. Worst — SP6, wrong duration shipping visibly incorrect values to users.

Not yet triaged into merge-blockers vs. follow-ups.

## What is actually left, ranked (2026-09-13)

1. ~~**S8 / SP7** — an in-flight model download cannot be cancelled. The only one with a
   user-facing consequence.~~ Fixed as ARC-063 on 2026-09-13.
2. ~~**S7** — status as a raw string, with a dead `"Pending"` branch now sitting inside a
   delete guard.~~ Fixed as ARC-064 on 2026-09-13, along with the delete guard's own defect.
3. ~~**SP1, SP2** — ADR-0011 and the slice-09 plan describe a system that no longer exists
   (a provider picker, a connect step, a lazy download).~~ Fixed as documents on 2026-09-14:
   ADR-0011 carries a "What actually shipped" section, the plan a header note.
4. **S9** — maintainability: `TranscriptionCoordinator.transcribe` takes its three
   collaborators per call. **All that is left**, and it has no user-facing consequence. Four
   left this list on 2026-09-14: S4 as ARC-065, S5 as ARC-066, S2 as ARC-067, and S10, which
   turned out to have been fixed by ARC-015 before the re-audit was written.
5. ~~**SP4, SP5** — moot, or history.~~ SP5 closed 2026-09-14: no soft delete, by decision.
   SP4 was moot from the start.
