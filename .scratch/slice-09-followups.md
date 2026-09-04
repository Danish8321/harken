# Slice 9 on-device transcription — follow-ups

Branch: `feat/on-device-transcription`, merged. Opened 2026-08-27,
reviewed 2026-09-04 against the device regression run
([device-regression-2026-09-04.md](device-regression-2026-09-04.md)).

## 1. First-run "download model" setup step + Settings re-download/update option
Status: **done**.

Onboarding step 2 is the explicit download (`onboarding2_step4_title`
"Get the speech model", `onboarding2_download_model`, progress via
`onboarding2_downloading`, terminal state `onboarding2_model_ready`). Settings
carries the manual action (`settings_model_update` / `settings_model_download`
with `settings_model_downloading` and `settings_model_download_failed`).
Both exercised on device 2026-09-04 on a fresh install — the download ran from
onboarding and reached "Model ready".

## 2. Revert temporary MODEL_DOWNLOAD_URL
Status: **done**.

`ModelDownloadManager.kt:145` points at
`https://github.com/Danish8321/harken/releases/download/models-v1/ggml-base.en.bin`,
and that release asset exists — a fresh install downloaded ~140 MB from it on
2026-09-04.

## 3. Remaining manual on-device checks
Status: **two of three done**.

- **Transcript accuracy — done.** A deterministic 41.04 s TTS fixture
  (speech / 25 s silence / speech) transcribed to 3 correct segments at
  0:00, 0:04 and 0:32. Silence-only audio now yields 0 segments instead of
  eleven hallucinated " you" lines.
- **Playback — done, and the "no audio file" message it was written against no
  longer exists.** `NoPlaybackCard` was replaced by a real transport
  (`PlaybackCard` + `PlaybackCursor` + MediaPlayer in `SessionSheetViewModel`);
  `session_no_playback` is gone and `session_audio_missing` covers only the
  case where the WAV is genuinely off the phone. Play / pause / scrub /
  tap-a-segment / end-of-file / sheet-close all verified on device.
- **Offline / interrupted-download handling — still not confirmed.** Kill the
  app mid-download, confirm no corrupt `.tmp` is left behind, confirm retry is
  clean. This is the only item from this list still open.
