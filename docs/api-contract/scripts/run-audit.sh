#!/usr/bin/env bash
# run-audit.sh — POSIX equivalent of run-audit.ps1
#
# Run from the backend repo root:
#   ./docs/api-contract/scripts/run-audit.sh

set -euo pipefail

BACKEND_BASE="${BACKEND_BASE:-http://localhost:9010}"
FRONTEND_REPO="${FRONTEND_REPO:-/d/end/workspace/vue/blog-web-v2-front-end}"
AUDIT_DATE="${AUDIT_DATE:-$(date +%Y-%m-%d)}"
LOGS_DIR="logs/api-contract-${AUDIT_DATE}"

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
cd "$REPO_ROOT"
echo "▶ Working from: $REPO_ROOT"

echo "▶ Phase 0: capture backend /v3/api-docs from $BACKEND_BASE"
mkdir -p "$LOGS_DIR"
status=$(curl -fsS "$BACKEND_BASE/actuator/health/readiness" | node -e 'process.stdout.write(JSON.parse(require("fs").readFileSync(0,"utf8")).status)')
if [ "$status" != "UP" ]; then echo "Backend readiness is '$status', expected 'UP'"; exit 1; fi
curl -fsS "$BACKEND_BASE/v3/api-docs" > "$LOGS_DIR/backend-openapi.raw.json"
echo "  ✓ saved $LOGS_DIR/backend-openapi.raw.json"

echo "▶ Phase 2-A: normalise ApiResponse envelope"
node docs/api-contract/scripts/normalise-backend.js \
  "$LOGS_DIR/backend-openapi.raw.json" \
  "$LOGS_DIR/backend-openapi.normalised.json" \
  "$LOGS_DIR/unwrapped-responses.json"

echo "▶ Phase 1: generate frontend OpenAPI from $FRONTEND_REPO"
ABS_LOGS="$(cd "$LOGS_DIR" && pwd)"
(
  cd "$FRONTEND_REPO"
  npm run audit:openapi -- \
    --source src/api/real \
    --out "$ABS_LOGS/frontend-openapi.json" \
    --warnings "$ABS_LOGS/frontend-generator-warnings.json" \
    --anti-pattern-out "$ABS_LOGS/anti-pattern-inventory.json" \
    --align-with "$ABS_LOGS/backend-openapi.normalised.json"
  npm run audit:openapi -- \
    --source src/api/mock \
    --out "$ABS_LOGS/frontend-mock-openapi.json" \
    --warnings "$ABS_LOGS/frontend-mock-generator-warnings.json"
)

echo "▶ Phase 2-B: oasdiff (real vs backend, real vs mock)"
export MSYS_NO_PATHCONV=1
docker run --rm -v "$ABS_LOGS:/work" tufin/oasdiff diff \
  /work/backend-openapi.normalised.json /work/frontend-openapi.json -f json \
  > "$LOGS_DIR/oasdiff-real.json"
docker run --rm -v "$ABS_LOGS:/work" tufin/oasdiff diff \
  /work/frontend-openapi.json /work/frontend-mock-openapi.json -f json \
  > "$LOGS_DIR/oasdiff-mock.json"

echo "▶ Phase 3: build markdown gap report"
REPORT_FILE="docs/api-contract/${AUDIT_DATE}-gap-report.md"
AUDIT_SCRIPT=run-audit.sh node docs/api-contract/scripts/build-report.js "$LOGS_DIR" "$REPORT_FILE"

echo ""
echo "✓ Audit complete. Report: $REPORT_FILE"
