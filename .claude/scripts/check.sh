#!/usr/bin/env bash
# Full build gate: every project must compile with no errors.
set -euo pipefail
cd "$(dirname "$0")/../.."
echo "== check: dotnet build =="
dotnet build Harken.slnx --nologo -warnaserror

echo "== check: gradle assembleDebug (Harken.Android) =="
(cd src/Harken.Android && ./gradlew.bat assembleDebug)

echo "== check: OK =="
