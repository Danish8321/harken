#!/usr/bin/env bash
# Fast test gate: build + run unit/integration tests (excludes anything tagged Manual/E2E).
set -euo pipefail
cd "$(dirname "$0")/../.."
echo "== test-fast: dotnet test =="
dotnet test Harken.slnx --nologo --filter "Category!=Manual&Category!=E2E"

# testDebugUnitTest runs JVM-only tests (src/test/kotlin). Instrumented tests
# (src/androidTest/kotlin, tagged separately) need an emulator/device and are excluded
# here — same Category!=Manual&Category!=E2E convention as the .NET side, documented in
# docs/onboarding.md as a manual/on-device step.
echo "== test-fast: gradle testDebugUnitTest (Harken.Android) =="
(cd src/Harken.Android && ./gradlew.bat testDebugUnitTest)

echo "== test-fast: OK =="
