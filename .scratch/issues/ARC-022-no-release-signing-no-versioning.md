# ARC-022 — The release build cannot be released

- **Severity:** medium
- **Status:** fixed
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

## Resolution

**Signing.** `signingConfigs.release` is created only when `keystore.properties` exists
(gitignored; `keystore.properties.example` is committed). Without it `assembleRelease`
still runs, still exercises R8 and resource shrinking, and produces an unsigned APK —
which keeps `check.sh` working on every machine rather than only the one that publishes.
Refusing to build without a key would have put the release gate out of reach.

**Versioning.** `versionCode` is the git commit count and `versionName` comes from
`version.properties`. The commit count rather than a hand-edited number because a
hand-edited number is a number nobody edits, and Play refuses an upload whose versionCode
has not risen; `versionCodeFallback` covers a build from a source archive with no history.

**Identity in the log.** `BuildConfig.GIT_SHA` is set from `git rev-parse --short HEAD`
(`buildConfig = true`, off by default since AGP 8), and the launch `device_capability`
event now carries `versionName`, `versionCode` and `gitSha`. That is the actual point of
the ticket: a log excerpt from a user can now be tied to the code that produced it.

## Evidence

- `.claude/scripts/check.sh` — `== check: OK ==`
- `app/build/outputs/apk/release/output-metadata.json` — `"versionCode": 171`,
  `"versionName": "0.1.0"`, where 171 is `git rev-list --count HEAD`
- Debug `BuildConfig.java` — `VERSION_CODE = 171`, `VERSION_NAME = "0.1.0-debug"`,
  `GIT_SHA = "b1b5f6b"`

## Not done

The release APK on this machine is still unsigned, because there is no keystore here. The
signing path is written and gitignored but has never produced a signed artifact; that
needs a key and one `assembleRelease`.
