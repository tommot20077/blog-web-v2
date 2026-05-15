# run-audit.ps1
# Orchestrate the API contract audit: capture backend OpenAPI, generate frontend OpenAPI,
# diff with oasdiff, and produce the gap report.
#
# Run from the backend repo root:
#   ./docs/api-contract/scripts/run-audit.ps1
#
# Prerequisites:
#   - Backend running on http://localhost:9010 (./mvnw -pl blog-start spring-boot:run -Dspring-boot.run.profiles=dev)
#   - Node 18+, Docker (for oasdiff), Frontend repo at $FrontendRepo
#
# Outputs land in logs/api-contract-2026-05-15/ (git-ignored).

$ErrorActionPreference = 'Stop'

# --- Configuration ---
$BackendBase   = if ($env:BACKEND_BASE) { $env:BACKEND_BASE } else { 'http://localhost:9010' }
$FrontendRepo  = if ($env:FRONTEND_REPO) { $env:FRONTEND_REPO } else { 'D:/end/workspace/vue/blog-web-v2-front-end' }
$AuditDate     = if ($env:AUDIT_DATE) { $env:AUDIT_DATE } else { (Get-Date -Format 'yyyy-MM-dd') }
$LogsDir       = "logs/api-contract-$AuditDate"
$RepoRoot      = (Get-Item -Path $PSScriptRoot).Parent.Parent.Parent.FullName

Write-Host "▶ Working from: $RepoRoot" -ForegroundColor Cyan
Set-Location $RepoRoot

# --- Phase 0: capture backend OpenAPI ---
Write-Host "▶ Phase 0: capture backend /v3/api-docs from $BackendBase" -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path $LogsDir | Out-Null
$health = (Invoke-RestMethod "$BackendBase/actuator/health").status
if ($health -ne 'UP') { throw "Backend health is '$health', expected 'UP'" }
Invoke-RestMethod "$BackendBase/v3/api-docs" -OutFile "$LogsDir/backend-openapi.raw.json"
Write-Host "  ✓ saved $LogsDir/backend-openapi.raw.json"

# --- Phase 2-A: normalise envelope ---
Write-Host "▶ Phase 2-A: normalise ApiResponse envelope" -ForegroundColor Cyan
node docs/api-contract/scripts/normalise-backend.js `
  "$LogsDir/backend-openapi.raw.json" `
  "$LogsDir/backend-openapi.normalised.json" `
  "$LogsDir/unwrapped-responses.json"

# --- Phase 1: generate frontend OpenAPI ---
Write-Host "▶ Phase 1: generate frontend OpenAPI from $FrontendRepo" -ForegroundColor Cyan
$absLogs = (Resolve-Path $LogsDir).Path -replace '\\', '/'
Push-Location $FrontendRepo
try {
  npm run audit:openapi -- `
    --source src/api/real `
    --out "$absLogs/frontend-openapi.json" `
    --warnings "$absLogs/frontend-generator-warnings.json" `
    --anti-pattern-out "$absLogs/anti-pattern-inventory.json" `
    --align-with "$absLogs/backend-openapi.normalised.json"
  npm run audit:openapi -- `
    --source src/api/mock `
    --out "$absLogs/frontend-mock-openapi.json" `
    --warnings "$absLogs/frontend-mock-generator-warnings.json"
}
finally { Pop-Location }

# --- Phase 2-B: oasdiff ---
Write-Host "▶ Phase 2-B: oasdiff (real vs backend, real vs mock)" -ForegroundColor Cyan
$dockerLogs = (Resolve-Path $LogsDir).Path -replace '\\', '/'
$env:MSYS_NO_PATHCONV = '1'
docker run --rm -v "${dockerLogs}:/work" tufin/oasdiff diff `
  /work/backend-openapi.normalised.json /work/frontend-openapi.json -f json `
  > "$LogsDir/oasdiff-real.json"
docker run --rm -v "${dockerLogs}:/work" tufin/oasdiff diff `
  /work/frontend-openapi.json /work/frontend-mock-openapi.json -f json `
  > "$LogsDir/oasdiff-mock.json"

# --- Phase 3: report ---
Write-Host "▶ Phase 3: build markdown gap report" -ForegroundColor Cyan
$reportFile = "docs/api-contract/$AuditDate-gap-report.md"
node docs/api-contract/scripts/build-report.js $LogsDir $reportFile

Write-Host "`n✓ Audit complete. Report: $reportFile" -ForegroundColor Green
