# Contract Red E2E Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add executable red contract checks that detect frontend/backend drift in response envelopes, error shapes, enums, pagination, and multipart upload behavior.

**Architecture:** Reuse the existing backend API contract audit under `docs/api-contract` and add focused red checks. Contract red tests are report-oriented and isolated from blocking CI until promoted.

**Tech Stack:** Node.js, TypeScript/tsx, Vitest, OpenAPI JSON, Spring Boot OpenAPI, PowerShell.

---

## Red-Test Contract

This plan stops at red.

- Contract tests must run and produce a report.
- Failures must name endpoint, method, and mismatch kind.
- Do not change backend OpenAPI annotations or frontend API services to make the tests green.
- All output goes under backend `logs/`.

## File Structure

Create:

- `docs/api-contract/scripts/contract-red.test.js`
- `docs/api-contract/scripts/run-red-audit.ps1`

Modify:

- `docs/api-contract/scripts/build-report.js` only if it needs a reusable export that is already internally available.
- `docs/api-contract/README.md` only if a contract-audit command index already exists.

Reference:

- `docs/api-contract/2026-05-16-gap-report.md`
- `docs/api-contract/scripts/run-audit.ps1`
- `docs/api-contract/scripts/run-audit.test.js`

## Task 1: Add Red Contract Test Harness

**Files:**

- Create: `docs/api-contract/scripts/contract-red.test.js`

- [ ] **Step 1: Write a focused Vitest contract red test**

Create the file:

```js
import { describe, expect, it } from 'vitest'
import fs from 'node:fs'
import path from 'node:path'

const ROOT = process.cwd()
const LOG_DIR = path.join(ROOT, 'logs', 'api-contract-red')
const BACKEND = path.join(LOG_DIR, 'backend-openapi.normalised.json')
const FRONTEND = path.join(LOG_DIR, 'frontend-openapi.json')

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'))
}

function operationMap(doc) {
  const result = new Map()
  for (const [apiPath, pathItem] of Object.entries(doc.paths ?? {})) {
    for (const method of ['get', 'post', 'put', 'patch', 'delete']) {
      if (pathItem[method]) {
        result.set(`${method.toUpperCase()} ${apiPath}`, pathItem[method])
      }
    }
  }
  return result
}

function responseEnvelope(operation) {
  const response = operation.responses?.['200'] ?? operation.responses?.['201']
  const json = response?.content?.['application/json']?.schema
  return JSON.stringify(json ?? {})
}

describe('contract red checks', () => {
  it('frontend and backend expose exactly the same operation keys', () => {
    const backend = operationMap(readJson(BACKEND))
    const frontend = operationMap(readJson(FRONTEND))

    expect([...frontend.keys()].sort()).toEqual([...backend.keys()].sort())
  })

  it('shared successful responses keep a data envelope', () => {
    const backend = operationMap(readJson(BACKEND))
    const frontend = operationMap(readJson(FRONTEND))
    const mismatches = []

    for (const [key, backendOperation] of backend) {
      const frontendOperation = frontend.get(key)
      if (!frontendOperation) continue
      const backendEnvelope = responseEnvelope(backendOperation)
      const frontendEnvelope = responseEnvelope(frontendOperation)
      if (!backendEnvelope.includes('data') || !frontendEnvelope.includes('data')) {
        mismatches.push(key)
      }
    }

    expect(mismatches).toEqual([])
  })

  it('multipart upload contract remains aligned', () => {
    const backend = operationMap(readJson(BACKEND))
    const frontend = operationMap(readJson(FRONTEND))
    const key = 'POST /api/v1/files/upload'
    const backendBody = JSON.stringify(backend.get(key)?.requestBody ?? {})
    const frontendBody = JSON.stringify(frontend.get(key)?.requestBody ?? {})

    expect(backendBody).toContain('multipart/form-data')
    expect(frontendBody).toContain('multipart/form-data')
    expect(frontendBody).toContain('file')
  })

  it('article status enum contains workflow states used by UI journeys', () => {
    const backend = JSON.stringify(readJson(BACKEND))
    const required = ['DRAFT', 'PENDING_REVIEW', 'PUBLISHED', 'REJECTED']
    const missing = required.filter(value => !backend.includes(value))

    expect(missing).toEqual([])
  })
})
```

- [ ] **Step 2: Run before artifacts exist and confirm harness failure is understandable**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
npx vitest run docs/api-contract/scripts/contract-red.test.js 2>&1 | Tee-Object -FilePath logs\contract-red-no-artifacts.log
```

Expected:

- Fails because `logs/api-contract-red/*.json` does not exist.
- This is acceptable only for this harness step.

- [ ] **Step 3: Commit harness**

```powershell
git status --short
git add docs/api-contract/scripts/contract-red.test.js
git commit -m "test(contract): 新增契約紅燈檢查 harness"
```

## Task 2: Add Red Audit Runner

**Files:**

- Create: `docs/api-contract/scripts/run-red-audit.ps1`

- [ ] **Step 1: Write runner**

Create the file:

```powershell
$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..\..")
$logDir = Join-Path $root "logs\api-contract-red"
New-Item -ItemType Directory -Force $logDir | Out-Null

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

  npx vitest run docs/api-contract/scripts/contract-red.test.js 2>&1 | Tee-Object -FilePath logs\contract-red-audit.log
  exit $LASTEXITCODE
}
finally {
  Pop-Location
}
```

- [ ] **Step 2: Run and capture red**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
.\docs\api-contract\scripts\run-red-audit.ps1 2>&1 | Tee-Object -FilePath logs\contract-red-runner.log
```

Expected:

- Generates `logs/api-contract-red/backend-openapi.normalised.json`.
- Generates `logs/api-contract-red/frontend-openapi.json`.
- Runs Vitest.
- Failure, if any, identifies operation or contract mismatch.

- [ ] **Step 3: Commit runner**

```powershell
git status --short
git add docs/api-contract/scripts/run-red-audit.ps1
git commit -m "test(contract): 新增契約紅燈 audit runner"
```

## Task 3: Add P0 Endpoint Contract Cases

**Files:**

- Modify: `docs/api-contract/scripts/contract-red.test.js`

- [ ] **Step 1: Add explicit P0 operation coverage test**

Append this test inside `describe('contract red checks', () => { ... })`:

```js
  it('P0 journey endpoints are all present in both contracts', () => {
    const backend = operationMap(readJson(BACKEND))
    const frontend = operationMap(readJson(FRONTEND))
    const required = [
      'POST /api/v1/auth/register',
      'GET /api/v1/auth/verify-email',
      'POST /api/v1/auth/login',
      'POST /api/v1/auth/refresh',
      'POST /api/v1/auth/logout',
      'POST /api/v1/articles',
      'PUT /api/v1/articles/{uuid}',
      'POST /api/v1/articles/{uuid}/submit',
      'POST /api/v1/articles/{uuid}/publish',
      'POST /api/v1/articles/{uuid}/reject',
      'GET /api/v1/admin/articles/pending',
      'GET /api/v1/articles',
      'GET /api/v1/articles/{uuid}',
      'GET /api/v1/search',
      'POST /api/v1/articles/{articleUuid}/like',
      'POST /api/v1/articles/{articleUuid}/bookmark',
      'GET /api/v1/users/me/bookmarks',
      'POST /api/v1/articles/{articleUuid}/comments',
    ]

    const missing = required.filter(key => !backend.has(key) || !frontend.has(key))
    expect(missing).toEqual([])
  })
```

- [ ] **Step 2: Run and capture red**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
.\docs\api-contract\scripts\run-red-audit.ps1 2>&1 | Tee-Object -FilePath logs\contract-p0-endpoints-red.log
```

Expected:

- Failure, if any, names missing P0 operation keys.

- [ ] **Step 3: Commit P0 contract cases**

```powershell
git status --short
git add docs/api-contract/scripts/contract-red.test.js
git commit -m "test(contract): 新增 p0 旅程契約紅燈檢查"
```

## Task 4: Add CI Report-Only Direction

**Files:**

- Create: `docs/api-contract/red-ci-notes.md`

- [ ] **Step 1: Write report-only CI note**

Create the file:

```markdown
# Contract Red CI Notes

The contract red suite is intentionally non-blocking until Yuan promotes it.

Recommended job behavior:

- Run `.\docs\api-contract\scripts\run-red-audit.ps1`.
- Upload `logs\contract-red-audit.log`.
- Upload `logs\api-contract-red\`.
- Do not fail blocking CI because these tests are allowed to be red in Phase 1.

Promotion rule:

- Only move this into blocking CI after the team decides the contract red checks should be made green.
```

- [ ] **Step 2: Commit CI note**

```powershell
git status --short
git add docs/api-contract/red-ci-notes.md
git commit -m "docs(contract): 記錄契約紅燈 ci 策略"
```

## Final Acceptance

- Contract red tests run through one PowerShell command.
- Mismatches identify endpoint and mismatch category.
- Logs are saved under `logs/`.
- No backend/frontend implementation code is changed to make contract tests green.
- Existing blocking CI remains unaffected.
