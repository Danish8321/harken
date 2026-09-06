# ARC-021 — Dependency versions are string literals scattered through the build file

- **Severity:** medium
- **Status:** fixed
- **Area:** `src/Harken.Android/app/build.gradle.kts`

## Problem

There is no `gradle/libs.versions.toml`. Every coordinate is written inline,
including the two that are load-bearing and non-obvious: `material3` pinned to
`1.4.0` *above* the Compose BOM `2025.09.00` (deliberately, for Material 3
Expressive) and Room `2.7.1` matched to the KSP plugin version. Those two facts
live in comments in the middle of a dependency block.

A version catalog is the platform's own answer, needs no new dependency, and
makes "which artifacts move together" a visible property of the file rather
than something to be reconstructed by reading.

## Fix

`gradle/libs.versions.toml` with the version groups named
(`compose-bom`, `material3`, `room`, `kotlin`), and `build.gradle.kts`
referring to `libs.*`.

## Resolution

`src/Harken.Android/gradle/libs.versions.toml`, with both build files on `libs.*` and
`alias(...)` for the plugins.

The two load-bearing facts are now properties of the file rather than comments inside a
dependency list: `material3` is the only Compose artifact carrying its own version, right
under the note saying why it sits above the BOM; and `room` is documented as the thing
that ties itself to `ksp`, which ties itself to `kotlin`.

Added a `[bundles] compose` for the five artifacts every screen needs, so a module cannot
pick up half of them and find the rest at the first red squiggle.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==` (debug, release and lint all resolve
  through the catalog)
- `.claude/scripts/test-fast.sh` — `== test-fast: OK ==`

No version changed. This is the same dependency graph, written down once.
