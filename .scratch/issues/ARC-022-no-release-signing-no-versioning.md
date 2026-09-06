# ARC-022 — The release build cannot be released

- **Severity:** medium
- **Status:** open
- **Area:** `src/Harken.Android/app/build.gradle.kts`

## Problem

- No `signingConfigs` block. `assembleRelease` produces an unsigned APK; the
  build gate has therefore never exercised a signed artifact, and R8/resource
  shrinking has never been verified against one.
- `versionCode = 1`, `versionName = "1.0"`, with no strategy for either. Every
  build the user has installed reports itself as the same version, so a bug
  report cannot be tied to a build and the telemetry cannot either.

ADR-0014 assumes a Play Console listing exists. Neither of these is optional
for one.

## Fix

A `signingConfigs.release` reading from `keystore.properties` (gitignored, with
a committed `.example`), and a `versionCode`/`versionName` derived from the git
describe output or an explicit `version.properties`. Emit the version in the
launch telemetry event alongside `device_capability`.
