# Slice 9 on-device transcription — follow-ups

Branch: `feat/on-device-transcription`. Opened 2026-08-27.

## 1. First-run "download model" setup step + Settings re-download/update option
Status: done (`732f552`).

## 2. Revert temporary MODEL_DOWNLOAD_URL
Status: done (`a6b43d4`). Real `models-v1` GitHub Release confirmed live
(`gh release view models-v1` — asset `ggml-base.en.bin` present), URL casing fixed to
`Danish8321`, on-device retry verified per that commit's message.

## 3. Remaining manual on-device checks
Status: 3 of 3 confirmed 2026-08-28 on SM-E625F (serial RZ8R20CRB9T), fresh install.
All three manual checks now confirmed — no remaining blocker to merge from this item.

- **Transcript accuracy — CONFIRMED.** adb can't inject spoken audio, so used a
  TTS-loopback: synthesized known-text speech via Windows SAPI
  (`System.Speech.Synthesis.SpeechSynthesizer`), played it through the device's own
  speaker (Samsung MyFiles app) while Harken recorded via the mic, then ran Transcribe
  from Library on that recording. Ran crash-free: 0 SIGSEGV/FATAL EXCEPTION across the
  full run (`adb logcat -d -t <N> | grep -c "SIGSEGV\|FATAL EXCEPTION"`), stable pid
  throughout (`adb shell pidof com.harken.android`, CPU ~452% during inference —
  compute-bound, not hung). Transcript came back a **word-for-word exact match** to the
  ground truth text: "The quick brown fox jumps over the lazy dog." / "Testing on device
  transcription accuracy for the Harken Android application." (segments 1–2 of 4; segments
  3–4 correctly read `[silence]` for the trailing dead air after the clip ended).
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
merging). All three manual checks are now confirmed; remaining pre-merge work is the
still-open items in [review-slice-09-findings.md](review-slice-09-findings.md) and the
unresolved [SIGSEGV bug](bug-ggml-sigsegv-vec-dot-f16.md) (mitigated, not root-caused).

## Note (2026-08-28)
Found and discarded unrelated uncommitted working-tree changes (predating this session)
that removed backend-URL configuration from Settings/Onboarding entirely — conflicted
with ADR-0012's on-device-summarization plan, which needs a configurable backend URL for
its Cloud provider option. Confirmed with user: discard, not part of this slice's scope.
