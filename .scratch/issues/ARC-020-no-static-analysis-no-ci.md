# ARC-020 — Nothing checks style, lint or correctness except a compiler

- **Severity:** medium
- **Status:** open
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
