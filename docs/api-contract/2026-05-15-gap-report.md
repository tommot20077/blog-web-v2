# API Contract Gap Report — 2026-05-15

## Summary

- Backend operations: 82
- Frontend operations: 75
- Backend snapshot: `logs/api-contract-2026-05-15/backend-openapi.normalised.json`
- Frontend snapshot: `logs/api-contract-2026-05-15/frontend-openapi.json`
- Audit script version: 2026-05-15

## Required Fixes

Frontend calls these endpoints but backend does not expose them — production will break.

_None._


## Schema Drift

Endpoints exist on both sides, but request/response schema or parameters differ.
Drift severity: `high` = breaking; `medium` = silent contract mismatch; `low` = stylistic.

| Method | Path | Kind | Location | Severity | Summary |
|---|---|---|---|---|---|
| GET | /api/v1/admin/articles/pending | parameter-required-change | parameter:query:page | high | required flipped from false to true |
| GET | /api/v1/admin/articles/pending | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/admin/articles/pending | parameter-required-change | parameter:query:size | high | required flipped from false to true |
| GET | /api/v1/admin/articles/pending | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/articles | parameter-required-change | parameter:query:page | high | required flipped from false to true |
| GET | /api/v1/articles | parameter-type-change | parameter:query:page | medium | type integer → string |
| GET | /api/v1/articles | parameter-required-change | parameter:query:size | high | required flipped from false to true |
| GET | /api/v1/articles | parameter-type-change | parameter:query:size | medium | type integer → string |
| POST | /api/v1/articles | requestBody-required-added | requestBody:application/json | high | new required fields: summary, coverImageUrl, categoryIds, tagNames |
| POST | /api/v1/articles | requestBody-property-deleted | requestBody:application/json | medium | removed properties: status |
| GET | /api/v1/articles/me | parameter-required-change | parameter:query:page | high | required flipped from false to true |
| GET | /api/v1/articles/me | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/articles/me | parameter-required-change | parameter:query:size | high | required flipped from false to true |
| GET | /api/v1/articles/me | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/articles/me | parameter-type-change | parameter:query:status | medium | type string →  |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-required-change | parameter:query:page | high | required flipped from false to true |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-required-change | parameter:query:size | high | required flipped from false to true |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-required-change | parameter:query:sort | high | required flipped from false to true |
| GET | /api/v1/articles/{articleUuid}/comments | parameter-type-change | parameter:query:sort | medium | type string →  |
| GET | /api/v1/articles/{articleUuid}/versions | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/articles/{articleUuid}/versions | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/articles/{articleUuid}/versions | parameter-type-change | parameter:query:type | medium | type string →  |
| PUT | /api/v1/articles/{uuid} | requestBody-required-added | requestBody:application/json | high | new required fields: title, summary, content, coverImageUrl, categoryIds, tagNames |
| PUT | /api/v1/articles/{uuid} | requestBody-property-deleted | requestBody:application/json | medium | removed properties: status |
| POST | /api/v1/articles/{uuid}/reject | requestBody-required-added | requestBody:application/json | high | new required fields: reason |
| POST | /api/v1/auth/logout | parameter-deleted | parameter:cookie:refreshToken | high | Frontend passes cookie param "refreshToken" that backend no longer accepts. |
| POST | /api/v1/auth/refresh | parameter-deleted | parameter:cookie:refreshToken | high | Frontend passes cookie param "refreshToken" that backend no longer accepts. |
| POST | /api/v1/files/upload | parameter-deleted | parameter:query:usageType | high | Frontend passes query param "usageType" that backend no longer accepts. |
| GET | /api/v1/recommend/related/{articleUuid} | parameter-deleted | parameter:query:limit | high | Frontend passes query param "limit" that backend no longer accepts. |
| GET | /api/v1/recommend/trending | parameter-required-change | parameter:query:limit | high | required flipped from false to true |
| GET | /api/v1/recommend/trending | parameter-type-change | parameter:query:limit | low | type integer → number |
| GET | /api/v1/recommend/trending | parameter-required-change | parameter:query:period | high | required flipped from false to true |
| GET | /api/v1/search | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/search | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/search | parameter-type-change | parameter:query:sort | medium | type string →  |
| GET | /api/v1/search/suggest | parameter-required-change | parameter:query:q | high | required flipped from false to true |
| GET | /api/v1/series | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/series | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/tags/hot | parameter-required-change | parameter:query:limit | high | required flipped from false to true |
| GET | /api/v1/tags/hot | parameter-type-change | parameter:query:limit | low | type integer → number |
| GET | /api/v1/tags/suggest | parameter-deleted | parameter:query:limit | high | Frontend passes query param "limit" that backend no longer accepts. |
| GET | /api/v1/users/me/bookmarks | parameter-required-change | parameter:query:page | high | required flipped from false to true |
| GET | /api/v1/users/me/bookmarks | parameter-type-change | parameter:query:page | low | type integer → number |
| GET | /api/v1/users/me/bookmarks | parameter-required-change | parameter:query:size | high | required flipped from false to true |
| GET | /api/v1/users/me/bookmarks | parameter-type-change | parameter:query:size | low | type integer → number |
| GET | /api/v1/users/me/files | parameter-deleted | parameter:query:page | high | Frontend passes query param "page" that backend no longer accepts. |
| GET | /api/v1/users/me/files | parameter-deleted | parameter:query:size | high | Frontend passes query param "size" that backend no longer accepts. |
| GET | /api/v1/users/me/files | parameter-deleted | parameter:query:sort | high | Frontend passes query param "sort" that backend no longer accepts. |


## Backend-only Endpoints

Backend exposes these, frontend never calls. Either deferred features or admin tooling.

| Method | Path |
|---|---|
| DELETE | /api/v1/admin/categories/{uuid} |
| DELETE | /api/v1/admin/tags/{id} |
| POST | /api/v1/admin/categories |
| POST | /api/v1/admin/search/reindex |
| POST | /api/v1/auth/verify-email-code |
| PUT | /api/v1/admin/categories/{uuid} |
| PUT | /api/v1/admin/tags/{id} |


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

_Mock generator emitted 0 operations because `src/api/mock/` services do not call `apiClient` (by design). Per-endpoint mock-vs-real drift is therefore not meaningful for this layer. 55 real paths have no mock counterpart at the apiClient layer._

## Generator Warnings

Non-blocking — generator could not fully resolve these but recorded them.

| Code | Count | Sample (first 3) |
|---|---|---|
| ambiguous-request | 1 | D:/end/workspace/vue/blog-web-v2-front-end/src/api/real/userService.ts:14:userService.deleteAccount |


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
