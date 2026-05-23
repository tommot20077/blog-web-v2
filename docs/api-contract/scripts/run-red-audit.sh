#!/usr/bin/env bash
# run-red-audit.sh - POSIX runner for the red contract audit
#
# Run from the backend repo root:
#   ./docs/api-contract/scripts/run-red-audit.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
LOG_DIR="$ROOT/logs/api-contract-red"
DEFAULT_FRONTEND_REPO="$(cd "$ROOT/.." && pwd)/blog-web-v2-front-end"
FRONTEND_REPO="${FRONTEND_REPO:-$DEFAULT_FRONTEND_REPO}"
VITEST_CLI="$FRONTEND_REPO/node_modules/vitest/vitest.mjs"
ORIGINAL_AUDIT_DATE="${AUDIT_DATE-}"
RED_AUDIT_DATE="red-$(date +%Y%m%d%H%M%S)"

cleanup() {
  if [ -n "${ORIGINAL_AUDIT_DATE}" ]; then
    export AUDIT_DATE="$ORIGINAL_AUDIT_DATE"
  else
    unset AUDIT_DATE || true
  fi
}
trap cleanup EXIT

mkdir -p "$LOG_DIR"
export AUDIT_DATE="$RED_AUDIT_DATE"

cd "$ROOT"
./docs/api-contract/scripts/run-audit.sh

LATEST_LOG_DIR="$(find "$ROOT/logs" -maxdepth 1 -type d -name 'api-contract-*' | sort | tail -n 1)"
if [ -z "$LATEST_LOG_DIR" ]; then
  echo "No api-contract log directory found after run-audit.sh" >&2
  exit 1
fi

cp "$LATEST_LOG_DIR/backend-openapi.normalised.json" "$LOG_DIR/backend-openapi.normalised.json"
cp "$LATEST_LOG_DIR/frontend-openapi.json" "$LOG_DIR/frontend-openapi.json"

RED_GAP_REPORT="$ROOT/docs/api-contract/${RED_AUDIT_DATE}-gap-report.md"
if [ -f "$RED_GAP_REPORT" ]; then
  rm -f "$RED_GAP_REPORT"
fi

if [ ! -f "$VITEST_CLI" ]; then
  echo "Vitest CLI not found at $VITEST_CLI. Run npm install in $FRONTEND_REPO first." >&2
  exit 1
fi

CONTRACT_LOG="$ROOT/logs/contract-red-audit.log"
node "$VITEST_CLI" run docs/api-contract/scripts/contract-red.test.js --cache false 2>&1 | tee "$CONTRACT_LOG"
