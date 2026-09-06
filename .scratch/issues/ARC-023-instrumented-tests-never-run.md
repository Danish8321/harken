# ARC-023 — The instrumented tests are in no gate

- **Severity:** medium
- **Status:** fixed
- **Area:** `.claude/scripts/test-fast.sh`, `app/src/androidTest/`

## Problem

`SessionDatabaseMigrationTest` exists, is well written, and asserts the one
thing in the repository that can destroy user data — that `MIGRATION_1_2` is
additive and preserves existing rows. It runs only if a human types
`connectedAndroidTest` with a device attached, which no script does.

The verification contract names `check.sh`, `test-fast.sh`, `test-full.sh`,
`contract.sh` and `e2e.sh`. Only the first two exist. There is no tier that
runs on-device tests, so "the migration is safe" has never been evidence
produced by a gate.

This becomes urgent the moment ARC-015's rename migration is written.

## Fix

A `test-full.sh` that runs `connectedAndroidTest` against an attached device or
an emulator, documented as the gate a schema change must pass.

## Resolution

`.claude/scripts/test-full.sh`: everything `test-fast.sh` runs, then
`connectedDebugAndroidTest`.

It refuses to run rather than skipping when no device is attached — `adb devices` must
list one as `device` exactly, so an `unauthorized` or `offline` phone is named as the
failure it is. A gate that quietly passes when it could not run is worse than no gate,
because its OK is then evidence of nothing.

Uninstalls the debug build and its test package first: an instrumented run against a
database left over from a previous install is testing that install's state, not this
build's.

Documented in `docs/onboarding.md` §5 as the gate a schema change must pass, which is what
makes it a precondition for ARC-015's rename migration.

## Evidence

- With no device attached: prints `No device or emulator is attached.` and exits 1.
- `.claude/scripts/check.sh` and `test-fast.sh` unaffected — `OK` on both.

## Not done

The gate has never passed, only failed correctly. No device is attached to this machine,
so `connectedDebugAndroidTest` has still not run — `SessionDatabaseMigrationTest` remains
unexecuted evidence. That is now one command away instead of a thing nobody scripted.
