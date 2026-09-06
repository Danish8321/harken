# ARC-023 — The instrumented tests are in no gate

- **Severity:** medium
- **Status:** open
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
