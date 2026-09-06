# ARC-024 — Half the repository is a backend nothing calls

- **Severity:** medium
- **Status:** open
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
