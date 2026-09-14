# ARC-069: Evaluate swapping ggml-base.en.bin for a better Whisper model

Status: open, deferred

## Context

Current model: `ggml-base.en.bin`, ~148MB, hosted at
`github.com/Danish8321/harken/releases/tag/models-v1`
(`ModelDownloadManager.MODEL_DOWNLOAD_URL`).

Product decision on 2026-09-14 (see `bug-ggml-sigsegv-vec-dot-f16.md`
closing decision): app targets mid+ processor phones, not e625f-class
hardware. That headroom is why a bigger model is worth reconsidering now.

## Candidate

`small.en` — ~488MB, ~3x current size, meaningfully better transcription
accuracy than base.en, still English-only. Not yet hosted: only
`ggml-base.en.bin` exists on the `models-v1` release.

`medium.en` considered and rejected for now — ~1.5GB, likely too slow for
on-device phone decode, and RAM pressure during decode is the same axis
that was in play for the SIGSEGV investigation (different bug, same
decode path).

## Decision so far

Replace outright if this proceeds (not a user-facing picker) — one
model, bump `MODEL_FILE_NAME` / `MODEL_DOWNLOAD_URL` / `MODEL_SHA256`
together per the existing comment discipline in `ModelDownloadManager`.
A picker (base.en vs small.en as a Settings choice) was considered and
rejected as real feature work, not a quick swap.

## Why deferred

User wants to wait for results from the multi-day monitoring build
(finalize build with file-logging/crash-capture, `fc1feec`) currently
running on real hardware before spending a 3x model-size/decode-time
increase on top of an unproven baseline.

## Next steps, when revisited

1. Pull whatever `Diagnostics → Export logs` reports from the monitoring
   run — decode timings, crash rate, device RAM tier actually seen.
2. Benchmark `small.en` decode time + peak RAM on a mid-tier phone
   (ideally the same `eece2e35`/AIN065 used for SIGSEGV repro) before
   committing to the swap.
3. Upload `ggml-small.en.bin` to a new/updated GitHub release asset,
   compute its SHA-256, bump the three constants in
   `ModelDownloadManager.kt` in one commit.
4. Re-verify the model-download flow end to end (truncated-download
   check already exists and applies unchanged).
