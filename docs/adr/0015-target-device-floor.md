# ADR-0015: Target device floor is Nothing Phone (2)-class, and the CI-adjacent test device is below it

## Status
Accepted — 2026-08-29

## Context
Every sizing decision in the on-device pipeline — which Whisper model, which summarization
model, which quantization, how many threads, whether a GPU delegate is worth building —
depends on a floor that has never been written down. In its absence the only anchor was
the one device the project happens to own: **SM-E625F, Exynos 850, 8× Cortex-A55 with no
big cores at all**. That is the device the unexplained `ggml_vec_dot_f16` SIGSEGV was
observed on (`.scratch/bug-ggml-sigsegv-vec-dot-f16.md`), and it is a 2021 budget SoC.

Sizing to that device caps the product's local quality at what an all-little-core CPU can
do, forever, for the sake of a market segment the product is not aimed at.

## Decision
The supported floor is **Nothing Phone (2)-class and newer**, stated as capability rather
than as one handset:

- a prime-core SoC of Snapdragon 8+ Gen 1 calibre or better (Cortex-X2-class prime core,
  Adreno 730-class GPU),
- 8 GB RAM minimum,
- Android 13 (API 33) or newer.

Harken is not built for, tested against, or quality-guaranteed below that. This is a
deliberate 2022-flagship-and-up floor, not a "modern Android" floor.

The reference device is the project owner's own Nothing Phone (2), which means the
supported configuration is testable daily rather than theoretically.

The Exynos 850 (SM-E625F) is **below the floor** and out of the support scope. It is kept
for one purpose: a lower-bound canary. A crash there is information, not a release
blocker.

## Consequences
- **The open SIGSEGV is an out-of-scope observation, not a shipping bug.** Its status in
  `.scratch/bug-ggml-sigsegv-vec-dot-f16.md` must be restated as such. It is not thereby
  dismissed — it is an unexplained memory fault in vendored native code that runs on every
  device, and "only seen below the floor" is a reason to deprioritise it, not a diagnosis.
  It cannot be closed until it is either root-caused or reproduced-and-cleared on an
  in-scope device.
- **Every measurement taken so far is void.** Benchmarks, latency figures, RAM readings
  and quality judgements to date all come from hardware the product does not target, and
  none of it transfers upward — eight A55 cores say nothing about sustained load on an X2
  prime core. The M1 evidence gate has to be run from scratch on the reference device.
- **The reference device is available**, so that is a scheduling problem rather than a
  procurement one.
- **The emulator is not a fallback.** `app/build.gradle.kts` sets
  `ndk { abiFilters += "arm64-v8a" }`, so the APK carries no x86_64 native library and
  will not run on a standard x86-host emulator image at all. Testing requires either real
  arm64 hardware or a device farm.
- **`minSdk 26` no longer describes the floor.** No device at this tier ships below
  Android 13 (API 33); the reference device launched on it. API 26–32 support is a claim
  the project neither tests nor needs, and it forces compatibility branches for platform
  features that every supported device has. Whether to raise `minSdk` to 33 is an open
  question, not settled here.
- **Model sizing is unlocked.** An X2-class, 8 GB-plus device with an Adreno 730 hosts a
  3–4B parameter quantized summarizer comfortably, and a GPU-delegated runtime is a real
  option rather than an aspiration. The binding constraint on the local model is its
  licensing and download story, not RAM.

## Alternatives considered
- **Floor at the cheap test device (Exynos 850).** Rejected: caps local summary quality at
  what eight A55 cores can produce and drags every model choice down with it, to serve
  users the product is not for.
- **Floor at Snapdragon 888 (2021).** Considered and raised one tier, because the
  reference device is 8+ Gen 1 — a floor nobody owns hardware for is a floor nobody
  tests.
- **Two floors — transcription supported low, summarization supported high.** Rejected as
  the primary shape: it makes the headline feature disappear on hardware the team can
  test, so its regressions would never be caught. (Cloud Mode, ADR-0014, is the real
  answer for a user on weak hardware.)
- **No floor, best-effort everywhere.** Rejected: unfalsifiable. Without a floor there is
  no configuration a benchmark can pass or fail, and the evaluation set has nothing to
  mean.

## Related
[ADR-0011](0011-on-device-transcription.md), [ADR-0012](0012-full-standalone-local-summarization.md),
[ADR-0014](0014-local-first-with-optional-cloud-mode.md)
