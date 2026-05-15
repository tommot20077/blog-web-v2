# run-audit.ps1
# Orchestrate the API contract audit: capture backend OpenAPI, generate frontend OpenAPI,
# diff with oasdiff, and produce the gap report.
#
# Run from the backend repo root:
#   ./docs/api-contract/scripts/run-audit.ps1
#
# Prerequisites:
#   - Node 18+
#   - Docker for tufin/oasdiff; if Docker oasdiff fails, npm @oasdiff-js/oasdiff-js is tried
#   - Frontend repo at $FrontendRepo
#
# Outputs land in logs/api-contract-<date>/ (git-ignored).

$ErrorActionPreference = 'Stop'

# --- Configuration ---
$BackendBase   = if ($env:BACKEND_BASE) { $env:BACKEND_BASE } else { 'http://localhost:9010' }
$FrontendRepo  = if ($env:FRONTEND_REPO) { $env:FRONTEND_REPO } else { 'D:/end/workspace/vue/blog-web-v2-front-end' }
$AuditDate     = if ($env:AUDIT_DATE) { $env:AUDIT_DATE } else { (Get-Date -Format 'yyyy-MM-dd') }
$LogsDir       = "logs/api-contract-$AuditDate"
$RepoRoot      = (Get-Item -Path $PSScriptRoot).Parent.Parent.Parent.FullName
$MaxGeneratorWarningRatio = if ($env:MAX_GENERATOR_WARNING_RATIO) { [double]$env:MAX_GENERATOR_WARNING_RATIO } else { 0.30 }

$script:BackendStartedByScript = $false
$script:BackendProcess = $null

function Test-BackendReadiness {
  try {
    $health = (Invoke-RestMethod "$BackendBase/actuator/health/readiness" -TimeoutSec 5).status
    return $health -eq 'UP'
  }
  catch {
    return $false
  }
}

function Start-BackendIfNeeded {
  if (Test-BackendReadiness) {
    Write-Host "  Backend readiness is already UP at $BackendBase" -ForegroundColor DarkGreen
    return
  }

  Write-Host "  Backend not ready; starting dev profile in background" -ForegroundColor Yellow
  $mvnw = Join-Path $RepoRoot 'mvnw.cmd'
  $script:BackendProcess = Start-Process `
    -FilePath $mvnw `
    -ArgumentList @('-pl', 'blog-start', 'spring-boot:run', '-Dspring-boot.run.profiles=dev') `
    -WorkingDirectory $RepoRoot `
    -PassThru `
    -WindowStyle Hidden
  $script:BackendStartedByScript = $true

  $deadline = (Get-Date).AddSeconds(90)
  while ((Get-Date) -lt $deadline) {
    if (Test-BackendReadiness) {
      Write-Host "  Backend readiness is UP" -ForegroundColor DarkGreen
      return
    }
    Start-Sleep -Seconds 2
  }

  throw 'Backend readiness did not become UP within 90 seconds'
}

function Stop-StartedBackend {
  if ($script:BackendStartedByScript -and $script:BackendProcess) {
    Write-Host "  Stopping backend process tree $($script:BackendProcess.Id)" -ForegroundColor Yellow
    Stop-ProcessTree $script:BackendProcess.Id
  }
}

function Stop-ProcessTree {
  param([int]$ProcessId)

  $children = Get-CimInstance Win32_Process -Filter "ParentProcessId = $ProcessId"
  foreach ($child in $children) {
    Stop-ProcessTree $child.ProcessId
  }

  $process = Get-Process -Id $ProcessId -ErrorAction SilentlyContinue
  if ($process) {
    Stop-Process -Id $ProcessId -Force
  }
}

function Get-OpenApiOperationCount {
  param([string]$SpecPath)

  $spec = Get-Content -Raw -Path $SpecPath | ConvertFrom-Json
  $count = 0
  $httpMethods = @('get', 'post', 'put', 'patch', 'delete', 'options', 'head')
  foreach ($pathProp in $spec.paths.PSObject.Properties) {
    foreach ($methodProp in $pathProp.Value.PSObject.Properties) {
      if ($httpMethods.Contains($methodProp.Name.ToLowerInvariant())) {
        $count += 1
      }
    }
  }
  return $count
}

function Get-JsonArrayCount {
  param([string]$JsonPath)

  if (-not (Test-Path $JsonPath)) { return 0 }
  $raw = Get-Content -Raw -Path $JsonPath
  if ([string]::IsNullOrWhiteSpace($raw)) { return 0 }
  $parsed = $raw | ConvertFrom-Json
  if ($null -eq $parsed) { return 0 }
  if ($parsed -is [System.Array]) { return $parsed.Count }
  return 1
}

function Assert-GeneratorWarningRatio {
  param(
    [string]$WarningsPath,
    [string]$OpenApiPath
  )

  $warningCount = Get-JsonArrayCount $WarningsPath
  $operationCount = Get-OpenApiOperationCount $OpenApiPath
  $denominator = [Math]::Max($operationCount, 1)
  $ratio = $warningCount / $denominator
  Write-Host ("  Generator warning ratio: {0:P1} ({1}/{2})" -f $ratio, $warningCount, $denominator)

  if ($ratio -gt $MaxGeneratorWarningRatio) {
    throw ("generator warning ratio {0:P1} exceeds {1:P1}" -f $ratio, $MaxGeneratorWarningRatio)
  }
}

function Invoke-Oasdiff {
  param(
    [string]$DockerLeft,
    [string]$DockerRight,
    [string]$HostLeft,
    [string]$HostRight,
    [string]$OutputFile
  )

  & docker run --rm -v "${dockerLogs}:/work" tufin/oasdiff diff $DockerLeft $DockerRight -f json > $OutputFile
  $dockerExit = $LASTEXITCODE
  if ($dockerExit -eq 0 -or $dockerExit -eq 1) {
    return
  }

  Write-Warning "Docker oasdiff failed with exit code $dockerExit; falling back to npm @oasdiff-js/oasdiff-js"
  & npx --yes '@oasdiff-js/oasdiff-js' diff $HostLeft $HostRight -f json > $OutputFile
  $fallbackExit = $LASTEXITCODE
  if ($fallbackExit -ne 0 -and $fallbackExit -ne 1) {
    throw "oasdiff fallback failed with exit code $fallbackExit"
  }
}

Write-Host "Working from: $RepoRoot" -ForegroundColor Cyan
Set-Location $RepoRoot

try {
  # --- Phase 0: capture backend OpenAPI ---
  Write-Host "Phase 0: capture backend /v3/api-docs from $BackendBase" -ForegroundColor Cyan
  New-Item -ItemType Directory -Force -Path $LogsDir | Out-Null
  Start-BackendIfNeeded
  Invoke-RestMethod "$BackendBase/v3/api-docs" -OutFile "$LogsDir/backend-openapi.raw.json"
  $backendOperationCount = Get-OpenApiOperationCount "$LogsDir/backend-openapi.raw.json"
  if ($backendOperationCount -lt 50) {
    throw "backend OpenAPI operation count $backendOperationCount is below sanity threshold 50"
  }
  Write-Host "  saved $LogsDir/backend-openapi.raw.json (operations=$backendOperationCount)"

  # --- Phase 2-A: normalise envelope ---
  Write-Host "Phase 2-A: normalise ApiResponse envelope" -ForegroundColor Cyan
  node docs/api-contract/scripts/normalise-backend.js `
    "$LogsDir/backend-openapi.raw.json" `
    "$LogsDir/backend-openapi.normalised.json" `
    "$LogsDir/unwrapped-responses.json"

  # --- Phase 1: generate frontend OpenAPI ---
  Write-Host "Phase 1: generate frontend OpenAPI from $FrontendRepo" -ForegroundColor Cyan
  $absLogs = (Resolve-Path $LogsDir).Path -replace '\\', '/'
  Push-Location $FrontendRepo
  try {
    npx tsx scripts/generate-frontend-openapi.ts `
      --source src/api/real `
      --out "$absLogs/frontend-openapi.json" `
      --warnings "$absLogs/frontend-generator-warnings.json" `
      --anti-pattern-out "$absLogs/anti-pattern-inventory.json" `
      --align-with "$absLogs/backend-openapi.normalised.json"
    npx tsx scripts/generate-frontend-openapi.ts `
      --source src/api/mock `
      --out "$absLogs/frontend-mock-openapi.json" `
      --warnings "$absLogs/frontend-mock-generator-warnings.json"
  }
  finally {
    Pop-Location
  }
  Assert-GeneratorWarningRatio "$LogsDir/frontend-generator-warnings.json" "$LogsDir/frontend-openapi.json"

  # --- Phase 2-B: oasdiff ---
  Write-Host "Phase 2-B: oasdiff (real vs backend, real vs mock)" -ForegroundColor Cyan
  $dockerLogs = (Resolve-Path $LogsDir).Path -replace '\\', '/'
  $env:MSYS_NO_PATHCONV = '1'
  Invoke-Oasdiff `
    '/work/backend-openapi.normalised.json' `
    '/work/frontend-openapi.json' `
    "$LogsDir/backend-openapi.normalised.json" `
    "$LogsDir/frontend-openapi.json" `
    "$LogsDir/oasdiff-real.json"
  Invoke-Oasdiff `
    '/work/frontend-openapi.json' `
    '/work/frontend-mock-openapi.json' `
    "$LogsDir/frontend-openapi.json" `
    "$LogsDir/frontend-mock-openapi.json" `
    "$LogsDir/oasdiff-mock.json"

  # --- Phase 3: report ---
  Write-Host "Phase 3: build markdown gap report" -ForegroundColor Cyan
  $reportFile = "docs/api-contract/$AuditDate-gap-report.md"
  node docs/api-contract/scripts/build-report.js $LogsDir $reportFile

  Write-Host "`nAudit complete. Report: $reportFile" -ForegroundColor Green
}
finally {
  Stop-StartedBackend
}
