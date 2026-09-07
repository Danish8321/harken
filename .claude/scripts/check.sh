#!/usr/bin/env bash
# Full build gate: the app must compile with no errors. Android-only since ADR-0015
# retired the .NET tier.
set -euo pipefail
# Sourced before the cd, while "$0" still points where the caller found it.
# shellcheck source=_gradle.sh
. "$(dirname "$0")/_gradle.sh"
cd "$(dirname "$0")/../.."
# Formatting first: it is the fastest task here and the one most likely to fail, so a
# badly-formatted change is rejected in seconds rather than after a native build. ktlint's
# official Kotlin style, with two settings stated in src/Harken.Android/.editorconfig and
# the reasons beside them (ARC-037). `gradlew ktlintFormat` is the fix.
echo "== check: gradle ktlintCheck (Harken.Android) =="
(cd src/Harken.Android && "$GRADLEW" ktlintCheck)

echo "== check: gradle assembleDebug (Harken.Android) =="
(cd src/Harken.Android && "$GRADLEW" assembleDebug)

# The instrumented tests need a device to *run*, so no gate here runs them. Nothing
# compiled them either, which meant a refactor could break them and the break stayed
# invisible until someone plugged in a phone weeks later. Assembling the test APK needs no
# device and catches that, plus a broken test manifest and any asset the tests read.
echo "== check: gradle assembleDebugAndroidTest (Harken.Android) =="
(cd src/Harken.Android && "$GRADLEW" assembleDebugAndroidTest)

# The release variant is the only one that runs lintVital, and it went unbuilt long enough
# for a lint/AGP version mismatch to break it unnoticed. Gated here so it cannot rot again.
echo "== check: gradle assembleRelease (Harken.Android) =="
(cd src/Harken.Android && "$GRADLEW" assembleRelease)

# assembleRelease runs lintVital, which is the fatal-only subset. The full Lint pass is a
# separate task and had never been run: it is what would have reported the missing
# launcher icon, the unqualified allowBackup and the unneeded usesCleartextTraffic on the
# day each was written (ARC-001, ARC-002, ARC-004, ARC-020). abortOnError is on in the
# build file, so a new finding fails this gate rather than printing into the scroll.
echo "== check: gradle lint (Harken.Android) =="
(cd src/Harken.Android && "$GRADLEW" lintDebug)

echo "== check: OK =="
