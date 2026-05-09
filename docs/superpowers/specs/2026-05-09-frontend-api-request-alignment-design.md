# Frontend API Request Alignment Design

## Goal

Align the frontend request layer with the backend runtime OpenAPI contract by adding missing real service wrappers and cleaning stale frontend documentation/test references.

## Scope

### In Scope

- Add missing real service wrappers under `/mnt/d/end/workspace/vue/blog-web-v2-front-end/src/api/real/`:
  - `bookmarkService`
  - `highlightService`
  - `readingProgressService`
  - `versionPreferenceService`
  - `articleVersionService`
  - `seriesService`
- Add unit tests for each new service.
- Keep endpoint paths aligned with `/mnt/d/end/workspace/vue/blog-web-v2-front-end/api-reference/openapi.json`.
- Clean stale frontend documentation references in:
  - `/mnt/d/end/workspace/vue/blog-web-v2-front-end/diff.md`
  - `/mnt/d/end/workspace/vue/blog-web-v2-front-end/runbook-integration.md`
- Clean E2E hard-coded backend URL usage where tests can use the existing environment-based backend URL pattern instead.

### Out Of Scope

- Connecting the new services to UI components.
- Adding new backend endpoints.
- Changing backend OpenAPI.
- Changing authentication/token behavior.
- Reworking frontend API architecture beyond the new wrapper files and focused cleanup.

## Service Design

Each new real service should follow the existing `src/api/real/*Service.ts` pattern:

- Import `apiClient` from `../apiClient`.
- Expose a named service object.
- Keep response/request interfaces close to the service unless a shared frontend type already exists.
- Return backend data directly when no mapper is needed.
- Use small mappers only when backend fields need to match an existing frontend-facing shape.

Planned service methods:

- `bookmarkService`
  - `bookmark(articleUuid)`
  - `unbookmark(articleUuid)`
  - `getMyBookmarks(page, size)`
- `highlightService`
  - `list(articleUuid)`
  - `create(articleUuid, request)`
  - `update(uuid, request)`
  - `delete(uuid)`
- `readingProgressService`
  - `get(articleUuid)`
  - `update(articleUuid, request)`
- `versionPreferenceService`
  - `get()`
  - `update(request)`
  - `reset(key)`
- `articleVersionService`
  - `list(articleUuid)`
  - `getDetail(articleUuid, versionUuid)`
  - `createManual(articleUuid, request)`
  - `delete(articleUuid, versionUuid)`
  - `promote(articleUuid, versionUuid)`
  - `restore(articleUuid, versionUuid)`
- `seriesService`
  - `list(params?)`
  - `get(slug)`
  - `create(request)`
  - `update(uuid, request)`
  - `delete(uuid)`
  - `addArticle(uuid, articleUuid)`
  - `removeArticle(uuid, articleUuid)`

## Data Flow

1. UI or future composables import a service from `src/api/real`.
2. Service calls `apiClient` with a backend runtime OpenAPI path.
3. `apiClient` handles base URL, auth header, refresh behavior, and response unwrapping.
4. Service returns typed data or `void`.

## Documentation And E2E Cleanup

- `diff.md` and `runbook-integration.md` should no longer describe `/api/admin/*` or `pending/count` as current API paths.
- E2E hard-coded `http://localhost:9010` should be replaced with a shared backend URL value where practical, following the existing `VITE_API_BASE_URL || 'http://localhost:9010'` pattern.
- Cleanup should not change test scenarios or expected behavior.

## Testing Strategy

Follow TDD for every implementation change:

1. Write failing unit tests for the new service file.
2. Run the targeted test and save output under a local log file.
3. Implement the minimum service code.
4. Re-run the targeted test and save passing output.

Targeted tests should verify:

- Correct `apiClient` method.
- Correct endpoint path.
- Correct params/body.
- Correct return behavior for `void` and data responses.

Verification commands should include:

- Targeted Vitest tests for the new service files.
- `rg "/api/admin|pending/count" diff.md runbook-integration.md src e2e` to confirm stale references are gone or intentionally documented as historical only.
- `rg "http://localhost:9010" e2e` to confirm remaining hard-coded URLs are removed or intentionally justified.

## Acceptance Criteria

- Missing backend runtime APIs have frontend real service wrappers and unit tests.
- New tests pass.
- Existing real service paths remain unchanged unless a test proves they are wrong.
- Frontend documentation no longer presents `/api/admin/*` or `pending/count` as current API contract.
- E2E backend URL usage follows a shared environment-based pattern where practical.
- No UI behavior changes are introduced.
