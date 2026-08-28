# Slice 9 on-device transcription — follow-ups

Branch: `feat/on-device-transcription`. Opened 2026-08-27.

## 1. First-run "download model" setup step + Settings re-download/update option
Status: done (`732f552`).

## 2. Revert temporary MODEL_DOWNLOAD_URL
Status: done (`a6b43d4`). Real `models-v1` GitHub Release confirmed live
(`gh release view models-v1` — asset `ggml-base.en.bin` present), URL casing fixed to
`Danish8321`, on-device retry verified per that commit's message.

## 3. Remaining manual on-device checks
Status: 2 of 3 confirmed 2026-08-28 on SM-E625F (serial RZ8R20CRB9T), fresh install.
Transcript accuracy still open — only remaining blocker to merge.

- **Transcript accuracy — not confirmed.** Ran the full pipeline (onboarding → model
  download → record 0:36 → Transcribe from Library) crash-free: 0 SIGSEGV/FATAL EXCEPTION
  across ~5 min of polling (`adb logcat -d -t <N> | grep -c "SIGSEGV\|FATAL EXCEPTION"`),
  stable pid throughout (`adb shell pidof com.harken.android`). Transcript came back
  `[BLANK_AUDIO]` x2, which is whisper's correct output for the ambient-noise input used —
  no real speech was fed to the mic (not injectable via adb). Someone needs to physically
  speak into the phone during a recording and confirm the transcript reads back
  correctly.
- **Summarize button hidden + playback shows "no audio file" message — CONFIRMED.**
  Summarize button entirely absent from the session detail sheet for a local-only
  session; player shows "0:00 / Recorded on-device; no audio file to play back".
- **Offline / interrupted-download handling — CONFIRMED.** Force-stopped the app
  mid-redownload (`.tmp` caught at 145,586,877 bytes via
  `adb shell run-as com.harken.android ls -la files/models/`). On relaunch, Settings
  correctly showed "Not downloaded yet" (didn't mistake the stale `.tmp` for a real
  model). Retry redownload completed cleanly to the correct final size
  (147,964,211 bytes), old `.tmp` gone, 0 crashes. Also confirms the S3 fix from
  [review-slice-09-findings.md](review-slice-09-findings.md) (Content-Length validation)
  didn't regress the happy path.

Also live-confirmed on-device: the SP6 duration fix (session showed "Transcribed ·
0m 36s", matching the actual 0:36 recording) and the SP3 onboarding copy fix (step 3 now
reads "whenever you tap Transcribe").

Blocks: full gate in `docs/plans/slice-09-on-device-transcription.md`, and merge to
`master` ([slice-10](../docs/plans/slice-10-organic-design-system.md) and
[slice-11](../docs/plans/slice-11-on-device-summarization.md) are both blocked on this
merging). Transcript accuracy is the one item standing between this branch and merge.

## Note (2026-08-28)
Found and discarded unrelated uncommitted working-tree changes (predating this session)
that removed backend-URL configuration from Settings/Onboarding entirely — conflicted
with ADR-0012's on-device-summarization plan, which needs a configurable backend URL for
its Cloud provider option. Confirmed with user: discard, not part of this slice's scope.
