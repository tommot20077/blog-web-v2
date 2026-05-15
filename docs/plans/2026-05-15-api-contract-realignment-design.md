# API Contract Re-Alignment Design — 2026-05-15

> Brainstorming output. Implementation plan:
> `docs/plans/2026-05-15-api-contract-realignment-implementation-plan.md`.

## Background

The previous audit at `docs/api-contract/gap-report.md` (snapshot 2026-05-09) concluded with no
required fixes, but flagged 6 backend-only features as "可暫緩":
bookmark, highlight, reading-progress, version-preference, article-version, series.

Since that audit:

- `feature/user-email-verification` was merged (PR #38), introducing email-verification /
  password-reset endpoints in `Auth`.
- The frontend repo (`D:/end/workspace/vue/blog-web-v2-front-end`) now has the previously
  deferred services:
  `bookmarkService.ts`, `highlightService.ts`, `readingProgressService.ts`,
  `versionPreferenceService.ts`, `articleVersionService.ts`, `seriesService.ts`.

Goal of this audit: produce a fresh, **endpoint + schema** level gap report covering the entire
frontend (`real/`, `mock/`, `e2e/`, components/composables/stores) against the runtime backend
OpenAPI, using a fully automated bidirectional OpenAPI diff so the audit is re-runnable.

## Scope

| In scope | Out of scope |
|---|---|
| Endpoint (method + path) alignment | Behavioural / functional testing |
| Request/response schema alignment | Latency / SLO checks |
| Path / query / required field drift | HTTP header alignment beyond what OpenAPI captures |
| Anti-pattern detection (components calling axios directly) | Auth-flow correctness |
| Mock-vs-real service consistency | Auto-fix / code-mod |

## Source of Truth

- **Backend**: Runtime `/v3/api-docs` from `mvnw -pl blog-start spring-boot:run -Dspring-boot.run.profiles=dev`.
  The checked-in `api-reference/openapi.json` in the frontend repo is NOT trusted (may be stale).
- **Frontend**: TypeScript service modules under `src/api/real/` + top-level `src/api/*Service.ts`.
  Generated to OpenAPI by a new ts-morph parser.

## Pipeline

```
Phase 0: capture
  mvnw spring-boot:run --profile=dev (background)
  curl /v3/api-docs > logs/.../backend-openapi.raw.json
  stop backend

Phase 1: frontend openapi generation
  scripts/generate-frontend-openapi.ts (frontend repo)
    parse src/api/real/**/*.ts  -> logs/.../frontend-openapi.json
    parse src/api/mock/**/*.ts  -> logs/.../frontend-mock-openapi.json
  ripgrep + ts-morph inventory:
    e2e/, src/components/**, src/composables/**, src/stores/**
    -> logs/.../anti-pattern-inventory.json

Phase 2: normalise + diff
  docs/api-contract/scripts/normalise-backend.js
    unwrap ApiResponse<T> envelope -> backend-openapi.normalised.json
  oasdiff (Docker tufin/oasdiff):
    backend-normalised vs frontend-openapi          -> oasdiff-real.json
    frontend-openapi vs frontend-mock-openapi      -> oasdiff-mock.json

Phase 3: aggregate
  docs/api-contract/scripts/build-report.js
    consume oasdiff-real.json + oasdiff-mock.json + anti-pattern-inventory.json
    + frontend-generator-warnings.json
    -> docs/api-contract/2026-05-15-gap-report.md
```

## Component Design

### 1. Frontend OpenAPI Generator (`scripts/generate-frontend-openapi.ts`, frontend repo)

**Toolchain**: Node 20, ts-morph (TypeScript AST), ts-json-schema-generator (TS type → JSON
Schema), openapi3-ts (compose document).

**Extraction rules** (per service function):

1. HTTP method ← `apiClient.{get|post|put|patch|delete}(…)`. The function MUST contain exactly
   one such call at the top-level body; multi-call wrappers are flagged and skipped.
2. URL path ← first argument of the call.
   - Plain string literal: used verbatim.
   - Template literal `` `…${ident}…` ``: each `${ident}` becomes `{ident}` in OpenAPI path.
     `ident` MUST be an identifier whose binding is one of the enclosing function's parameters;
     otherwise the call is flagged `unresolved-path` and excluded.
3. Path parameters ← the identifiers extracted in (2), typed from the function signature.
4. Query parameters ← if the second argument is an object literal with a `params` property,
   each key under `params` becomes a query parameter.
5. Request body ← for `post|put|patch`, if the second argument is an object literal WITHOUT a
   `params` property, OR a variable whose type isn't `{ params: ... }`, that argument is the
   request body; its TS type is converted to a JSON Schema.
6. Response ← the explicit `Promise<X>` return type. If the function lacks an explicit return
   type, the inferred type is used and a `response-type-inferred` warning is logged.

**Path-param naming alignment**: after parsing both sides, rename frontend OpenAPI path params
to match backend OpenAPI for the same `(method, path)` pair. Backend is canonical.

**Output**:
- `frontend-openapi.json` (3.0.3)
- `frontend-mock-openapi.json` (3.0.3, same shape, scanned from `src/api/mock/`)
- `frontend-generator-warnings.json` — list of `{file, line, function, code: unresolved-path |
  response-type-inferred | ambiguous-request | multi-call-wrapper | unresolvable-schema}`

**Self-tests**: ~5 small unit tests in `scripts/generate-frontend-openapi.test.ts` covering
each of: plain path, template-literal path, query params, request body, generic Promise return.

### 2. Backend Envelope Normaliser (`docs/api-contract/scripts/normalise-backend.js`)

**Input**: `backend-openapi.raw.json`.

**Operations**:
1. For every `paths.*.[method].responses.*.content.*.schema`:
   - Resolve any `$ref` to find target schema.
   - If target name matches `/^ApiResponse/`, replace the response schema with
     `target.properties.data` (resolving `$ref` if necessary).
   - If `properties.data` is missing / null-typed, replace with `{ "type": "null" }`.
2. Remove unreferenced `ApiResponse*` entries from `components.schemas`.
3. Emit `unwrapped-responses.json` listing every response that did NOT match an `ApiResponse*`
   pattern, for manual review.

**Output**: `backend-openapi.normalised.json`.

### 3. Anti-Pattern Inventory (in the same generator script)

ripgrep + ts-morph sweep over:
- `e2e/**/*.{ts,spec.ts}`
- `src/components/**/*.{vue,ts}`
- `src/composables/**/*.ts`
- `src/stores/**/*.ts`

Captures every call site of `apiClient.X(` or `axios.X(` (any HTTP method). Output:
`anti-pattern-inventory.json` — `[{file, line, method, urlSnippet, category}]`.

### 4. Diff Driver (`docs/api-contract/scripts/run-audit.{sh,ps1}`)

Orchestrates Phase 0–3 end-to-end, putting all intermediate artefacts under
`logs/api-contract-2026-05-15/`. Both sh and ps1 variants because Yuan is on Windows.

Fail-fast guards:
- Backend health check (`/actuator/health`) before Phase 1.
- ≥30% of frontend operations producing generator warnings → halt and prompt.
- `oasdiff` non-zero exit (not "differences exist", but actual crash) → halt.

### 5. Report Builder (`docs/api-contract/scripts/build-report.js`)

Reads all JSON from `logs/api-contract-2026-05-15/`, slots findings into buckets, writes
markdown.

**Bucket rules**:
| Bucket | Source signal |
|---|---|
| Required Fixes | `frontend-only` endpoints (production callers point at missing backend) |
| Schema Drift | oasdiff `paths.modified` for endpoints where both sides exist |
| Backend-only (deferred) | endpoints in backend, absent from `real/`, `mock/`, `e2e/`, components |
| Anti-patterns | non-empty `anti-pattern-inventory.json` |
| Mock Drift | non-empty `oasdiff-mock.json` |
| Generator Warnings | from `frontend-generator-warnings.json` |
| Resolved Since 2026-05-09 | endpoints in backend AND now in `real/` that were "可暫緩" before |

## Output Artefact

`docs/api-contract/2026-05-15-gap-report.md` — see Section 4 of the brainstorming for the
section layout. The old `docs/api-contract/gap-report.md` is left untouched (historical
snapshot) with a one-line pointer appended.

## Risks & Mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Backend dev startup fails (Flyway / DB state) | No Phase 0 output | Reuse the Flyway repair commands from `2026-05-09-api-contract-alignment-plan.md` if needed |
| Some backend response is NOT wrapped in `ApiResponse` (file streams, 302) | Envelope normaliser skips it, schema diff falsely reports drift | Normaliser emits `unwrapped-responses.json` for manual review |
| `oasdiff` Docker image unavailable (network) | Phase 2 fails | Fallback to `@oasdiff-js/oasdiff-js` (npm). Generator + report builder unchanged. |
| Frontend service uses a complex template literal (`${a}${b}`) | Path unresolvable | Flag `unresolved-path` warning, exclude from diff, surface in report |
| TS generic return type unresolvable | Response schema imprecise | Flag `unresolvable-schema`, fall back to `{}` schema, manual spot-check |
| Frontend service is a multi-call wrapper (calls other service, not `apiClient`) | Generator records nothing | Skip + warning, flag for manual review |

## Assumptions

1. Local backend can reach dev DB; V14–V17 already applied (per `gap-report.md` 2026-05-09).
2. Docker is available on this machine for `tufin/oasdiff`.
3. Frontend repo `npm install` has run, or can run, to pull `ts-morph` /
   `ts-json-schema-generator`.
4. Every production HTTP call from the frontend goes through `apiClient` — verified by
   anti-pattern inventory; if violated, audit still produces results but flags every offender.

## Stop Conditions

- Backend won't start after one Flyway repair attempt → halt, ask Yuan.
- Generator warning ratio > 30% → halt, present warnings before committing report.
- `oasdiff` crash with both Docker and npm fallback → halt.

## Definition of Done

- `docs/api-contract/2026-05-15-gap-report.md` committed.
- `scripts/generate-frontend-openapi.ts` (+ its tests) committed in frontend repo.
- `docs/api-contract/scripts/{normalise-backend.js,run-audit.sh,run-audit.ps1,build-report.js}`
  committed in backend repo.
- `logs/api-contract-2026-05-15/*` git-ignored.
- A sanity spot-check on 5 random endpoints confirms the report's classification (≤1 mismatch).

## Out of Scope (explicit no's)

- Auto-fixing any drift. The audit is read-only diagnostic.
- Wiring this into CI. The pipeline is manual re-runnable for now.
- Touching `docs/api-contract/gap-report.md` content beyond appending a one-line pointer.
- Generating frontend types from backend OpenAPI (separate concern).

## Effort Estimate

| Phase | Estimated time |
|---|---|
| 0 — backend capture | 15 min (assuming dev DB clean) |
| 1 — frontend generator + tests | 1.5–2 hr (ts-morph parsing is the bulk) |
| 2 — normaliser + oasdiff orchestration | 45 min |
| 3 — report builder + final sanity check | 45 min |
| **Total** | **3–5 hr** |
