#!/usr/bin/env bash
# Full build gate: every project must compile with no errors.
set -euo pipefail
cd "$(dirname "$0")/../.."
echo "== check: dotnet build =="
dotnet build Harken.sln --nologo -warnaserror
echo "== check: OK =="
