# ADR-0012: Full standalone — on-device summarization (Phase 2, not yet started)

## Status
Proposed — deferred, not scheduled

## Context
[ADR-0011](0011-on-device-transcription.md) (Phase 1) moves default transcription and
session storage fully on-device, but leaves summarization backend-only: a session with
no configured backend has a transcript but no summary, and the Summarize action is
hidden rather than shown-and-broken.

This ADR records the shape of the further step — on-device summarization — as a Phase 2
option, deliberately not committed to in this slice. It exists so the decision isn't
re-litigated from scratch later, not as approved scope.

## Decision (proposed, not started)
Add on-device summarization as an alternative to the existing `SummarizeAgent`/
`IChatClient` backend path:
- A small on-device LLM (e.g. Gemma via MediaPipe LLM Inference, or a GGUF model via
  llama.cpp) running in the Android app, invoked the same way Whisper runs on-device per
  ADR-0011.
- Reuses the local Room session store from ADR-0011 to persist the generated summary
  alongside the local transcript.
- Cloud summarization via the existing backend `IChatClient` seam remains available as
  an opt-in alternative, same as cloud transcription is opt-in today.

## Why this is deferred, not decided
Raised and priced during ADR-0011's grilling session (2026-08-25):
- Scope is materially larger than Phase 1: local DB schema, a model-selection and
  quality-evaluation pass (on-device LLM output quality is unproven for this use case),
  and UI branching between local-only and backend-connected modes. Rough estimate: 2–3
  weeks equivalent effort vs. Phase 1's 2–4 days, with the biggest unknown being model
  quality/device RAM requirements, not implementation mechanics.
- No cash cost either way (on-device LLM licensing is free; cloud summarization already
  has a working, priced path) — the blocker is engineering time and unproven quality,
  not budget.

## Alternatives considered
- Ship Phase 1 and Phase 2 together. Rejected: bundling would delay the free, zero-cost
  transcription win behind an unproven on-device LLM integration.

## Consequences
- Until this is implemented, backend-less users get transcripts only, no AI summaries —
  an accepted, explicit gap per ADR-0011.
- Revisit this ADR (move to Accepted) once Phase 1 has shipped and there's appetite to
  spend the larger estimated effort, or once a concrete on-device model is validated as
  good enough on real hardware.

## Related
[ADR-0011](0011-on-device-transcription.md)
