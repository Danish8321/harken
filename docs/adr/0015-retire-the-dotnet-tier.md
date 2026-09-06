# ADR-0015: Retire the .NET tier

## Status
Proposed

## Context

[ADR-0011](0011-on-device-transcription.md) moved the default transcription path onto the
phone, and [ADR-0012](0012-full-standalone-local-summarization.md) did the same for
summaries. Both were accepted. What neither did was say what happens to the server they
made optional.

What is still in the repository, and still built and tested by every gate:

| Project | Lines of C# |
|---|---|
| `src/Harken.Api` | 3,459 |
| `src/Harken.Core` | 303 |
| `src/Harken.Console` | 241 |
| `tests/Harken.Api.IntegrationTests` | 955 |
| `tests/Harken.Core.UnitTests` | 256 |
| **Total** | **5,214** |

The Android client contains no Retrofit, Ktor, OkHttp or `HttpURLConnection` call to any
of it. The only URL in the whole client is the GitHub release the Whisper model is
downloaded from. Retrofit and its Gson converter were removed outright when R8 was
enabled, because nothing had imported `retrofit2` since ADR-0011.

Two consequences are already visible in the code:

- `Harken.Core/Audio/WavWriter.cs` and `audio/WavWriter.kt` are two implementations of the
  same 44-byte header, maintained in parallel. Only the Kotlin one has ever run on a
  user's device.
- `check.sh` builds all three projects and `test-fast.sh` runs 46 of their tests on every
  gate. The dead tier is the slowest part of the loop to feel and the easiest to keep
  believing in.

Against that, the .NET tier is not *entirely* unexercised. `docs/onboarding.md` §3 and §4
tell a new contributor to run `Harken.Api` and then `Harken.Console` to prove that
Whisper, the model file, Ollama and Gemma are wired up **before** touching a phone —
deliberately, to isolate pipeline problems from mobile problems. That is a real use, by a
real reader, today. It is a developer diagnostic, not a product surface, and it is the
only argument for keeping any of this.

YAGNI applies retroactively: this is code kept for a use that has been formally decided
against. But "delete it" and "it is useless" are different claims, and only the first one
is true.

## Decision

**Proposed, not yet accepted — this one is the maintainer's call.** Three options,
with what each costs.

### Option A — Delete the .NET tier

Remove `Harken.Api`, `Harken.Core`, `Harken.Console` and both test projects; drop the
`dotnet build` and `dotnet test` steps from `check.sh` and `test-fast.sh`; rewrite
onboarding §3–§4 around `installDebug` and a phone.

- **Gains:** the gates get faster and stop asserting things about code no user runs. One
  `WavWriter`. The repository stops describing an architecture the app does not have.
- **Costs:** the desktop bring-up path goes with it. A contributor whose first
  transcription fails on the phone loses the step that told them whether the problem was
  the model or the app. Git history keeps the code, so this is reversible in the sense
  that anything committed is.

### Option B — Keep it, and say what it is for

Add a line to the README and to each project naming it a reference implementation and a
desktop bring-up harness, not a backend. Take it out of `check.sh` and `test-fast.sh` and
put it behind its own script, so the per-change gate stops paying for it.

- **Gains:** the onboarding path survives; the fast loop gets its time back.
- **Costs:** two `WavWriter`s stay, and a tier outside the gate rots — which is how the
  lint/AGP mismatch in ARC-020 got in.

### Option C — Keep only the parts the diagnostic needs

Keep `Harken.Console` and whatever of `Harken.Core` it calls; delete `Harken.Api` and its
955 lines of integration tests, which are the bulk of the tier and the part with no reader
at all now that the console client has no server to talk to.

- **Gains:** the bring-up path survives in a form that matches what it is actually used
  for. Roughly 4,400 of the 5,214 lines go.
- **Costs:** `Harken.Console` currently talks HTTP to `Harken.Api` for everything —
  recording is local but transcription and summary are `POST`s. Keeping it without the API
  means rewriting it against Whisper.net and Ollama directly. That is real work, not a
  deletion.

**Recommendation: Option A.** The bring-up path it protects is worth less than it looks:
the desktop pipeline is Whisper.net and Ollama, while the phone runs a JNI build of
whisper.cpp and no summarizer at all, so a green desktop run does not actually clear the
phone's dependencies. It answers a question about a stack the product no longer ships.
Option C's rewrite would buy a diagnostic that has the same flaw, and Option B keeps two
`WavWriter`s alive for it.

## Consequences

If Option A is accepted:

- `check.sh` and `test-fast.sh` lose their `dotnet` steps and become Android-only. The
  verification contract in `CLAUDE.md` should say so.
- `docs/onboarding.md` §3–§4 are replaced by a phone-first bring-up, with the model
  download and a first recording as the proof.
- ADR-0001 through ADR-0010 stay as written. They record decisions that were true when
  made; superseding them wholesale would be rewriting history rather than adding to it.
- `Harken.slnx` goes, and with it the last thing in the repository that implies a server.
