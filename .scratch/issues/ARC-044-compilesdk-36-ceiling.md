# ARC-044 — compileSdk 36 ceiling holds back 18 Lint version notices

- **Severity:** low
- **Status:** open
- **Area:** `app/build.gradle.kts`, `gradle/libs.versions.toml`

## Problem

Lint's `GradleDependency`/`NewerVersionAvailable`/`AndroidGradlePluginVersion`
checks name 18 libraries with a newer release available. Every one of them is
out of reach for the same reason: their 2026 builds ship AAR metadata requiring
`compileSdk 37`, and AGP 8.12 recommends no higher than `compileSdk 36`. Bumping
any one of them individually fails `checkDebugAarMetadata` — this was tried and
reverted while closing [ARC-037](ARC-037-lint-warnings-formatter-and-ci.md).

The ceiling is documented at the top of `gradle/libs.versions.toml`.

## The fix

One job, in order, not eighteen small ones:

1. AGP 8.12 -> 9.x.
2. `compileSdk` 36 -> 37 (and `targetSdk` alongside it if 37 is also the
   latest stable at the time).
3. Re-run the androidx/Kotlin/room/lifecycle/etc. bumps Lint has been naming —
   most should resolve cleanly once the ceiling moves.

Re-run `check.sh` and `test-full.sh` (device required — Room's migration test)
after each step, since AGP major bumps have broken `lintVitalRelease` here
before (see the comment on `agp` in `libs.versions.toml`).

## Deliberately not bundled with ARC-037

ARC-037 closed with these 18 named and this ticket filed, rather than staying
open for a ceiling only a toolchain bump moves — see that ticket's "The version
notices are a ceiling, not a backlog" section.
