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
MAX_GENERATOR_WARNING_RATIO="${MAX_GENERATOR_WARNING_RATIO:-0.30}"

REPO_ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
BACKEND_PID=""
BACKEND_STARTED_BY_SCRIPT=0
BACKEND_PROCESS_GROUP=0

cd "$REPO_ROOT"
echo "Working from: $REPO_ROOT"

backend_readiness_is_up() {
  curl --connect-timeout 5 --max-time 10 -fsS "$BACKEND_BASE/actuator/health/readiness" 2>/dev/null \
    | node -e 'const fs=require("fs"); try { const raw=fs.readFileSync(0,"utf8").trim(); if (!raw) process.exit(1); const x=JSON.parse(raw); process.exit(x.status === "UP" ? 0 : 1); } catch { process.exit(1); }'
}

start_backend_if_needed() {
  if backend_readiness_is_up; then
    echo "  Backend readiness is already UP at $BACKEND_BASE"
    return
  fi

  echo "  Backend not ready; starting dev profile in background"
  if command -v setsid >/dev/null 2>&1; then
    setsid ./mvnw -pl blog-start spring-boot:run -Dspring-boot.run.profiles=dev \
      > "$LOGS_DIR/backend-start.log" 2>&1 &
    BACKEND_PROCESS_GROUP=1
  else
    ./mvnw -pl blog-start spring-boot:run -Dspring-boot.run.profiles=dev \
      > "$LOGS_DIR/backend-start.log" 2>&1 &
  fi
  BACKEND_PID="$!"
  BACKEND_STARTED_BY_SCRIPT=1

  for _ in $(seq 1 45); do
    if backend_readiness_is_up; then
      echo "  Backend readiness is UP"
      return
    fi
    sleep 2
  done

  echo "Backend readiness did not become UP within 90 seconds" >&2
  exit 1
}

terminate_process_tree() {
  local pid="$1"
  if command -v pgrep >/dev/null 2>&1; then
    for child in $(pgrep -P "$pid" 2>/dev/null || true); do
      terminate_process_tree "$child"
    done
  fi
  kill "$pid" 2>/dev/null || true
}

cleanup_backend() {
  if [ "$BACKEND_STARTED_BY_SCRIPT" = "1" ] && [ -n "$BACKEND_PID" ]; then
    echo "  Stopping backend process group/tree $BACKEND_PID"
    if [ "$BACKEND_PROCESS_GROUP" = "1" ]; then
      kill -TERM -- "-$BACKEND_PID" 2>/dev/null || true
      sleep 2
      kill -KILL -- "-$BACKEND_PID" 2>/dev/null || true
    else
      terminate_process_tree "$BACKEND_PID"
    fi
  fi
}
trap cleanup_backend EXIT

openapi_operation_count() {
  node - "$1" <<'NODE'
const fs = require('fs')
const spec = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
const methods = new Set(['get', 'post', 'put', 'patch', 'delete', 'options', 'head'])
let count = 0
for (const pathItem of Object.values(spec.paths || {})) {
  for (const method of Object.keys(pathItem || {})) {
    if (methods.has(method.toLowerCase())) count += 1
  }
}
process.stdout.write(String(count))
NODE
}

check_generator_warning_ratio() {
  node - "$1" "$2" "$MAX_GENERATOR_WARNING_RATIO" <<'NODE'
const fs = require('fs')
const [warningsPath, specPath, thresholdRaw] = process.argv.slice(2)
const warnings = fs.existsSync(warningsPath) ? JSON.parse(fs.readFileSync(warningsPath, 'utf8')) : []
const spec = JSON.parse(fs.readFileSync(specPath, 'utf8'))
const methods = new Set(['get', 'post', 'put', 'patch', 'delete', 'options', 'head'])
let operations = 0
for (const pathItem of Object.values(spec.paths || {})) {
  for (const method of Object.keys(pathItem || {})) {
    if (methods.has(method.toLowerCase())) operations += 1
  }
}
const denominator = Math.max(operations, 1)
const ratio = warnings.length / denominator
const threshold = Number(thresholdRaw)
console.log(`  Generator warning ratio: ${(ratio * 100).toFixed(1)}% (${warnings.length}/${denominator})`)
if (ratio > threshold) {
  console.error(`generator warning ratio ${(ratio * 100).toFixed(1)}% exceeds ${(threshold * 100).toFixed(1)}%`)
  process.exit(1)
}
NODE
}

run_oasdiff() {
  local docker_left="$1"
  local docker_right="$2"
  local host_left="$3"
  local host_right="$4"
  local output_file="$5"

  set +e
  docker run --rm -v "$ABS_LOGS:/work" tufin/oasdiff diff "$docker_left" "$docker_right" -f json > "$output_file"
  local docker_exit=$?
  set -e
  if [ "$docker_exit" -eq 0 ] || [ "$docker_exit" -eq 1 ]; then
    return
  fi

  echo "Docker oasdiff failed with exit code $docker_exit; falling back to npm @oasdiff-js/oasdiff-js" >&2
  set +e
  npx --yes @oasdiff-js/oasdiff-js diff "$host_left" "$host_right" -f json > "$output_file"
  local fallback_exit=$?
  set -e
  if [ "$fallback_exit" -ne 0 ] && [ "$fallback_exit" -ne 1 ]; then
    echo "oasdiff fallback failed with exit code $fallback_exit" >&2
    exit "$fallback_exit"
  fi
}

echo "Phase 0: capture backend /v3/api-docs from $BACKEND_BASE"
mkdir -p "$LOGS_DIR"
start_backend_if_needed
curl --connect-timeout 5 --max-time 60 -fsS "$BACKEND_BASE/v3/api-docs" > "$LOGS_DIR/backend-openapi.raw.json"
backend_operation_count="$(openapi_operation_count "$LOGS_DIR/backend-openapi.raw.json")"
if [ "$backend_operation_count" -lt 50 ]; then
  echo "backend OpenAPI operation count $backend_operation_count is below sanity threshold 50" >&2
  exit 1
fi
echo "  saved $LOGS_DIR/backend-openapi.raw.json (operations=$backend_operation_count)"

echo "Phase 2-A: normalise ApiResponse envelope"
node docs/api-contract/scripts/normalise-backend.js \
  "$LOGS_DIR/backend-openapi.raw.json" \
  "$LOGS_DIR/backend-openapi.normalised.json" \
  "$LOGS_DIR/unwrapped-responses.json"

echo "Phase 1: generate frontend OpenAPI from $FRONTEND_REPO"
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
check_generator_warning_ratio "$LOGS_DIR/frontend-generator-warnings.json" "$LOGS_DIR/frontend-openapi.json"

echo "Phase 2-B: oasdiff (real vs backend, real vs mock)"
export MSYS_NO_PATHCONV=1
run_oasdiff \
  /work/backend-openapi.normalised.json \
  /work/frontend-openapi.json \
  "$LOGS_DIR/backend-openapi.normalised.json" \
  "$LOGS_DIR/frontend-openapi.json" \
  "$LOGS_DIR/oasdiff-real.json"
run_oasdiff \
  /work/frontend-openapi.json \
  /work/frontend-mock-openapi.json \
  "$LOGS_DIR/frontend-openapi.json" \
  "$LOGS_DIR/frontend-mock-openapi.json" \
  "$LOGS_DIR/oasdiff-mock.json"

echo "Phase 3: build markdown gap report"
REPORT_FILE="docs/api-contract/${AUDIT_DATE}-gap-report.md"
AUDIT_SCRIPT=run-audit.sh node docs/api-contract/scripts/build-report.js "$LOGS_DIR" "$REPORT_FILE"

echo ""
echo "Audit complete. Report: $REPORT_FILE"
