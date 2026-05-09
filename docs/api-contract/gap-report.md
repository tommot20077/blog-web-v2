# API Contract Gap Report

## Summary

- Runtime backend OpenAPI source: regenerate with `curl -fsS http://localhost:9010/v3/api-docs | jq '.'`.
- Runtime backend endpoints: regenerate from the runtime OpenAPI JSON with `jq -r '.paths | to_entries[] as $p | $p.value | keys[] | "\(. | ascii_upcase) \($p.key)"'`.
- Frontend OpenAPI endpoints: regenerate from the checked-in frontend `api-reference/openapi.json` with the same endpoint extraction command.
- Health check evidence: regenerate with `curl -fsS http://localhost:9010/actuator/health`.
- Frontend OpenAPI was regenerated from Flyway-enabled backend runtime `/v3/api-docs`.
- No current backend OpenAPI alignment fix requires using a Flyway bypass.

## Current Required Fixes

No unresolved current required fixes remain for the previously reported admin prefix mismatch, removed pending count endpoint, or Flyway-enabled runtime capture.

## Alignment Follow-Up

- `pending/count` remains intentionally removed from the contract; the frontend derives pending count from `GET /api/v1/admin/articles/pending?page=1&size=1`.
- Frontend OpenAPI was regenerated from Flyway-enabled backend runtime `/v3/api-docs`.
- Verification evidence is intentionally local and ignored by git. Recreate it by running the commands in `docs/superpowers/plans/2026-05-09-api-contract-alignment-plan.md` after starting the backend with the `dev` profile.

## Flyway Safety Evidence

- Canonical safety evidence for the Flyway repair is local and ignored by git; recreate it with the Flyway history, schema check, and repair commands in `docs/superpowers/plans/2026-05-09-api-contract-alignment-plan.md`.
- V14-V17 were applied to the shared dev DB during Flyway-enabled startup in the local execution record; rerun the Flyway-enabled startup command in the alignment plan to verify this in a fresh environment.

## Resolved Alignment Items

| Finding | Resolution | Evidence |
|---|---|---|
| Frontend OpenAPI previously documented stale `/api/admin/*` paths while backend runtime exposed `/api/v1/admin/*`. | Resolved by regenerating frontend OpenAPI from the Flyway-enabled backend runtime `/v3/api-docs`. | Recreate runtime and frontend endpoint lists with the alignment plan commands, then compare the sorted `METHOD path` output. |
| Frontend OpenAPI previously included the removed admin pending count endpoint. | Resolved; `pending/count` is intentionally absent from the contract, and pending count is derived from `GET /api/v1/admin/articles/pending?page=1&size=1`. | Recreate runtime and frontend endpoint lists with the alignment plan commands, then confirm neither list contains `pending/count`. |
| Earlier runtime capture required bypassing Flyway because of the V13 checksum mismatch. | Resolved for alignment evidence; current OpenAPI evidence came from Flyway-enabled backend startup, with Flyway repair evidence recorded separately. | Rerun the Flyway-enabled backend startup and Flyway history commands in the alignment plan. |

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
