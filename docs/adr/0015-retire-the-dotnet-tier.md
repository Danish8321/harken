# ADR-0015: Retire the .NET tier

## Status
Accepted — 2026-09-07. **Option A.** The maintainer chose deletion over keeping the
tier behind its own script (B) or rewriting the console diagnostic against Whisper.net
and Ollama directly (C).

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

Three options were put, with what each costs. **Option A was chosen.**

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

Done when this ADR was accepted:

- `check.sh` and `test-fast.sh` lost their `dotnet` steps and are Android-only. The
  verification contract in `CLAUDE.md` still names scripts that no longer run .NET; the
  script names are unchanged, so the contract still holds, but its wording is stale.
- The CI workflow lost its `actions/setup-dotnet` step, and with it the only reader of
  `global.json`.
- `Harken.slnx`, `global.json`, `Directory.Build.props` and `Directory.Packages.props`
  went with the projects — all four existed only to build them.
- `docs/onboarding.md` §3–§4 were replaced by a phone-first bring-up. `README.md`,
  `docs/setup.md` and `CONTEXT.md` went further than this ADR asked: each still described
  a client that uploads to a server, an account model, and a summarizer, none of which
  survived ADR-0009, ADR-0011 or ADR-0012. Deleting the tier without correcting them
  would have left the repository's front door instructing a reader to run a project that
  is not there.
- ADR-0001 through ADR-0010 stay as written. They record decisions that were true when
  made; superseding them wholesale would be rewriting history rather than adding to it.
  `docs/plans/slice-*.md` stay for the same reason.

Not done, and known:

- The three `src/Harken.*` directories and `tests/` still hold untracked build output and
  a developer's `harken.db` and test recordings. Git no longer tracks a byte of them.
- `Harken.Core/Audio/WavWriter.cs` is gone, so `audio/WavWriter.kt` is now the only
  implementation of that 44-byte header — which was the point.
