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
Status: **done**.

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
- **Offline / interrupted-download handling — done, and one real defect fixed
  (`a36a506`).** Verified on device 2026-09-04 on a fresh install.

  *No corrupt `.tmp` is ever left behind* — confirmed. The download writes to
  `ggml-base.en.bin.tmp` and only renames after the stream completes, so a
  partial file can never be loaded as a model. A `.tmp` found on disk is
  invisible to `isModelPresent()`, and the next attempt truncates it before
  writing. Retry is clean: cutting the network mid-download deleted the `.tmp`,
  surfaced a Retry action, and the retry completed (148 MB in 15.1 s).

  *But a `.tmp` was left behind* — not corrupt, just abandoned. Killing the
  process mid-download left an 86 MB file that nothing on any later path
  removed, because the cleanup only ran in `onFailure` and a dead process throws
  nothing. Up to 148 MB of the user's storage, held indefinitely, invisible in
  the app. `discardPartialDownload()` now reclaims it at launch next to the
  recording orphan sweep, skipped while a download is genuinely in flight so
  re-entering the activity mid-download cannot delete a live file. Both branches
  verified on device, and the path now emits telemetry where it previously
  emitted only `Log.e`.

## 4. Found while verifying item 3 — all fixed 2026-09-05

1. **Raw exception text is shown to the user.** Cutting the network mid-download
   puts *"Software caused connection abort"* on screen, in both onboarding and
   Settings. That is a socket message, not something a person can act on. A lost
   connection should say so.
2. **A failed model update destroys the working model.** Settings "Update"
   deletes the current model and *then* downloads. Interrupting that leaves the
   user with no model at all and on-device transcription unavailable —
   reproduced on device, recovered only by a successful retry. The download
   should land in `.tmp` and replace the model only on success, exactly as the
   first-run path already does.
3. **No resume.** Every retry restarts from byte 0 of 148 MB; no `Range` header
   is sent even though the release asset supports it. On a flaky mobile
   connection the download may never complete, and each attempt costs the user
   148 MB of data.
4. **`runCatching` swallows `CancellationException`** in both `ensureModel` and
   `downloadProgress`, so a cancelled download is reported as a failed one and
   structured concurrency is broken at that boundary.
5. **No integrity check.** Nothing verifies size or checksum before the rename.
   OkHttp raises on a premature close when `Content-Length` is known, which
   covers the common case, but a chunked or length-less response could rename a
   truncated file into place as a valid model.

All five fixed in `7269302`, on a fresh install on the Nothing Phone 2:

- Errors are classified once (`ModelDownloadFailure`) and each screen maps the
  class to a sentence. Cutting the network now reads *"No connection. The
  download will pick up where it left off when you retry."*
- Settings "Update" downloads to `.tmp` and replaces only on success. An
  interrupted update left `ggml-base.en.bin` intact on disk, the screen said
  *"Update failed; the model you have still works."*, and the button stayed
  "Update" rather than reverting to "Download".
- Resume works: the network was cut at 29,422,404 bytes and the retry sent a
  `Range` header — `model_download_stream resumedFromBytes=29422404
  httpCode=206` — finishing at exactly 147,964,211 bytes.
- `runCatchingDownload` rethrows `CancellationException`.
- The transferred size is checked against `Content-Length` before the move.

Because a partial is now a resume point, the launch sweep keeps it and discards
only partials older than `StalePartialAgeMs` (24 h). Verified: a relaunch two
minutes after a failure logged `model_partial_kept ageMs=128380` and left the
44 MB partial in place.

## 5. Found while verifying the crash breadcrumb (item 3 of the perf report)

Both fixed in `2a07c85`.

1. **A transcription killed with the process was stuck forever.** The session
   row stayed `Running`, so the Library showed a spinning "Transcribing" chip
   with nothing running behind it, on every launch thereafter. A transcription
   cannot outlive the process, so `Running` rows are now settled as failed at
   launch (`transcription_interrupted_recovered sessions=1`).
2. **A failed transcription offered no retry.** The Library rendered only a
   "kept on device" chip for `Failed`, so *any* failure left the recording with
   no way forward. Failed rows now offer Transcribe, like never-started ones.
   The recovered session retried to completion: 221 s in, 81 s decoded over 6
   spans, 13 segments, `realtimeFactor=0.19`.
