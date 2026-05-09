# API Contract Alignment Design

## Goal

Align the frontend checked-in OpenAPI reference with the backend runtime OpenAPI contract, using the backend `dev` profile `/v3/api-docs` as the source of truth.

## Decisions

- Backend runtime OpenAPI is the authoritative API contract.
- Frontend `api-reference/openapi.json` should be regenerated from backend `/v3/api-docs`, not manually patched endpoint-by-endpoint.
- Legacy `/api/admin/*` entries in the frontend OpenAPI reference must be removed and replaced by the runtime `/api/v1/admin/*` paths.
- `GET /api/admin/articles/pending/count` is treated as stale documentation and should be removed.
- Do not add a backend `pending/count` endpoint in this scope because the current frontend real service already derives the count from `GET /api/v1/admin/articles/pending?page=1&size=1` and reads `total`.
- Fix the Flyway V13 checksum issue first so backend `dev` startup can capture OpenAPI without `spring.flyway.enabled=false`.

## Scope

### In Scope

- Reproduce and document the Flyway V13 checksum startup failure.
- Repair or otherwise resolve the local dev Flyway checksum mismatch without changing historical migration SQL unless investigation proves the checked-in migration is wrong.
- Start backend with `dev` profile and Flyway enabled.
- Capture `/actuator/health` and `/v3/api-docs`.
- Replace frontend `api-reference/openapi.json` with the captured runtime OpenAPI.
- Verify frontend OpenAPI endpoint list no longer contains `/api/admin/*` or `pending/count`.
- Commit backend audit/update docs and frontend OpenAPI update as separate logical commits if both repositories change.

### Out of Scope

- Adding new backend API endpoints.
- Adding frontend service wrappers for bookmark, highlight, reading progress, article versions, version preference, or series.
- Changing UI behavior.
- Refactoring API client defaults unless implementation reveals it is required for verification.

## Data Flow

1. Backend starts with `spring.profiles.active=dev`.
2. Flyway validates migrations during startup.
3. OpenAPI is served by backend at `/v3/api-docs`.
4. The captured JSON replaces the frontend checked-in `api-reference/openapi.json`.
5. `jq` extracts method/path pairs from both runtime and frontend OpenAPI files.
6. `comm` verifies the stale frontend-only entries are gone.

## Error Handling

- If Flyway still fails after repair, capture the exact failure log under `logs/` and stop before updating frontend OpenAPI.
- If `/v3/api-docs` cannot be fetched, keep the existing frontend OpenAPI unchanged and report the backend startup/API-docs failure.
- If generated frontend OpenAPI still contains `/api/admin/*` or `pending/count`, treat it as evidence that the backend runtime contract is not what the audit expected and stop for investigation.

## Testing And Verification

- Save command output under `logs/` for evidence.
- Reproduce the current Flyway failure before repair using normal `dev` startup.
- After repair, verify:
  - backend `dev` startup succeeds with Flyway enabled,
  - `GET /actuator/health` returns healthy status,
  - `GET /v3/api-docs` returns valid JSON,
  - frontend `api-reference/openapi.json` contains runtime `/api/v1/admin/*` paths,
  - frontend `api-reference/openapi.json` does not contain `/api/admin/*`,
  - frontend `api-reference/openapi.json` does not contain `pending/count`.

## Acceptance Criteria

- Backend `dev` startup no longer requires `spring.flyway.enabled=false` for OpenAPI capture.
- Frontend checked-in OpenAPI has the same runtime admin prefix as backend: `/api/v1/admin/*`.
- `GET /api/admin/articles/pending/count` is removed from frontend OpenAPI.
- Verification logs exist under `logs/`.
- No production code changes are made unless Flyway investigation requires a code/config fix.
