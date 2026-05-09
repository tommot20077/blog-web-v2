# API Contract Gap Report

## Summary

- Runtime backend OpenAPI source: `logs/api-contract-openapi-runtime.json`
- Runtime backend endpoints: 81 operations in `logs/api-contract-runtime-endpoints.txt`
- Production controller cross-check: 81 mappings, all matched runtime OpenAPI in `docs/api-contract/backend-endpoints.md`
- Checked-in frontend OpenAPI endpoints: 48 entries in `logs/api-contract-frontend-openapi-endpoints.txt`
- Runtime endpoints missing from checked-in frontend OpenAPI: 41 entries in `logs/api-contract-runtime-not-in-frontend-openapi.txt`
- Checked-in frontend OpenAPI entries not present in runtime backend OpenAPI: 8 entries in `logs/api-contract-frontend-openapi-not-in-runtime.txt`
- Frontend real service usage: 50 implementation calls summarized in `docs/api-contract/frontend-usage.md`

## Required Fixes

| Priority | Finding | Evidence | Classification | Action |
|---|---|---|---|---|
| 必修正 | Checked-in frontend OpenAPI still documents old admin prefix `/api/admin/*`, but runtime backend exposes `/api/v1/admin/*`. | Runtime has `GET /api/v1/admin/articles/pending`, `POST /api/v1/admin/categories`, `PUT /api/v1/admin/tags/{id}` in `logs/api-contract-runtime-endpoints.txt`; stale entries such as `GET /api/admin/articles/pending` and `POST /api/admin/categories` appear in `logs/api-contract-frontend-openapi-not-in-runtime.txt`. | path-mismatch | Regenerate or update the frontend checked-in OpenAPI document from the backend runtime `/v3/api-docs`. |
| 必修正 | Checked-in frontend OpenAPI includes `GET /api/admin/articles/pending/count`, which is not exposed by runtime backend OpenAPI. | `GET /api/admin/articles/pending/count` appears only in `logs/api-contract-frontend-openapi-not-in-runtime.txt`; runtime admin article controller exposes `GET /api/v1/admin/articles/pending`. | frontend-extra | Remove this documented endpoint or implement it in backend if the UI still requires it. |
| 必修正 | Normal dev startup could not capture OpenAPI because Flyway V13 checksum mismatch blocks boot. | `logs/api-contract-backend-dev.log` records runtime capture with `spring.flyway.enabled=false`; earlier normal startup failed with `Migration checksum mismatch for migration version 13`. | runtime-capture-failed | Fix Flyway history/checksum mismatch so `dev` profile can start without bypassing migrations. |

## Documentation Updates

| Priority | Finding | Evidence | Classification | Action |
|---|---|---|---|---|
| 文件需更新 | Frontend checked-in OpenAPI is missing all runtime admin endpoints under `/api/v1/admin`. | `logs/api-contract-runtime-not-in-frontend-openapi.txt` includes `GET /api/v1/admin/articles/pending`, `POST /api/v1/admin/search/reindex`, `POST /api/v1/admin/categories`, `PUT /api/v1/admin/categories/{uuid}`, `DELETE /api/v1/admin/tags/{id}`, and related admin entries. | doc-mismatch | Regenerate frontend API reference from `logs/api-contract-openapi-runtime.json`. |
| 文件需更新 | Checked-in frontend OpenAPI is missing reading feature endpoints. | Runtime-only entries include bookmark, highlight, reading progress, article version, version preference, and series endpoints in `logs/api-contract-runtime-not-in-frontend-openapi.txt`. | doc-mismatch | Update API reference so frontend planning sees current backend capabilities. |
| 文件需更新 | Frontend app and E2E defaults use different backend ports. | `src/api/apiClient.ts` defaults to `http://localhost:8080`; `e2e/global-setup.ts` defaults to `http://localhost:9010`. | doc-mismatch | Align config or document that E2E requires `VITE_API_BASE_URL=http://localhost:9010`. |

## Deferred Front-End Coverage

| Priority | Finding | Evidence | Classification | Action |
|---|---|---|---|---|
| 可暫緩 | Bookmark APIs exist in backend but no real service wrapper was found. | Runtime has `POST /api/v1/articles/{articleUuid}/bookmark`, `DELETE /api/v1/articles/{articleUuid}/bookmark`, and `GET /api/v1/users/me/bookmarks`; they do not appear in the real service summary. | frontend-missing | Add service/UI coverage when bookmark feature enters frontend scope. |
| 可暫緩 | Highlight APIs exist in backend but no real service wrapper was found. | Runtime has `GET /api/v1/articles/{articleUuid}/highlights`, `POST /api/v1/articles/{articleUuid}/highlights`, `PUT /api/v1/highlights/{uuid}`, `DELETE /api/v1/highlights/{uuid}`. | frontend-missing | Add service/UI coverage when reading highlight feature enters frontend scope. |
| 可暫緩 | Reading progress and version preference APIs exist in backend but no real service wrapper was found. | Runtime has `GET/PUT /api/v1/articles/{articleUuid}/progress` and `GET/PUT/DELETE /api/v1/me/preferences/version...`. | frontend-missing | Add service/UI coverage when reader state persistence is implemented. |
| 可暫緩 | Article version APIs exist in backend but no real service wrapper was found. | Runtime has `/api/v1/articles/{articleUuid}/versions`, `/manual`, `/{versionUuid}`, `/promote`, and `/restore` endpoints. | frontend-missing | Add editor version history service when the UI supports versioning. |
| 可暫緩 | Series APIs exist in backend but no real service wrapper was found. | Runtime has `GET/POST /api/v1/series`, `GET/PUT/DELETE /api/v1/series/{...}`, and article membership endpoints. | frontend-missing | Add service/UI coverage when series management is planned. |

## Runtime OpenAPI Capture Failure

| Priority | Finding | Evidence | Classification | Action |
|---|---|---|---|---|
| 必修正 | The OpenAPI evidence is runtime evidence under `dev` profile, but migration validation was bypassed for capture. | Backend was started with `--spring.profiles.active=dev --spring.flyway.enabled=false`; health and OpenAPI were captured from `http://localhost:9010`. | runtime-capture-failed | Treat endpoint shape as valid runtime OpenAPI evidence, but do not treat this as proof that Flyway-enabled dev startup is healthy. |

## Known Initial Checks

| Priority | Finding | Evidence | Classification | Action |
|---|---|---|---|---|
| 文件需更新 | Backend controller scan and runtime OpenAPI agree. | `docs/api-contract/backend-endpoints.md` records 81 production controller mappings matched 81 runtime OpenAPI operations, with no controller-only or OpenAPI-only production endpoints. | matched | No backend controller/OpenAPI path correction required from this audit. |
| 文件需更新 | Current frontend real service calls match runtime backend endpoints. | `docs/api-contract/frontend-usage.md` records 50 production real service calls with `matched` runtime status. | matched | Focus immediate fixes on checked-in OpenAPI reference and Flyway startup. |
