# ARC-020 — Nothing checks style, lint or correctness except a compiler

- **Severity:** medium
- **Status:** closed
- **Area:** `.claude/scripts/`, repository root

## Problem

`check.sh` runs `dotnet build`, `assembleDebug`, `assembleRelease`.
`test-fast.sh` runs the .NET and JVM unit tests. That is the whole gate.

Absent:

- **Android Lint.** `./gradlew lint` is never invoked. Every finding in
  ARC-001 (missing launcher icon), ARC-002 (`allowBackup`) and ARC-004
  (cleartext) is a Lint check that ships in the toolchain and would have
  reported them the day they were introduced.
- **ktlint / detekt.** Formatting and complexity are unenforced; the codebase
  is consistent by hand, which does not scale past one author.
- **CI.** There is no `.github/workflows`, no CI configuration of any kind. The
  gates only run when someone remembers to run them.

## Fix

Add `./gradlew lint` to `check.sh` with a baseline for existing findings only
if it is genuinely large, a ktlint or detekt task, and a GitHub Actions
workflow that runs `check.sh` + `test-fast.sh` on push. Adding a Gradle plugin
needs sign-off first (no new dependency without asking) — Lint needs none.

## Resolution (partial — Lint only)

`check.sh` now runs `./gradlew lintDebug` after the two assembles.
`assembleRelease` only ever ran `lintVital`, the fatal-only subset; the full
pass had never been run.

Its first run found **4 errors and 76 warnings**, and three of the four were
real defects, not style:

- `NewApi` ×2 — `dynamicDarkColorScheme` / `dynamicLightColorScheme` called
  unguarded at minSdk 26, reachable from a live Settings switch. See ARC-036.
- `UnusedContentLambdaTargetStateParameter` — the tab transition animated each
  screen against itself. See ARC-035.
- `PropertyEscape` on `local.properties`. That file is machine-local and
  gitignored, and Gradle finds the SDK from `ANDROID_HOME`/`ANDROID_SDK_ROOT`
  without it, so it was deleted rather than escaped, and
  `src/Harken.Android/.gitignore` now says why for whoever's IDE recreates it.

`DataExtractionRules` and `MissingApplicationIcon` were among the warnings and
are already closed by ARC-002 and ARC-001.

Nothing was suppressed and no baseline file was created: the gate passes because
the causes were fixed.

## Still open

The 76 warnings (35 `Typos`, 16 `GradleDependency`, 11 `UnusedResources`), a
ktlint or detekt pass, and CI. Those need their own tickets and, for a Gradle
plugin, sign-off on a new dependency. Reopened as ARC-037 rather than left
implied here.

## Evidence

`check.sh` (dotnet build, assembleDebug, assembleRelease, and the new lintDebug
step) and `test-fast.sh` (14 + 32 .NET, 92 Android JVM) both pass.

## Device verification

Not applicable — a build gate.

## Status: closed
