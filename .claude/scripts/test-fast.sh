#!/usr/bin/env bash
# Fast test gate: build + run unit/integration tests (excludes anything tagged Manual/E2E).
set -euo pipefail
cd "$(dirname "$0")/../.."
echo "== test-fast: dotnet test =="
# No test projects yet -> dotnet test on the solution is a no-op success.
dotnet test Harken.sln --nologo --filter "Category!=Manual&Category!=E2E"
echo "== test-fast: OK =="
