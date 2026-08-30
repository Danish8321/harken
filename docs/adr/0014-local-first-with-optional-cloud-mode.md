# ADR-0014: Two Modes — Local by default, Cloud as an opt-in upgrade

## Status
Accepted — 2026-08-29

## Context
[ADR-0011](0011-on-device-transcription.md) moved default transcription on-device and
called the backend an "optional upgrade", but what shipped went further than the ADR
said. Commit `80c463d` ("on-device-only transcription") removed the Android HTTP client
entirely: `AppSettings` no longer holds a `baseUrl`, and the only network call left in the
app is `ModelDownloadManager` fetching the Whisper model. The optionality ADR-0011
described does not exist in code — the seam is closed, not optional.

That left three things undeclared:
- `Harken.Api` and `Harken.Console` are still built by `check.sh` but unreachable from the
  product, and are the only place summarization exists (`SummarizeAgent` over Ollama /
  `gemma3:4b`, ADR-0002).
- The Android app renders a `SummaryCard` from a Room `summaries` table that nothing
  writes. The README's headline promise ("Record → transcript → AI summary") is
  unfulfillable on device today.
- `CONTEXT.md` still defined Transcription as happening "on the backend after Upload,
  never on the client" — the opposite of what runs.

This ADR states the end-state shape the product is actually being built toward, so that
"is the backend part of this product or not" stops being answered differently by the
docs, the glossary and the code.

## Decision
Harken has exactly **two Modes**, and the User chooses.

**Local Mode — the default, and the whole product on its own.**
Record, transcribe and summarize entirely on the device. No account, no network, no
backend, no configuration. Every core workflow completes with the device in airplane
mode. This is what a user gets on install, without being asked anything.

**Cloud Mode — opt-in, off by default, behind an explicit Settings feature flag.**
Buys quality and capability that a phone-sized model cannot reach: expert transcription,
expert summarization, and chat over a Session's findings. Turning it on is a deliberate,
informed act by the User, never a default and never automatic.

Binding rules:

1. **Local Mode is never degraded by Cloud Mode existing.** Every feature that works
   today with no network keeps working with no network, forever. Cloud is additive.
2. **No network call happens while the flag is off.** Not a probe, not a health check, not
   telemetry. Flag off means the app behaves exactly as the current on-device-only build
   does, apart from the one-time model download.
3. **No silent fallback in either direction.** A failed on-device summarization does not
   quietly retry in the cloud, and a Cloud Mode outage does not silently downgrade to the
   local model without saying so. The User always knows which engine produced what they
   are reading.
4. **The Cloud API is versioned from its first release** (`/v1/...`), and the app states
   which API version it speaks. Local Mode has no version to negotiate; Cloud Mode does,
   because the app and the backend will ship on different clocks.
5. **In Cloud Mode, the Recording itself leaves the device.** Expert Transcription needs
   the audio, not a transcript of it, so Cloud Mode uploads the Recording. This is the
   whole reason the Mode is opt-in and off by default. Local Mode is unaffected: with the
   flag off, nothing leaves the device but the model download.
6. **The backend doubles as the project's offline evaluation harness.** It already runs
   Whisper.net and an LLM on a machine with no RAM, thermal or battery ceiling, which
   makes it the natural place to generate the reference transcripts and reference
   summaries that on-device output is scored against. That role needs no product
   surface and no flag.

`Harken.Console` is not a product surface under either Mode. It becomes the evaluation
runner.

## Consequences
- **The `IChatClient` seam ([ADR-0002](0002-ichatclient-provider-seam.md)) is load-bearing
  again**, not vestigial. Cloud summarization and chat arrive through it; Azure AI Foundry
  is the expected provider behind it.
- **[ADR-0012](0012-full-standalone-local-summarization.md) is no longer optional.** Local
  Mode promises a summary with no network, so on-device summarization is required scope,
  not a deferred nice-to-have. It moves off "Deferred".
- **An HTTP client returns to the Android app** — but gated, versioned, and dead code
  while the flag is off. Per the repo's contract rule, its request/response types are
  generated from the API contract, not hand-written to match it. The previous hand-written
  client is not resurrected.
- **`Upload` returns to the domain as a Cloud-Mode-only concept**, and it carries audio.
- **A `Privacy.md` and a Play Data Safety declaration are prerequisites for shipping Cloud
  Mode**, not for building it. Transmitting recorded audio is a declarable data collection
  under Play policy, and a privacy policy URL is required at submission. The content of
  that policy is deliberately not designed yet (2026-08-30) — the decision was to build
  the capability first. It is a release gate on slice 17, tracked there, and nothing
  earlier depends on it.
- **`README.md` is wrong today** and must be rewritten: "Capture from either client" no
  longer describes the product, and the Ollama/Whisper setup steps are harness setup, not
  user setup.
- Two summarizers and two transcribers will exist. Output must be attributable to the
  engine that produced it, or evaluation and bug reports become meaningless.

## Alternatives considered
- **Cloud required** (the pre-ADR-0011 shape). Rejected: reinstates the mandatory-backend
  friction the on-device pivot exists to remove, and makes the headline feature depend on
  a process the User has to run.
- **Cloud never** — permanently local-only, delete the backend. Rejected: caps summary
  quality at whatever fits in ~1 GB of phone RAM, with no escape hatch for users who want
  better, and throws away the only ground-truth generator available for evaluation.
- **Automatic hybrid** — run locally, silently fall back to cloud when the device is too
  slow or the result too poor. Rejected: audio or transcript leaving the device is not a
  performance optimization the app gets to make on the User's behalf. It must be a choice
  the User made and can see.
- **Cloud flag with an unversioned API** (current `/sessions` endpoints as-is). Rejected:
  an installed APK cannot be redeployed in lockstep with the backend, so an unversioned
  contract breaks users in the field on the first change.

## Related
[ADR-0002](0002-ichatclient-provider-seam.md), [ADR-0008](0008-local-whisper-first.md),
[ADR-0011](0011-on-device-transcription.md), [ADR-0012](0012-full-standalone-local-summarization.md)
