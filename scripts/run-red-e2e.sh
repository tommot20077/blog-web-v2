#!/usr/bin/env bash
# run-red-e2e.sh - standard runner for backend red E2E
#
# Run from the backend repo root:
#   ./scripts/run-red-e2e.sh
#   ./scripts/run-red-e2e.sh -Dtest=P0AuthLifecycleRedE2E

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG_FILE="$ROOT/logs/backend-red-e2e.log"

mkdir -p "$(dirname "$LOG_FILE")"

cd "$ROOT"
./mvnw -pl blog-start -am test -Pred-e2e -Dsurefire.failIfNoSpecifiedTests=false "$@" 2>&1 | tee "$LOG_FILE"
