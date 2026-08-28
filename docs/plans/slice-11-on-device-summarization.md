# Slice 11: On-device summarization, provider choice, cloud fallback

> **STALE (2026-08-28):** slice-09's backend/cloud path was ripped out entirely after
> this plan was written (`.scratch/plan-remove-backend-android.md`) — see
> [ADR-0012's Update](../adr/0012-full-standalone-local-summarization.md#update-2026-08-28).
> Task 5's provider-choice/cloud-fallback UI and Task 7's cloud-vs-on-device manual
> matrix are void. Re-scope before starting: on-device summarization is now the only
> path, no picker, no fallback prompt needed.

Implements [ADR-0012](../adr/0012-full-standalone-local-summarization.md). Branch: TBD
off `master`, **blocked until `feat/on-device-transcription` (slice-09) merges** —
including slice-09's own follow-up of replacing the temporary Hugging Face
`MODEL_DOWNLOAD_URL` with a real `models-v1` GitHub Release asset (see
`.scratch/slice-09-followups.md` item 2). This slice's Llama model asset lands in that
same release.

Grilled 2026-08-28 (see ADR-0012 for the full decision record and rejected
alternatives). Key resolved questions carried into the tasks below:
- Runtime: llama.cpp (shares `ggml` core already vendored for whisper.cpp).
- Model: Llama 3.2 3B Instruct, Q4_K_M GGUF (~1.8GB), self-hosted.
- Naming: role-based (`OnDeviceSummarizer`, `ModelCatalog.SummarizationModel`), never
  model-named — a future model swap is a `ModelSpec` value change.
- Summarization is available for **every** session (not just local-only), with an
  explicit `SummarizationProviderChoice` setting and a confirm-prompt fallback from
  cloud to on-device on network failure.
- `summaries` Room table needs no migration — already shaped for this.

## Tasks

### Task 1 — Vendor llama.cpp + JNI bridge
**Files:**
`src/Harken.Android/app/src/main/cpp/llama/` (new — vendored llama.cpp source, sharing
the existing `ggml` submodule/copy under `cpp/whisper/ggml` rather than duplicating it —
check llama.cpp's upstream tree for how it expects `ggml` laid out and adapt CMake
accordingly, do not vendor a second copy of `ggml` if avoidable),
`src/Harken.Android/app/src/main/cpp/harken_llama_jni.cpp` (new — JNI bridge:
`nativeLoadModel(String path): Long`, `nativeSummarize(long handle, String transcript):
String` returning the plain summary text, `nativeFreeModel(long handle)` — mirrors
`harken_whisper_jni.cpp`'s shape).
**Change:** `CMakeLists.txt` adds the llama.cpp target; `app/build.gradle.kts` needs no
new ABI change (arm64-v8a already set).
**Verify:** `./gradlew.bat assembleDebug` succeeds. Same caveat as slice-09 Task 1: green
build doesn't prove correct summarization — flag explicitly, real check is Task 6.

### Task 2 — `ModelSpec` / `ModelCatalog` generalization of `ModelDownloadManager`
**Files:**
`src/Harken.Android/app/src/main/kotlin/com/harken/android/speech/ModelDownloadManager.kt`,
new `ModelSpec.kt` (or same file) with `ModelSpec(fileName, downloadUrl, sizeBytes,
sha256)` and `ModelCatalog` object holding `TranscriptionModel` and `SummarizationModel`
entries (role-named, not model-named).
**Change:** `ModelDownloadManager`'s methods (`isModelPresent`, `deleteModel`,
`ensureModel`, `downloadProgress`) take a `ModelSpec` parameter instead of the hardcoded
`ModelFileName`/`MODEL_DOWNLOAD_URL` constants. Add SHA-256 verification after download,
before the `.tmp` → final rename (mismatch = treat as failed, delete `.tmp`, propagate
error). Add HTTP Range resume: if `.tmp` exists on retry, send `Range:
bytes=<tmp.length()>-` and append rather than restart (remove the current
delete-`.tmp`-on-failure behavior for the resumable case; keep it for checksum failure).
**Verify:** `test-fast.sh` — unit tests for: SHA-256 mismatch rejects and cleans up;
Range header sent correctly when `.tmp` pre-exists; both `ModelCatalog` entries work
through the same parameterized methods.

### Task 3 — WorkManager foreground download job for the summarization model
**Files:** new `SummarizationModelDownloadWorker.kt` (or similar,
`androidx.work.CoroutineWorker`), `app/build.gradle.kts` (add
`androidx.work:work-runtime-ktx` dependency).
**Change:** an `ExpeditedWorkRequest` wrapping `ModelDownloadManager.ensureModel(
ModelCatalog.SummarizationModel)`, foreground notification showing progress % with a
cancel action, `NetworkType.UNMETERED` constraint by default. Whisper's existing
download path is untouched — this worker is additive, only used for the summarization
model given its size.
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds; manual check deferred to Task 6
(WorkManager survives backgrounding is not practically unit-testable).

### Task 4 — `Summarizer` seam + `OnDeviceSummarizer` implementation
**Files:** new `Summarizer.kt` (interface + `LocalSummary(text: String, generatedAt:
Instant)` data class — local-only concept, no server counterpart, mirrors
`LocalTranscribedSegment`'s existing doc-comment pattern), new `OnDeviceSummarizer.kt`
(JNI-backed impl, load-once/reuse-handle pattern mirroring `OnDeviceTranscriber`).
**Change:** `OnDeviceSummarizer.summarize(...)` calls
`OnDeviceTranscriber.release()` (force-release the whisper handle) before loading the
summarization model, per ADR-0012 §7.
**Verify:** `test-fast.sh` — unit test with a fake JNI boundary (or whatever seam pattern
`OnDeviceTranscriber`'s tests already use, if any exist) confirming
`release()`-before-load ordering and the `LocalSummary` shape.

### Task 5 — Provider choice, coordinator, Room write, Settings, UI wiring
**Files:**
`src/Harken.Android/app/src/main/kotlin/com/harken/android/data/AppSettings.kt` (add
`SummarizationProviderChoice { OnDevice, Cloud }` + `summarizationProvider` Flow +
`setSummarizationProvider`, parallel to `TranscriptionProviderChoice`),
new `SummarizeCoordinator.kt` (resolves provider choice → cloud call or
`OnDeviceSummarizer`; on cloud failure, signals the confirm-prompt state rather than
falling back silently; on-device with model absent signals the download-prompt state),
`src/Harken.Android/app/src/main/kotlin/com/harken/android/data/SessionRepository.kt`
(add `saveLocalSummary(sessionId, LocalSummary)` — writes `SummaryRow` and flips
`SessionRow.hasSummary = true` in one `@Transaction`, no schema change needed),
`src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/SessionSheetViewModel.kt`
(replace the `canSummarize = ... !session.isLocalOnly && cachedBackendConfigured` gate —
button now always visible; wire tap through `SummarizeCoordinator`; add dialog state for
the network-failure confirm prompt and the model-download prompt),
`SettingsScreen.kt`/`SettingsViewModel.kt` (new "Summarization" group: provider choice +
model download/re-download row, mirroring the existing Transcription group's shape).
**Verify:** `test-fast.sh` — `SummarizeCoordinator` unit tests: cloud success path, cloud
failure → confirm-prompt state (never silent fallback), on-device with model present,
on-device with model absent → download-prompt state. `SessionRepository` transaction
test confirms `hasSummary` flips atomically with the summary insert.

### Task 6 — Legal/About section + Llama license
**Files:** new "Legal"/"About" section in `SettingsScreen.kt` (doesn't exist today);
bundle Llama 3.2 Community License text + "Built with Llama" attribution, and
whisper.cpp's MIT notice alongside it (existing gap, fixed in the same commit per
ADR-0012).
**Verify:** manual — section renders, text matches the actual license files shipped.

### Task 7 — Manual on-device verification (gates merge)
Not unit-testable; same category as slice-09's outstanding manual checks. Record results
in `.scratch/slice-11-followups.md` if anything is deferred rather than blocking:
- llama.cpp JNI loads the Q4_K_M GGUF and produces non-garbage summaries on real
  transcripts (accuracy/quality is genuinely unproven going in, per ADR-0012).
- 1.8GB download: survives app backgrounding (WorkManager), resumes cleanly after a
  mid-download kill, a truncated/corrupted file is caught by the SHA-256 check and not
  mistaken for a real model.
- RAM: on-device summarize doesn't get killed by the Android LMK on a 6GB-RAM device,
  with the whisper handle confirmed released before the summarization model loads.
- All three `SummarizationProviderChoice` × fallback combinations exercised on a real
  device: Cloud-configured-success, Cloud-configured-network-down → confirm prompt →
  on-device, OnDevice-chosen-model-present, OnDevice-chosen-model-absent → download
  prompt.

## Out of scope (explicitly, per ADR-0012)
- A second summarization model or a model-selection toggle (e.g. Phi-4 Mini).
- A second inference runtime (MediaPipe, ExecuTorch).
- Any change to the backend `SummarizeAgent`/`IChatClient` contract or behavior.
