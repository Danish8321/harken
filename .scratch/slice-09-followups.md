# Slice 9 on-device transcription — follow-ups

Branch: `feat/on-device-transcription`. Opened 2026-08-27.

## 1. First-run "download model" setup step + Settings re-download/update option
Status: not started.

Currently the whisper model downloads lazily on first recording
(`ModelDownloadManager.ensureModel()`, called from `CaptureViewModel.transcribeOnDevice`).
User wants an explicit setup step instead:
- First-run onboarding: an explicit "download model" step, not silently triggered by
  the first recording.
- Settings page: a manual "update/re-download model" action.

Touches: `OnboardingScreen.kt`/`OnboardingViewModel.kt`, Settings screen (find current
file), `ModelDownloadManager.kt` (already has `downloadProgress(): Flow<Int>` to wire
into a progress UI).

## 2. Revert temporary MODEL_DOWNLOAD_URL
Status: dirty in working tree, not committed.

`ModelDownloadManager.kt`'s `MODEL_DOWNLOAD_URL` was temporarily pointed at
`https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin` for the
on-device smoke test. Must revert to a real GitHub Release asset hosted on this repo
(`https://github.com/danish/harken/releases/download/models-v1/ggml-base.en.bin` was the
original placeholder — need an actual release with the model asset uploaded) before
merging to `master`.

## 3. Remaining manual on-device checks
Status: not confirmed.

- Transcript accuracy — asked user, not yet answered.
- Summarize button hidden + playback shows "no audio file" message for local-only
  session — asked user, not yet confirmed on-screen.
- Offline / interrupted-download handling: kill app mid-download, confirm no corrupt
  `.tmp` file left behind, confirm retry works cleanly.

Blocks: full gate in `docs/plans/slice-09-on-device-transcription.md`, and merge to
`master` (slice-10 is blocked on that merge).
