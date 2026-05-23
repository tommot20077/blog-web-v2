$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$logDir = Join-Path $root "logs\api-contract-red"
New-Item -ItemType Directory -Force $logDir | Out-Null
$frontendRepo = if ($env:FRONTEND_REPO) { $env:FRONTEND_REPO } else { "D:\end\workspace\vue\blog-web-v2-front-end" }
$vitestCli = Join-Path $frontendRepo "node_modules\vitest\vitest.mjs"
$originalAuditDate = $env:AUDIT_DATE
$redAuditDate = "red-$(Get-Date -Format 'yyyyMMddHHmmss')"
$env:AUDIT_DATE = $redAuditDate

Push-Location $root
try {
  & .\docs\api-contract\scripts\run-audit.ps1
  if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

  $latest = Get-ChildItem -Path (Join-Path $root "logs") -Directory |
    Where-Object { $_.Name -like "api-contract-*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

  if (-not $latest) {
    throw "No api-contract log directory found after run-audit.ps1"
  }

  Copy-Item -LiteralPath (Join-Path $latest.FullName "backend-openapi.normalised.json") -Destination (Join-Path $logDir "backend-openapi.normalised.json") -Force
  Copy-Item -LiteralPath (Join-Path $latest.FullName "frontend-openapi.json") -Destination (Join-Path $logDir "frontend-openapi.json") -Force
  $redGapReport = Join-Path $root "docs\api-contract\$redAuditDate-gap-report.md"
  if (Test-Path $redGapReport) {
    Remove-Item -LiteralPath $redGapReport -Force
  }

  if (-not (Test-Path $vitestCli)) {
    throw "Vitest CLI not found at $vitestCli. Run npm install in $frontendRepo first."
  }

  $contractLog = Join-Path $root "logs\contract-red-audit.log"
  $stdoutLog = Join-Path $root "logs\contract-red-audit.stdout.log"
  $stderrLog = Join-Path $root "logs\contract-red-audit.stderr.log"
  $process = Start-Process `
    -FilePath "node" `
    -ArgumentList @($vitestCli, "run", "docs/api-contract/scripts/contract-red.test.js", "--cache", "false") `
    -WorkingDirectory $root `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -WindowStyle Hidden `
    -PassThru `
    -Wait

  $stdoutText = if (Test-Path $stdoutLog) { Get-Content -Raw -Path $stdoutLog } else { "" }
  $stderrText = if (Test-Path $stderrLog) { Get-Content -Raw -Path $stderrLog } else { "" }
  [System.IO.File]::WriteAllText($contractLog, "$stdoutText$stderrText", [System.Text.UTF8Encoding]::new($false))
  Get-Content -Path $contractLog
  exit $process.ExitCode
}
finally {
  $env:AUDIT_DATE = $originalAuditDate
  Pop-Location
}
