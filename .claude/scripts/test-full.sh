#!/usr/bin/env bash
# On-device test gate: everything test-fast.sh runs, plus the instrumented tests.
#
# The tier that did not exist (ARC-023). SessionDatabaseMigrationTest asserts the one
# thing in this repository that can destroy a user's data — that a migration is additive
# and preserves the rows already there — and it ran only if a human remembered to type
# connectedAndroidTest with a phone plugged in. A schema change must pass this gate.
#
# Needs a device or a running emulator. There is no fallback and no skip: a gate that
# quietly passes when it could not run is worse than no gate, because its OK is then
# evidence of nothing.
set -euo pipefail
cd "$(dirname "$0")/../.."

echo "== test-full: adb devices =="
if ! command -v adb >/dev/null 2>&1; then
  echo "adb is not on PATH. Install platform-tools, or add it, then re-run." >&2
  exit 1
fi
# "device" exactly: an "unauthorized" or "offline" line is a phone that is plugged in and
# cannot run anything, which is the failure this check exists to name.
attached=$(adb devices | awk '$2 == "device" { count++ } END { print count + 0 }')
if [ "$attached" -eq 0 ]; then
  echo "No device or emulator is attached. 'adb devices' must list one as 'device'." >&2
  adb devices >&2
  exit 1
fi
echo "$attached device(s) attached"

"$(dirname "$0")/test-fast.sh"

# Uninstalls first, both variants: an instrumented run against a database left over from
# a previous install is testing that install's state, not this build's. The app is
# deliberately not backed up (ARC-002), so there is nothing to restore and nothing to
# lose that the device did not already have.
echo "== test-full: uninstall any previous build =="
adb uninstall com.harken.android.debug >/dev/null 2>&1 || true
adb uninstall com.harken.android.debug.test >/dev/null 2>&1 || true

echo "== test-full: gradle connectedDebugAndroidTest (Harken.Android) =="
(cd src/Harken.Android && ./gradlew.bat connectedDebugAndroidTest)

echo "== test-full: OK =="
