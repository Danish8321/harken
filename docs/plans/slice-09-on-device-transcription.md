# Slice 9: On-device transcription, backend optional

Implements [ADR-0011](../adr/0011-on-device-transcription.md). Branch:
`feat/on-device-transcription` (off `master`, independent of the unmerged
`feat/azure-batch-transcription`).

**Status (2026-08-26):** Tasks 1–6 committed and pushed to `origin/feat/on-device-transcription`.
Not merged — the full gate below (real-device manual checks) is still outstanding.
[Slice 10](slice-10-organic-design-system.md) is blocked on this merging to `master`.

Room already exists as a full local mirror (ADR-0010 in the Android tree,
`HarkenDatabase`/`SessionDao`/`SessionRepository`) — this slice does NOT introduce a new
local DB, it adds a **local-only session** mode to the existing one (a session that is
never synced from/to the backend), plus the native transcription pipeline that produces
one.

`SessionRow` gets one new column, `isLocalOnly: Boolean = false`. This is real user data
(a local-only session's transcript exists nowhere else), so it goes through a real Room
`Migration`, never `fallbackToDestructiveMigration()`.

## Tasks

### Task 1 — Vendor whisper.cpp + NDK/CMake JNI scaffold
**Files:**
`src/Harken.Android/app/src/main/cpp/CMakeLists.txt` (new),
`src/Harken.Android/app/src/main/cpp/whisper/` (new — vendored whisper.cpp source,
pulled from the upstream ggml-org/whisper.cpp release tag matching the model format
already used by the backend's Whisper.net, so ggml model files are interchangeable),
`src/Harken.Android/app/src/main/cpp/harken_whisper_jni.cpp` (new — JNI bridge:
`nativeLoadModel(String path): Long` (context handle), `nativeTranscribe(long handle,
short[] pcm16, int sampleRate): String[]` returning parallel arrays is awkward in JNI, so
instead return a single JSON string `[{"offsetMs":0,"text":"..."}]` decoded on the Kotlin
side — simplest correct boundary), `nativeFreeModel(long handle)`.
**Change:** `app/build.gradle.kts` gets an `externalNativeBuild { cmake { path =
"src/main/cpp/CMakeLists.txt" } }` block and `ndk { abiFilters += "arm64-v8a" }` (single
ABI for now — matches `minSdk 26`+modern-device assumption, avoids multi-ABI build time
while this is unproven).
**Verify:** `./gradlew.bat assembleDebug` succeeds (compiles the native lib for
arm64-v8a). This is the one task in this slice where a green build does not prove
correct transcription — flag that explicitly in the task-executor's report; real
correctness needs Task 3's manual on-device check.

### Task 2 — Room: local-only session column + migration
**Files:**
`src/Harken.Android/app/src/main/kotlin/com/harken/android/data/local/LocalModels.kt`,
`src/Harken.Android/app/src/main/kotlin/com/harken/android/data/local/SessionDao.kt`.
**Change:** add `val isLocalOnly: Boolean = false` to `SessionRow`; bump
`@Database(version = 2, ...)`; add `MIGRATION_1_2` (`ALTER TABLE sessions ADD COLUMN
isLocalOnly INTEGER NOT NULL DEFAULT 0`) registered via `.addMigrations(MIGRATION_1_2)`
in `HarkenDatabase.get(...)` — no destructive fallback. Add
`SessionDao.insertLocalOnly(session: SessionRow)` (plain `@Insert`, distinct from
`upsertMirrored` — a local-only row is never touched by sync) and
`SessionDao.completeLocalTranscription(id, segments: List<SegmentRow>, durationSeconds:
Int)` (a `@Transaction`: updates status to `"Succeeded"`, `segmentCount`, replaces
segments) and `SessionDao.failLocalTranscription(id, reason: String)`.
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Write one Room migration test
(`MigrationTestHelper`, matching whatever test scaffolding already exists under
`src/test` — if none exists for Room yet, a plain instrumented test asserting the column
exists post-migration is enough) — this is the Room-world equivalent of `schema.sh
verify`, so it isn't skipped.

### Task 3 — OnDeviceTranscriber + model download manager
**Files:** `src/Harken.Android/app/src/main/kotlin/com/harken/android/speech/OnDeviceTranscriber.kt`
(new — thin Kotlin wrapper over the JNI functions from Task 1, decodes the JSON result
into `List<TranscribedSegment>`), `src/Harken.Android/app/src/main/kotlin/com/harken/android/speech/ModelDownloadManager.kt`
(new — checks `filesDir/models/ggml-base.en.bin` exists; if not, downloads it via OkHttp
from a GitHub Releases asset URL on this repo with progress callback, writes to a `.tmp`
path and renames on success so a killed download never leaves a half-written model file
mistaken for a real one).
**Change:** none to existing files yet (wired into `CaptureViewModel` in Task 4).
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: with a real device,
trigger a download, confirm the model file lands and `OnDeviceTranscriber` produces
non-empty segments for a short test recording — this is the task that actually proves
Task 1's native build works, not just compiles.

### Task 4 — Wire on-device path into capture, bypass upload for WhisperLocal
**Files:** `src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/CaptureViewModel.kt`.
**Change:** when `cachedProvider == TranscriptionProviderChoice.WhisperLocal`, skip
`api.upload(...)` entirely: call `SessionRepository.createLocalSession(...)` (new
repository method wrapping `dao.insertLocalOnly`, generating the same shape of row
`refresh()` would have produced, with `isLocalOnly = true`, `transcriptionStatus =
"Running"`), run `OnDeviceTranscriber` against the recording file, then
`SessionRepository.completeLocalTranscription(...)` or `failLocalTranscription(...)`. If
`cachedProvider == AzureBatch`, behavior is unchanged (existing HTTP upload path) — Azure
still requires a reachable, configured backend, per ADR-0011 decision 5.
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: record with WhisperLocal
selected and NO backend URL configured (or backend unreachable), confirm a session still
appears in Library with a real transcript and no network error surfaced.

### Task 5 — Hide Summarize when no backend / local-only session
**Files:** `src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/SessionSheet.kt`,
`src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/SessionSheetViewModel.kt`.
**Change:** `SessionSheetViewModel` exposes `canSummarize: Boolean` (`!session.isLocalOnly
&& AppSettings.isValid(cachedBaseUrl)`); `SessionSheet.kt`'s Summarize button
(around line 210) renders only when `state.canSummarize` is true — matches ADR-0011
decision 4 (hidden, not shown-and-erroring).
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: open a local-only
session, confirm no Summarize button/menu renders at all.

### Task 6 — Onboarding: backend becomes optional, add model-status copy
**Files:** `src/Harken.Android/app/src/main/kotlin/com/harken/android/ui/OnboardingScreen.kt`.
**Change:** remove the mandatory "must connect before continuing" gate; the backend-URL
step gets a "Skip for now" action alongside the existing Connect action, with body copy
explaining what connecting unlocks (cloud transcription via Azure, AI summaries) versus
what works with no backend at all (on-device transcription, Library, playback). No new
step for model download — that stays lazy (first recording triggers it per Task 3),
keeping first-run friction to "open the app and record."
**Verify:** `./gradlew.bat compileDebugKotlin` succeeds. Manual: fresh install, tap Skip
at the backend step, confirm the app reaches the main screen and a recording can be made
and transcribed with no backend ever configured.

## Full gate
After all tasks: `.claude/scripts/check.sh` green, plus every manual on-device check
listed above run on a real device (this slice cannot be fully verified by build/compile
checks alone — native inference correctness and the true "zero backend" path both need a
phone).
