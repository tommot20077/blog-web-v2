# API Contract Gap Report

## Summary

- Runtime backend OpenAPI source: `logs/api-contract-align-openapi-runtime.json`
- Runtime backend endpoints: `logs/api-contract-align-runtime-endpoints.txt`
- Frontend OpenAPI endpoints: `logs/api-contract-align-frontend-endpoints.txt`
- Health check evidence: `logs/api-contract-align-health.json`
- Frontend OpenAPI was regenerated from Flyway-enabled backend runtime `/v3/api-docs`.
- No current backend OpenAPI alignment fix requires using a Flyway bypass.

## Current Required Fixes

No unresolved current required fixes remain for the previously reported admin prefix mismatch, removed pending count endpoint, or Flyway-enabled runtime capture.

## Alignment Follow-Up

- `pending/count` remains intentionally removed from the contract; the frontend derives pending count from `GET /api/v1/admin/articles/pending?page=1&size=1`.
- Frontend OpenAPI was regenerated from Flyway-enabled backend runtime `/v3/api-docs`.
- Verification evidence:
  - `logs/api-contract-align-health.json`
  - `logs/api-contract-align-openapi-runtime.json`
  - `logs/api-contract-align-runtime-endpoints.txt`
  - `logs/api-contract-align-frontend-endpoints.txt`

## Flyway Safety Evidence

- Canonical safety evidence for the Flyway repair:
  - `logs/api-contract-align-v13-full-schema-check.tsv`
  - `logs/api-contract-align-flyway-docker-repair.log`
  - `logs/api-contract-align-flyway-history-after.tsv`
- V14-V17 were applied to the shared dev DB during Flyway-enabled startup, based on `logs/api-contract-align-backend-dev.log`.

## Resolved Alignment Items

| Finding | Resolution | Evidence |
|---|---|---|
| Frontend OpenAPI previously documented stale `/api/admin/*` paths while backend runtime exposed `/api/v1/admin/*`. | Resolved by regenerating frontend OpenAPI from the Flyway-enabled backend runtime `/v3/api-docs`. | `logs/api-contract-align-openapi-runtime.json`, `logs/api-contract-align-frontend-endpoints.txt` |
| Frontend OpenAPI previously included the removed admin pending count endpoint. | Resolved; `pending/count` is intentionally absent from the contract, and pending count is derived from `GET /api/v1/admin/articles/pending?page=1&size=1`. | `logs/api-contract-align-runtime-endpoints.txt`, `logs/api-contract-align-frontend-endpoints.txt` |
| Earlier runtime capture required bypassing Flyway because of the V13 checksum mismatch. | Resolved for alignment evidence; current OpenAPI evidence came from Flyway-enabled backend startup, with Flyway repair evidence recorded separately. | `logs/api-contract-align-backend-dev.log`, `logs/api-contract-align-flyway-history-after.tsv` |

## Non-Blocking Follow-Up

| Priority | Finding | Evidence | Classification | Action |
|---|---|---|---|---|
| 非阻塞 | Frontend documentation still mentions stale `/api/admin` paths and the removed `pending/count` endpoint. | Frontend repo `diff.md` and `runbook-integration.md` still mention old `/api/admin` / `pending/count`. | docs-risk | Update frontend docs separately; this is outside the current OpenAPI alignment scope. |

## Deferred Front-End Coverage

| Priority | Finding | Evidence | Classification | Action |
|---|---|---|---|---|
| 可暫緩 | Bookmark APIs exist in backend but no real service wrapper was found in the original audit. | Runtime includes bookmark endpoints. | frontend-missing | Add service/UI coverage when bookmark feature enters frontend scope. |
| 可暫緩 | Highlight APIs exist in backend but no real service wrapper was found in the original audit. | Runtime includes highlight endpoints. | frontend-missing | Add service/UI coverage when reading highlight feature enters frontend scope. |
| 可暫緩 | Reading progress and version preference APIs exist in backend but no real service wrapper was found in the original audit. | Runtime includes reading progress and version preference endpoints. | frontend-missing | Add service/UI coverage when reader state persistence is implemented. |
| 可暫緩 | Article version APIs exist in backend but no real service wrapper was found in the original audit. | Runtime includes article version endpoints. | frontend-missing | Add editor version history service when the UI supports versioning. |
| 可暫緩 | Series APIs exist in backend but no real service wrapper was found in the original audit. | Runtime includes series endpoints. | frontend-missing | Add service/UI coverage when series management is planned. |

## Known Initial Checks

| Priority | Finding | Evidence | Classification | Action |
|---|---|---|---|---|
| 文件需更新 | Backend controller scan and runtime OpenAPI agreed in the original audit. | `docs/api-contract/backend-endpoints.md` recorded production controller mappings matched runtime OpenAPI operations, with no controller-only or OpenAPI-only production endpoints. | matched | No backend controller/OpenAPI path correction required from this audit. |
| 文件需更新 | Frontend real service calls matched runtime backend endpoints in the original audit. | `docs/api-contract/frontend-usage.md` recorded production real service calls with `matched` runtime status. | matched | Keep future service changes aligned with regenerated OpenAPI. |
