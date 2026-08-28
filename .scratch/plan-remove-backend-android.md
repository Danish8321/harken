# Plan: strip backend/cloud from Harken Android, full on-device only

Branch: `feat/on-device-transcription`. Scope: `src/Harken.Android` app only + docs.
Backend project (`Harken.Api`/`Harken.Console`) untouched — still used by console client.

Verification default: `cd src/Harken.Android && ./gradlew.bat compileDebugKotlin -q`
(clean exit = pass) unless a task states otherwise.

## Task 1 — `ui/CaptureViewModel.kt`
Remove: `api`/`HarkenApi` field, `cachedProvider`/`TranscriptionProviderChoice` branching,
`uploadToBackend()`, backend branch of `retryUpload()`. Collapse stop-recording flow to
always call `saveLocal()`. Keep `RecordingState`, `startRecording`/`stopRecording` mic logic.
Verify: compileDebugKotlin.

## Task 2 — `data/SessionRepository.kt`
Remove: `api: HarkenApi` constructor param + all call sites, `refresh()`, `refreshDetail()`,
`summarize()` (backend call), backend branch of `purge()`, `SessionListItem.toRow`/
`SessionDetail.toRow` DTO mappers. Keep: `createLocalSession`, `completeLocal`, `failLocal`,
`startLocalTranscription`, `toView`, `DerivedTitle`, Room `observe*` methods,
`isLocalOnly` short-circuit already in `purge()`.
Verify: compileDebugKotlin.

## Task 3 — `ui/LibraryViewModel.kt`, `ui/SessionSheetViewModel.kt`, `ui/SessionSheet.kt`
Remove: `LibraryViewModel.refresh()` (backend reconciliation) and its call sites/UI trigger
(e.g. pull-to-refresh if backend-only), `SessionSheetViewModel.canSummarize`/`summarize(id)`,
`SessionSheet`'s Summarize/Re-summarize button. Keep local-only transcribe trigger, rename/
tags, playback-gating logic.
Verify: compileDebugKotlin.

## Task 4 — `ui/SettingsScreen.kt`, `ui/SettingsViewModel.kt`
Remove: base-URL text field + "Test connection" button + result copy, `baseUrl`/
`testConnection()`/`onBaseUrlChanged` in the ViewModel, any provider-picker UI/state.
Keep: theme mode, dynamic color, model download/update UI+logic (on-device, unrelated).
Verify: compileDebugKotlin.

## Task 5 — `ui/OnboardingScreen.kt`
Remove the cloud-connect step (base-URL field, "Test connection", `ConnectionCheck` enum,
`connectionCheck`/`connectionMessage` state, `onBaseUrlChanged`/`testConnection()` in the
ViewModel, the `finish()` base-URL persistence call). Renumber remaining steps (recording
explainer, model download, permissions/whatever else) so step count/labels stay contiguous.
Verify: compileDebugKotlin + a quick uiautomator walkthrough is done in Task 8, not here.

## Task 6 — `data/AppSettings.kt` + delete dead network layer
- `data/AppSettings.kt`: remove `TranscriptionProviderChoice` enum, `Keys.BaseUrl`/
  `Keys.TranscriptionProvider`, `baseUrl`/`transcriptionProvider` flows + setters,
  `DefaultBaseUrl`, `isValid()`. Keep `onboardingComplete`, `themeMode`, `dynamicColor`.
- Delete outright: `network/HarkenApi.kt`, `network/NetworkModule.kt`,
  `network/SessionModels.kt`, `src/test/kotlin/com/harken/android/network/AppSettingsValidationTest.kt`.
- Grep the module for `HarkenApi|NetworkModule|BaseUrl|AzureBatch|TranscriptionProvider` after
  — must return zero hits outside this task's own diff context.
Verify: compileDebugKotlin, then `grep -rE "HarkenApi|NetworkModule|AzureBatch|TranscriptionProviderChoice" src/Harken.Android/app/src/main/kotlin` returns nothing.

## Task 7 — Docs
- `README.md` Mobile section (`### Configure`, `### Use`): remove backend base-URL /
  "Test connection" / upload-on-stop / cloud Summarize mentions. Onboarding is now
  local-only: permissions → model download → done (adjust step count to match Task 5).
- `docs/adr/0011-on-device-transcription.md`: strike/update decision 5 (Azure Batch —
  now removed, not just "unaffected"); note this ADR now describes the *only* transcription
  path, not an alternative to a backend one.
- Pop the stash (`git stash list` → `slice-11 planning prep...`), review its edits to
  `docs/adr/0012-full-standalone-local-summarization.md` and the new
  `docs/plans/slice-11-on-device-summarization.md` against this new full-local direction
  (they assumed a configurable backend URL for a Cloud summarize option — that option no
  longer exists in the app, so strip any reference to it), then commit them as part of
  this same doc pass rather than leaving them stashed.
- `.scratch/review-slice-09-findings.md`: mark S1 (backend claims), S6/SP1 (dead provider
  code) as resolved-by-removal (code deleted, not just noted).
Verify: read-through, no build step.

## Task 8 — Full verification
- `cd src/Harken.Android && ./gradlew.bat compileDebugKotlin -q` clean.
- `./gradlew.bat assembleDebug -q` clean.
- Fresh install on device (uninstall+reinstall per standing rule), walk onboarding
  (confirm no backend-URL step), Settings (confirm no backend-URL field), record →
  Transcribe from Library → confirm transcript, confirm Session Sheet has no Summarize
  button anywhere (it never should now, matches existing slice-09-followups confirmation).
- `adb logcat` crash-free through the walkthrough.
Verify: named commands above, outputs pasted in the completion report.
