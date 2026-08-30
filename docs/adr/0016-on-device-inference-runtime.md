# ADR-0016: One native inference runtime — llama.cpp on a shared ggml

## Status
Accepted — 2026-08-30

## Context
[ADR-0014](0014-local-first-with-optional-cloud-mode.md) makes Local Mode the whole
product on its own, which makes on-device summarization required scope rather than the
deferred option [ADR-0012](0012-full-standalone-local-summarization.md) recorded. So a
second model — an LLM — has to run on the device alongside Whisper, and something has to
run it.

Two runtimes were on the table. A spike of the second one (MediaPipe `tasks-genai` with
Gemma-3n E4B) was written before this decision was taken; it is superseded by this ADR.

Three constraints, none of them about silicon, decided it:

1. **Model licensing gates the download.** `ModelDownloadManager` fetches the Whisper model
   anonymously, with no account and no terms to accept. Gemma weights are acceptance-gated
   on Kaggle and Hugging Face; the spike's own comment concedes the workaround is
   `adb push`. A summarizer the user cannot obtain without creating an account and
   accepting a licence breaks the install-and-go promise ADR-0011 exists to keep.
2. **Two ggml copies in one process.** `app/src/main/cpp/CMakeLists.txt` builds `ggml` as a
   STATIC library from whisper.cpp v1.7.6 (`a8d002c`) and links it into
   `libharken_whisper_jni.so`. llama.cpp vendors its own ggml at a different version.
   Loading both leaves two sets of ggml symbols in one process for the dynamic linker to
   choose between, and the loser runs against a version it was not compiled for. That is
   the same class of fault as the still-unexplained `ggml_vec_dot_f16` SIGSEGV.
3. **Hosting caps the model size.** GitHub release assets are capped at 2 GB each. A
   4B-parameter Q4_K_M GGUF is larger than that; Gemma-3n E4B's `.task` bundle is larger
   still. The current hosting story cannot carry a summarizer at all.

## Decision
**One native inference runtime: llama.cpp, linked against the same ggml as whisper.cpp.**

- whisper.cpp is bumped to a release whose vendored ggml matches the chosen llama.cpp
  release. `ggml` becomes a single static target that both `whisper` and `llama` link
  against, inside a single JNI shared library. Two ggml copies never exist.
- The local summarizer is an **Apache-2.0 model in GGUF** — the Qwen3 instruct family is
  the candidate — so the weights can be re-hosted with no gate, no account, and no
  attribution obligations.
- **Model size is not decided here.** 1.7B and 4B class are benchmarked on the reference
  device (ADR-0015) and the numbers choose. Size is a URL and a prompt template, not an
  architecture commitment, and there is currently no measurement on supported hardware to
  decide it from.
- **Model hosting moves to a project-owned Hugging Face repository**, for both Whisper and
  the LLM. Free, anonymous, no size cap, one hosting story instead of two.
- **CPU is the correctness backend.** Any GPU path (Vulkan, OpenCL) is a later
  optimization behind a fallback, never on the correctness path.

## Alternatives considered
- **MediaPipe `tasks-genai` + Gemma-3n E4B** (the spike). Buys a working Adreno GPU
  delegate and avoids the ggml collision outright. Rejected on the licence gate: there is
  no anonymous download URL, so first run would require the user to authenticate and
  accept Google's terms. It also means owning a second native inference stack, with its
  own crash surface and upgrade cadence, while a native memory fault in the first one is
  still unexplained.
- **Two separate `.so` files, each statically linking its own ggml.** Rejected: it does not
  actually isolate the symbol sets, it just makes the collision harder to see. Symbol
  interposition would surface as a corrupted-tensor crash with no obvious cause — the
  worst possible failure mode given the open SIGSEGV.
- **ONNX Runtime or ExecuTorch.** Rejected: a third ecosystem, a model conversion pipeline
  to own, and no existing expertise in the project. Nothing about the requirement needs
  them.
- **No local summarizer — Cloud Mode only.** Rejected: contradicts ADR-0014's binding rule
  that Local Mode is the whole product on its own.

## Consequences
- **A whisper.cpp version bump is forced**, and it is not cosmetic — the JNI bridge and the
  CMake target list must be reconciled with the newer ggml source layout. Transcription
  regressing is the main risk of this ADR, so the bump ships with transcription still
  passing its gate before llama is added at all.
- One JNI library, one native build, one set of ABI and toolchain concerns.
- Swapping the local summarizer later costs a URL, a prompt template and a benchmark run.
- Prompt templates become model-specific and must live somewhere versioned alongside the
  model choice, not inlined in a screen.
- The Whisper model's download URL changes, so the existing `ModelDownloadManager`
  constant and any published release asset are superseded.
- Every Transcript and Summary must record which engine produced it (ADR-0014), and the
  local engine's identity now includes a model name and quantization, not just "on-device".
- The MediaPipe spike files and the `com.google.mediapipe:tasks-genai` dependency are
  removed rather than kept behind a flag.

## Related
[ADR-0011](0011-on-device-transcription.md), [ADR-0012](0012-full-standalone-local-summarization.md),
[ADR-0014](0014-local-first-with-optional-cloud-mode.md), [ADR-0015](0015-target-device-floor.md)
