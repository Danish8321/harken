# ADR-0012: Full standalone — on-device summarization

## Status
Accepted, but decision 6 (provider choice + cloud fallback) is now **void** — see
**Update (2026-08-28)** below. This revision was grilled on the premise that the Android
app still had a working (if unwired) backend connection to fall back to; that premise no
longer holds after the backend/cloud rip-out (`.scratch/plan-remove-backend-android.md`).
Decisions 1-5 and 7 (runtime, model, seam, download pipeline, persistence, resource
management) are unaffected and stand. **Needs re-grilling before slice-11 implementation
starts** — do not build against decision 6 as written.

## Context
[ADR-0011](0011-on-device-transcription.md) (Phase 1) moved default transcription and
session storage fully on-device, but left summarization backend-only: a session with no
configured backend has a transcript but no summary, and the Summarize action is hidden
rather than shown-and-broken.

This ADR was originally recorded as a deferred Phase 2 placeholder (2026-08-25). Phase 1
has since shipped (slice-09). This revision (grilled 2026-08-28) resolves the open
questions and commits to Phase 2 as slice-11.

## Decision
Add on-device summarization, available to every session (all sessions are local-only
now that the backend/cloud path is removed from the Android app — see Update below).

1. **Runtime**: llama.cpp, vendored the same way as whisper.cpp (ADR-0011) — the two
   share the `ggml` core already in this repo, so the existing NDK/CMake/JNI scaffolding
   is extended rather than duplicated. MediaPipe LLM Inference and ExecuTorch were
   considered and rejected as a *second* native inference stack for one already covered:
   different model format (not GGUF), no shared code with the vendored whisper build.
2. **First model**: Llama 3.2 3B Instruct, Q4_K_M GGUF (~1.8GB), self-hosted on this
   repo's `models-v1` GitHub Releases asset — same hosting pattern as the whisper model,
   chosen over Hugging Face direct-linking for URL stability and a checksum that never
   moves under us. Requires shipping the Llama 3.2 Community License text and a "Built
   with Llama" attribution in-app (new Settings "Legal/About" section; whisper.cpp's MIT
   notice is added in the same place as a bundled fix to an existing gap).
3. **Seam**: a generic `Summarizer` interface, named and shaped by role
   (`OnDeviceSummarizer`, `ModelCatalog.SummarizationModel`) rather than by the specific
   model — swapping to Phi-4 Mini or another GGUF later is a `ModelSpec` value change,
   not a rename. Mirrors `OnDeviceTranscriber`'s load-once/reuse-handle pattern.
4. **Download pipeline generalized**: `ModelDownloadManager` takes a `ModelSpec`
   (fileName, url, sizeBytes, sha256) instead of hardcoded constants, since there are now
   two models with different reliability needs. At 1.8GB, added: SHA-256 verification
   before the `.tmp` → final rename, HTTP Range resume instead of restart-on-failure, a
   WorkManager foreground job (new dependency: `work-runtime-ktx`) so the download
   survives backgrounding, and a Wi-Fi-only default with a Settings override. Whisper's
   existing 142MB download path is untouched.
5. **Persistence**: no schema change. The `summaries` Room table (ADR-0010) already
   matches `LocalSummary`'s shape (`sessionId`, `summary: String`, `generatedAt`) because
   it was built as a full local mirror of the backend contract, not backend-only. Saving
   an on-device summary is a plain insert plus flipping `SessionRow.hasSummary = true` in
   the same transaction.
6. ~~**Provider selection is explicit and user-facing**...a `SummarizationProviderChoice`
   (OnDevice/Cloud) setting...~~ **Void.** There is no cloud path to choose or fall back
   to any more. The Summarize button always resolves on-device; tapping it with the
   model not yet downloaded routes to a download prompt rather than being hidden or
   erroring (consistent with ADR-0011's "never present a button guaranteed to error").
7. **Resource management**: `OnDeviceTranscriber`'s whisper model handle is force-released
   before an on-device summarization loads the 3B model — transcription is already
   complete by the time Summarize is tapped, and freeing ~200-400MB first is cheap
   insurance against the combined peak on a 6GB device.

## Why the original deferral was correct
Raised and priced during ADR-0011's grilling session (2026-08-25): scope is materially
larger than Phase 1 (local DB schema check, a model-selection pass, UI branching between
local-only and backend-connected modes), with the biggest unknown being model
quality/device RAM, not implementation mechanics. Shipping Phase 1 alone first (rather
than bundled) meant the free, zero-cost transcription win wasn't delayed behind an
unproven LLM integration — and let this revision benefit from Phase 1's actual shipped
shape (e.g. discovering `summaries` already needs no migration) rather than guessing at
it upfront.

## Alternatives considered
- **Ship Phase 1 and Phase 2 together.** Rejected at the time, correctly — see above.
- **Smaller model first (Qwen 2.5 1.5B)** to de-risk the 1.8GB download before
  committing to 3B. Considered during this revision's grilling; rejected in favor of
  going straight to Llama 3.2 3B for stronger summary quality, accepting the download
  pipeline hardening (Section 4 above) as the real risk to manage directly instead of
  sidestepping via a smaller model.
- **Second inference runtime (MediaPipe/ExecuTorch)** for flexibility. Rejected: the
  `Summarizer` seam already makes a second implementation cheap to add later if needed;
  committing to one now (llama.cpp) avoids maintaining two native dependency trees for a
  need that isn't proven yet.
- ~~**Silent fallback from cloud to on-device** on network failure.~~ Moot — see Update
  below, there is no cloud path left to fall back from.

## Consequences
- Every session can be summarized, at the cost of a second vendored native inference
  stack and a large (~1.8GB) optional download.
- Model choice (Llama 3.2 3B today) is swappable later without touching the seam, by
  design — but only one model ships in this slice; a model-selection toggle (e.g. Phi-4
  Mini for structured output) is explicitly out of scope here.
- Prerequisite: slice-09 must be merged to `master` first, including its own follow-up
  of replacing the temporary Hugging Face `MODEL_DOWNLOAD_URL` with a real `models-v1`
  GitHub Release asset — slice-11's second model asset lands in that same release.

## Update (2026-08-28)
Decision 6 is void: the Android app's backend/cloud transcription and summarize path
was ripped out entirely (`.scratch/plan-remove-backend-android.md`) — there is now no
`SummarizationProviderChoice`, no cloud call, no confirm-prompt fallback to design.
Slice-11 becomes simpler than this ADR as written: on-device summarization is the
*only* path, full stop, same shape as ADR-0011's transcription decision. The
`docs/plans/slice-11-on-device-summarization.md` plan's Task 5/6 need re-scoping to
drop provider-choice UI and cloud-failure handling before implementation starts.

## Related
[ADR-0011](0011-on-device-transcription.md)
