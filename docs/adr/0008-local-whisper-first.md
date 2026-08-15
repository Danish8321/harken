# 8. Local Whisper first, Azure second, behind one seam

Date: 2026-08-15

## Status
Accepted

## Context
[ADR-0007](0007-record-then-transcribe.md) settles *when* transcription happens — after
recording, as a job. This settles *what does it*.

Cost is the primary constraint. A Visual Studio Enterprise subscription supplies $150 of
Azure credit per month, which sounds like it removes the constraint, but the terms are
narrow: dev/test use by the individual subscriber only, no rollover, no SLA. It cannot
legitimately fund other people's use, so any design that leans on it works only while
Harken has exactly one user — and the stated direction is family, then wider. Credit buys
time; it does not buy an architecture.

The hardware is already paid for: an RTX 3050 Laptop with 4 GB VRAM, an i5-12500H, 24 GB
RAM. Ollama and Gemma already run on it for summaries (ADR-0002), so local inference is
an established pattern here, not a new one.

Whisper.net 1.9.1 exists and is maintained (~495k downloads), wraps whisper.cpp, and
publishes a CUDA runtime for Windows. Its runtime list is Windows, Linux, macOS, Metal,
CoreML, CUDA, Vulkan, OpenVINO and Wasm — notably **no Android**, which is one of the
facts behind ADR-0007's decision that clients do not infer.

## Decision
**MVP 1 transcribes with local Whisper only.** No Azure, no cloud dependency, no meter.
Harken runs entirely on hardware already owned.

**MVP 2 adds Azure batch transcription as an alternate provider**, behind the same
interface, funded by the subscription credit while it remains a single-user app.

**One seam, chosen by configuration.** The backend declares which providers are
available; the user selects among what is declared. Backend configuration is the
authority — a provider the backend has no credentials for must never be offerable — and
the UI selects within that. This is the same shape as `IChatClient` for chat models
(ADR-0002) and the existing `ISpeechTranscriber`, so it is a third instance of a pattern
the project already commits to rather than a new idea.

**Model selection is configuration, not a code change**, so `small` and `medium` can be
compared on real recordings without a rebuild.

## Consequences
- **MVP 1 costs nothing to run** and needs no Azure account at all, which removes the
  entire setup path that was previously step one for a new machine.
- **It works with no internet.** Combined with offline capture, the whole pipeline
  survives a dead network — only the upload hop needs connectivity, and that is
  local-network only.
- **Transcription is not free in time.** Whisper on a 3050 is meaningfully slower than
  Azure. How much slower is unmeasured and is the main unknown this decision carries; a
  one-hour recording taking many minutes is plausible and acceptable, taking an hour is
  not. **This must be measured on a real recording before the phone client is built** —
  it is the reason the console proves the pipeline first.
- **VRAM is contended.** Gemma 3:4b resident is ~3 GB and Whisper `medium` ~2.5 GB, which
  do not both fit in 4 GB. Since transcription and summarization are sequential rather
  than simultaneous here, a short `OLLAMA_KEEP_ALIVE` lets Gemma unload between uses. A
  smaller Whisper model is the fallback if that proves fragile in practice.
- **A ~1.5 GB model file** must be present on the backend machine, versioned outside git,
  and downloaded as a setup step.
- **Accuracy is now our problem, not a vendor's.** If Whisper mis-transcribes, the answer
  is a bigger model or better audio, not a support ticket. Acceptable for a learning
  project; explicitly the trade being made.
- **Azure work is not wasted.** `AzureSpeechTranscriber` already exists and is tested. It
  changes shape for batch rather than being thrown away.
- **The credit is a runway, not a foundation.** Any future scope beyond one user needs a
  real funding answer. Recording this now stops "we have credit" from quietly becoming an
  assumption.

## Alternatives considered
- **Azure first, since credit makes it free.** Fastest path — the code already exists —
  but it builds the MVP on an entitlement that expires monthly, cannot cover a second
  user, and teaches nothing about running models. It also leaves the app unusable offline.
- **Local only, forever.** Simplest, and defensible while Harken is personal. Rejected as
  an *architecture*: the seam costs almost nothing to keep, and without it the eventual
  Azure move touches everything.
- **On-device inference on the phone.** Removes the backend entirely. Blocked on missing
  .NET bindings, and the phone-sized models are the inaccurate ones. Revisitable later as
  a third provider behind the same seam.
- **A hosted Whisper API (OpenAI or similar).** Cheaper per hour than Azure real-time, but
  it is a meter and an account, which is exactly what this decision is avoiding.
