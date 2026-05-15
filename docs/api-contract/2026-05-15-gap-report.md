# API Contract Gap Report — 2026-05-15

## Summary

- Backend operations: 82
- Frontend operations: 82
- Backend snapshot: `logs/api-contract-2026-05-15/backend-openapi.normalised.json`
- Frontend snapshot: `logs/api-contract-2026-05-15/frontend-openapi.json`
- Audit script version: run-audit.ps1

## Required Fixes

Frontend calls these endpoints but backend does not expose them — production will break.

_None._


## Schema Drift

Endpoints exist on both sides, but request/response schema or parameters differ.
Drift severity: `high` = breaking; `medium` = silent contract mismatch; `low` = stylistic.

| Method | Path | Kind | Location | Severity | Summary |
|---|---|---|---|---|---|
| GET | /api/v1/admin/articles/pending | parameter-required-change | parameter:query:page | low | Frontend marks query param "page" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/admin/articles/pending | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/admin/articles/pending | parameter-required-change | parameter:query:size | low | Frontend marks query param "size" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/admin/articles/pending | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/articles | parameter-required-change | parameter:query:page | low | Frontend marks query param "page" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/articles | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/articles | parameter-required-change | parameter:query:size | low | Frontend marks query param "size" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/articles | parameter-type-change | parameter:query:size | low | type integer → number |
| POST | /api/v1/articles | requestBody-required-frontend-stricter | requestBody:application/json | low | Frontend marks required: summary, coverImageUrl, categoryIds, tagNames. Backend allows them optional (safe). |
| POST | /api/v1/articles | requestBody-backend-only-field | requestBody:application/json | low | Backend defines optional fields frontend skips: status |
| GET | /api/v1/articles/me | parameter-required-change | parameter:query:page | low | Frontend marks query param "page" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/articles/me | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/articles/me | parameter-required-change | parameter:query:size | low | Frontend marks query param "size" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/articles/me | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-required-change | parameter:query:page | low | Frontend marks query param "page" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-required-change | parameter:query:size | low | Frontend marks query param "size" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-required-change | parameter:query:sort | low | Frontend marks query param "sort" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/articles/{articleUuid}/versions | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/articles/{articleUuid}/versions | parameter-type-change | parameter:query:size | low | type integer → number |
| PUT | /api/v1/articles/{uuid} | requestBody-required-frontend-stricter | requestBody:application/json | low | Frontend marks required: categoryIds, tagNames, title, summary, content, coverImageUrl. Backend allows them optional (safe). |
| PUT | /api/v1/articles/{uuid} | requestBody-backend-only-field | requestBody:application/json | low | Backend defines optional fields frontend skips: status |
| POST | /api/v1/articles/{uuid}/reject | requestBody-required-frontend-stricter | requestBody:application/json | low | Frontend marks required: reason. Backend allows them optional (safe). |
| POST | /api/v1/auth/logout | parameter-not-emitted | parameter:cookie:refreshToken | low | Backend declares cookie param "refreshToken"; frontend does not pass it explicitly (browsers handle cookies automatically). |
| POST | /api/v1/auth/refresh | parameter-not-emitted | parameter:cookie:refreshToken | low | Backend declares cookie param "refreshToken"; frontend does not pass it explicitly (browsers handle cookies automatically). |
| GET | /api/v1/recommend/related/{articleUuid} | parameter-required-change | parameter:query:limit | low | Frontend marks query param "limit" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/recommend/related/{articleUuid} | parameter-type-change | parameter:query:limit | low | type integer → number |
| GET | /api/v1/recommend/trending | parameter-required-change | parameter:query:limit | low | Frontend marks query param "limit" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/recommend/trending | parameter-type-change | parameter:query:limit | low | type integer → number |
| GET | /api/v1/recommend/trending | parameter-required-change | parameter:query:period | low | Frontend marks query param "period" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/search | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/search | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/search/suggest | parameter-required-change | parameter:query:q | low | Frontend marks query param "q" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/series | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/series | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/tags/hot | parameter-required-change | parameter:query:limit | low | Frontend marks query param "limit" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/tags/hot | parameter-type-change | parameter:query:limit | low | type integer → number |
| GET | /api/v1/tags/suggest | parameter-required-change | parameter:query:limit | low | Frontend marks query param "limit" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/tags/suggest | parameter-type-change | parameter:query:limit | low | type integer → number |
| GET | /api/v1/users/me/bookmarks | parameter-required-change | parameter:query:page | low | Frontend marks query param "page" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/users/me/bookmarks | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/users/me/bookmarks | parameter-required-change | parameter:query:size | low | Frontend marks query param "size" required; backend allows it optional (frontend is stricter, safe). |
| GET | /api/v1/users/me/bookmarks | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/users/me/files | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/users/me/files | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/users/me/files | parameter-type-change | parameter:query:sort | low | Spring Pageable sort accepts repeated sort params; frontend sends a single sort string. |


## Backend-only Endpoints

Backend exposes these, frontend never calls. Either deferred features or admin tooling.

_None._


## Frontend-only Endpoints

Already listed under Required Fixes; reproduced for ease of comparison.

_None._


## Anti-patterns

Direct apiClient/axios calls outside `src/api/`. Production code should go through service modules; tests/dev backdoors are tolerated but listed.

| Method | URL Snippet | Category | Location |
|---|---|---|---|
| get | '/api/v1/articles/me' | e2e-direct | e2e/integration/auth-token-refresh.spec.ts:136 |
| get | '/api/v1/users/me' | e2e-direct | e2e/integration/auth-token-refresh.spec.ts:137 |
| get | '/api/v1/articles/me' | e2e-direct | e2e/integration/auth-token-refresh.spec.ts:192 |


## Mock-vs-Real Drift

_Mock generator emitted 0 operations because `src/api/mock/` services do not call `apiClient` (by design). Per-endpoint mock-vs-real drift is therefore not meaningful for this layer. 60 real paths have no mock counterpart at the apiClient layer._

## Generator Warnings

Non-blocking — generator could not fully resolve these but recorded them.

_None._


## Unwrapped Backend Responses

Backend responses NOT wrapped in `ApiResponse<T>` envelope (envelope normaliser skipped these). Audit manually.

_None._


## Resolved Since 2026-05-09

Endpoints flagged as "可暫緩" in the 2026-05-09 audit that now have matching frontend services.

| Feature tag | Operation |
|---|---|
| bookmark | DELETE /api/v1/articles/{articleUuid}/bookmark |
| bookmark | GET /api/v1/users/me/bookmarks |
| bookmark | POST /api/v1/articles/{articleUuid}/bookmark |
| highlight | DELETE /api/v1/highlights/{uuid} |
| highlight | GET /api/v1/articles/{articleUuid}/highlights |
| highlight | POST /api/v1/articles/{articleUuid}/highlights |
| highlight | PUT /api/v1/highlights/{uuid} |
| progress | GET /api/v1/articles/{articleUuid}/progress |
| progress | PUT /api/v1/articles/{articleUuid}/progress |
| preferences/version | DELETE /api/v1/me/preferences/version/{key} |
| preferences/version | GET /api/v1/me/preferences/version |
| preferences/version | PUT /api/v1/me/preferences/version |
| versions | DELETE /api/v1/articles/{articleUuid}/versions/{versionUuid} |
| versions | GET /api/v1/articles/{articleUuid}/versions |
| versions | GET /api/v1/articles/{articleUuid}/versions/{versionUuid} |
| versions | POST /api/v1/articles/{articleUuid}/versions/manual |
| versions | POST /api/v1/articles/{articleUuid}/versions/{versionUuid}/promote |
| versions | POST /api/v1/articles/{articleUuid}/versions/{versionUuid}/restore |
| series | DELETE /api/v1/series/{uuid} |
| series | DELETE /api/v1/series/{uuid}/articles/{articleUuid} |
| series | GET /api/v1/series |
| series | GET /api/v1/series/{slug} |
| series | POST /api/v1/series |
| series | PUT /api/v1/series/{uuid} |
| series | PUT /api/v1/series/{uuid}/articles/{articleUuid} |


## Evidence & Reproducibility

- Intermediate artefacts: `logs/api-contract-2026-05-15/` (git-ignored).
- Re-run: `./docs/api-contract/scripts/run-audit.ps1` (or `.sh`).
- Backend startup: `./mvnw -pl blog-start spring-boot:run -Dspring-boot.run.profiles=dev`.
