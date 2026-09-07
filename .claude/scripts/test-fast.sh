#!/usr/bin/env bash
# Fast test gate: build + run unit/integration tests (excludes anything tagged Manual/E2E).
set -euo pipefail
# Sourced before the cd, while "$0" still points where the caller found it.
# shellcheck source=_gradle.sh
. "$(dirname "$0")/_gradle.sh"
cd "$(dirname "$0")/../.."
# testDebugUnitTest runs JVM-only tests (src/test/kotlin). Instrumented tests
# (src/androidTest/kotlin) need an emulator/device, so test-full.sh runs those and this
# gate does not — documented in docs/onboarding.md as a manual/on-device step.
echo "== test-fast: gradle testDebugUnitTest (Harken.Android) =="
(cd src/Harken.Android && "$GRADLEW" testDebugUnitTest)

echo "== test-fast: OK =="
