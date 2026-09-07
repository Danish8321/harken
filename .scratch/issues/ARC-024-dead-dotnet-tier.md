# ARC-024 — Half the repository is a backend nothing calls

- **Severity:** medium
- **Status:** done
- **Area:** `src/Harken.Api`, `src/Harken.Core`, `src/Harken.Console`

## Problem

5,214 lines of C# across three projects. The Android app contains no Retrofit,
Ktor, OkHttp or `HttpURLConnection` call to any of it — the only URL in the
whole client is the GitHub model release
([ADR-0011](../../docs/adr/0011-on-device-transcription.md) moved
transcription on-device and the client-side networking went with it).

`Harken.Core/Audio/WavWriter.cs` and `audio/WavWriter.kt` are two
implementations of the same 44-byte header, maintained in parallel, only one of
which any user has ever executed.

Meanwhile `check.sh` builds all three projects and `test-fast.sh` runs 46 of
their tests on every gate — so the dead tier is the part of the build that is
slowest to feel and easiest to keep believing in.

YAGNI applies retroactively: this is code kept for a use that has been formally
decided against.

## Fix

Decide explicitly, in an ADR: delete the .NET tier, or state what it is for and
why the gates should keep paying for it. If it is kept as a reference
implementation, it comes out of `check.sh` and `test-fast.sh`.

## Progress

[ADR-0015](../../docs/adr/0015-retire-the-dotnet-tier.md) written, status **Proposed**.
It lays out three options — delete the tier, keep it out of the per-change gate with a
stated purpose, or keep only the console diagnostic — with what each costs, and
recommends deletion.

One thing the ticket had wrong: the tier is not entirely unexercised. `docs/onboarding.md`
§3–§4 tell a new contributor to run `Harken.Api` and `Harken.Console` to prove Whisper,
the model, Ollama and Gemma are wired before touching a phone. That is a real reader
today. The ADR argues the check is worth less than it looks — the desktop path is
Whisper.net and Ollama, the phone is a JNI build of whisper.cpp with no summarizer, so a
green desktop run does not clear the phone's dependencies — but "unused" was not accurate
and the ADR says so.

## Resolution

ADR-0015 **accepted, Option A**, 2026-09-07. Deleted:

- `src/Harken.Api`, `src/Harken.Core`, `src/Harken.Console`,
  `tests/Harken.Api.IntegrationTests`, `tests/Harken.Core.UnitTests` — 5,457 lines
  including the project files.
- `Harken.slnx`, `global.json`, `Directory.Build.props`, `Directory.Packages.props`.
  All four existed only to build the above; nothing in `src/Harken.Android` reads any of
  them.
- The `dotnet build` step from `check.sh`, the `dotnet test` step from `test-fast.sh`,
  and `actions/setup-dotnet` from `.github/workflows/gates.yml`, which was `global.json`'s
  only other reader.

`audio/WavWriter.kt` is now the only implementation of that header.

The docs went further than the ADR asked. `README.md`, `docs/setup.md` and `CONTEXT.md`
still described accounts, an upload step, a polling client and a summarizer — none of
which survived ADR-0009, ADR-0011 or ADR-0012, and none of which was about the .NET tier
at all. Deleting the projects while leaving the front page telling a reader to
`dotnet run --project src/Harken.Api` would have swapped one wrong description for a
worse one, so all four docs were rewritten around the app that actually ships.
`docs/plans/slice-*.md` and ADR-0001–0010 were left as written: they record what was
true when made.

## Left behind

The three `src/Harken.*` directories and `tests/` still exist on disk holding untracked
`bin/`, `obj/`, a developer's `harken.db` and six test recordings. Git tracks none of it.
Removing them is one `rm -rf` the tooling here would not run.
