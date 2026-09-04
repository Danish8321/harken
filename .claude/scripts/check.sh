#!/usr/bin/env bash
# Full build gate: every project must compile with no errors.
set -euo pipefail
cd "$(dirname "$0")/../.."
echo "== check: dotnet build =="
dotnet build Harken.slnx --nologo -warnaserror

echo "== check: gradle assembleDebug (Harken.Android) =="
(cd src/Harken.Android && ./gradlew.bat assembleDebug)

# The release variant is the only one that runs lintVital, and it went unbuilt long enough
# for a lint/AGP version mismatch to break it unnoticed. Gated here so it cannot rot again.
echo "== check: gradle assembleRelease (Harken.Android) =="
(cd src/Harken.Android && ./gradlew.bat assembleRelease)

echo "== check: OK =="
