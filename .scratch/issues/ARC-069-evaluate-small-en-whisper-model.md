# ARC-069: Evaluate swapping ggml-base.en.bin for a better Whisper model

- **Severity:** medium
- **Area:** `speech/ModelDownloadManager.kt`, `docs/adr/0017-eight-gigabyte-minimum-after-small-en.md`
- **Status:** done — swapped, measured on real hardware, minimum device raised to
  8 GB (ADR-0017). Follow-up `small.en-q5_1` tracked at the bottom, and much less
  urgent since [ARC-070](ARC-070-ggml-built-without-arm-fp16-kernels.md) removed
  the decode-speed argument for it.

## 2026-09-17: swap done ahead of monitoring results

User chose to replace outright now and regress on emulator rather than wait.

- `ModelDownloadManager`: `ggml-small.en.bin`, 487,614,201 bytes, SHA-256
  `c6138d6d58ecc8322097e0f987c32f1be8bb0a18532a3f88f734d1bbf9c41e5d`
  (matches upstream Hugging Face). New `discardRetiredModels()` deletes
  `ggml-base.en.bin` (+ `.tmp`) at launch.
- Strings: onboarding size 140 → 470 MB, out-of-space hint 150 → 500 MB.
- Session meta label now `whisper small.en` — not stored per transcript, so
  base.en-era transcripts also show small.en.

Emulator (AVD `Android12`, API 37 x86_64 + libndk_translation, 4 GB RAM,
debug build, model sideloaded), 120 s AMI ES2002a clip (60–180 s):

| | small.en (emulator) | base.en (Nothing Phone 2, 2026-09-04) |
|---|---|---|
| Peak total PSS | **1.16 GB** | 594–610 MB |
| Peak native heap | 1.006 GB | 451–482 MB |
| PSS after finish | 168 MB | ~195 MB |
| Decode RTF | 1.48 (ARM translation — not meaningful) | 0.24–0.27 |
| Model load | 2.5 s | 157–280 ms |

PSS climbed stepwise through the decode instead of plateauing. LMK killed
Chrome/Photos/keyboard at model load on the 4 GB AVD. Output: 33 segments,
accurate text, no crash. Retired-model cleanup verified on device
(`model_retired_discarded`).

## 2026-09-17, same evening: measured on the real device

Device `eece2e35` (Nothing Phone 2, AIN065, Android 16, 7,270 MB totalMem),
debug build, model pushed over USB (phone was on LTE — no 488 MB of the user's
mobile data spent). Same 120 s AMI fixture, same build as the emulator run.

| | small.en (AIN065) | base.en (AIN065, 2026-09-04) |
|---|---|---|
| decodeMs, 118 s span | 127,543 | ~28,300 |
| realtimeFactor | **1.06** | 0.24 |
| decodedRealtimeFactor | **1.08** | 0.25 |
| Peak total PSS | **1.15 GB** | 594–610 MB |
| Peak native heap | 1.00 GB | 451 MB |
| Model load | 834 ms | 157–280 ms |
| PSS after finish | 141 MB | ~195 MB |
| Segments | 33 | — |

**small.en decodes slower than real time on the target device.** A 3-hour
recording would take ~3.2 hours to transcribe, against ~43 minutes on base.en
— the figure ADR-0014 and the perf work of 2026-09-04 were built around.
The emulator's ARM translation had masked this as a 1.48 RTF that looked like
a translation artefact; on bare arm64 it is only 1.4x better than that.

Memory pressure is real, not just an emulator artefact: 20 `lowmemorykiller`
kills during the run on a 7 GB phone (LinkedIn, Play background, two Nothing
system apps), plus a critical-pressure event the killer chose to ignore.

Nothing about the swap is wrong mechanically — download, verification, retired-
model sweep and decode all work on both the emulator and the phone. The
question was only whether this model's cost is acceptable.

## Decided 2026-09-18: keep small.en, raise the bar to 8 GB

User chose accuracy ("Let's go for the best"), then asked for the device bar to
be decided from the connected phone rather than extrapolated.

Second decode on the same device, `/proc/meminfo` sampled every 3 s across the
run (51 samples, `scratchpad/sysmem-phone.log`):

| | value |
|---|---|
| `realtimeFactor` | 1.22 (`decodedRealtimeFactor` 1.25), 33 segments |
| `MemAvailable` | 2,645,280 kB → **1,821,128 kB** — 824 MB given up |
| `SwapFree` | 1,379,460 kB → 1,157,640 kB — 222 MB actually swapped |
| LMK | 20 `Kill` lines + one ignored critical-pressure event |

That is the reference device, which is *above* the old 6 GB bar, paying for the
peak with background processes and swap. 1.15 GB is 43% of what this 8 GB phone
has available; on a 6 GB phone (~1.9 GB available) it is 60% — a worse ratio than
the one ADR-0014 refused to ship to a 4 GB phone at 610 MB. ADR-0014's own
closing rule ("a change that raises it materially raises the minimum device with
it") therefore decides it.

**[ADR-0017](../../docs/adr/0017-eight-gigabyte-minimum-after-small-en.md)**
supersedes ADR-0014. `MINIMUM_NOMINAL_GB` 6 → 8, `MINIMUM_TOTAL_MEM_BYTES`
4.5 GB → 6.2 GB (midway between a 6 GB device's ~5.34 GiB report and this one's
7.10 GiB). Stale ~610 MB / 480 MB claims swept from `AndroidManifest.xml`,
`TranscriptionService`, `TranscriptionCoordinator`, `OnDeviceTranscriber`,
`docs/setup.md`, `docs/onboarding.md`.

RTF is a real regression and is *not* fixed by this: transcription is now slower
than real time and every estimate written against 0.24 is wrong.

## Follow-up: ARC-070 candidate — `small.en-q5_1`

Untested. ~182 MB quantized, plausibly returns memory to roughly base.en levels
and brings the 6 GB tier back. Worth measuring if the 8 GB bar proves too narrow
or if RTF > 1 turns out to bite in real use. Same fixture, same device, same
instrumentation as above — the measurement is now a repeatable recipe.

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
