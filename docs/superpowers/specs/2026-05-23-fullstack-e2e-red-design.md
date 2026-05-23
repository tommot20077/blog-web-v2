# Full-stack E2E Red Test Design

Date: 2026-05-23
Status: Draft for Yuan review
Scope: blog-web-v2 backend + blog-web-v2-front-end full-stack E2E red-test scenario design

## Goal

Design the important end-to-end user journeys for the blog system and turn them into
executable red tests in a later implementation phase.

Phase 1 intentionally stops at red:

- Write executable E2E scenarios.
- Run them and capture meaningful failing output.
- Do not modify backend or frontend product code to make the tests green.
- Keep red tests isolated from existing blocking CI until the team decides to promote them.

Phase 2 uses the same journeys for manual verification against the real dev database.

## Current Baseline

The project already has separate E2E layers:

- Backend Java E2E under `blog-start/src/test/java/dowob/xyz/blog/e2e`.
- Backend `-Pe2e` profile includes `**/*E2E.java` and excludes them from normal test runs.
- Backend E2E uses Testcontainers for PostgreSQL, Redis, RabbitMQ, MinIO, and Elasticsearch.
- Frontend Playwright has mock mode under `e2e/mock` and integration mode under `e2e/integration`.
- Frontend integration mode currently expects a backend to be running; it does not own backend/Testcontainers startup.
- API contract report dated 2026-05-16 shows no frontend-only or backend-only endpoints.

This design keeps those layers and adds a thin full-stack red-test layer.

## Test Layer Strategy

### 1. Backend Testcontainers Red E2E

Use this for deep behavior and data-state checks:

- API contract behavior.
- Database state transition.
- Redis state.
- RabbitMQ async event propagation.
- MinIO file metadata/object behavior.
- Elasticsearch indexing and search behavior.
- Permission, validation, and boundary cases.

This layer can contain many scenarios because failures are easier to diagnose.

### 2. Frontend Playwright Mock E2E

Keep this for UI behavior that does not require real infrastructure:

- Route guards.
- Empty/loading/error states.
- Form state preservation.
- Toast behavior.
- Visual interaction state.
- UI-only boundary behavior.

Mock E2E remains useful because it is stable and fast.

### 3. Full-stack Playwright Red E2E

Add this as the cross-system golden journey layer:

- Browser drives the real frontend.
- Frontend calls the real backend.
- Backend connects to Testcontainers-managed infrastructure.
- Test data is created by the test flow, not by the dev database.

This layer should stay thinner than backend E2E. It proves the whole product path works
from the user's point of view.

### 4. Contract Red E2E

Use this when the risk is not a single user journey but frontend/backend drift:

- Request body shape.
- Response envelope shape.
- Error code and status mapping.
- Enum values.
- Pagination shape.
- Multipart upload contract.

## Red Test Rules

Red tests must be real failures, not placeholders.

Acceptable red failures:

- The flow reaches an assertion and the product behavior is not implemented yet.
- The frontend and backend disagree on a contract.
- A backend state transition is missing or inconsistent.
- A UI state does not reflect the backend result.
- An async effect does not appear within the defined wait window.

Unacceptable red failures:

- Compilation error.
- Syntax error.
- Missing dependency.
- Incorrect selector.
- Test cannot start the app.
- Test data cannot be prepared because the harness is broken.
- `fail("TODO")` or equivalent placeholder failure.

Every red run must write output under `logs/`.

## Priority Rules

P0:

- Crosses frontend and backend.
- Crosses at least two user roles or major modules.
- Writes data.
- Represents a core product path.
- Has high regression risk.

P1:

- Important and user-visible, but can be verified by a smaller layer first.
- Usually single-role or single-module heavy.
- Valuable for confidence after P0 is stable.

P2:

- Deep feature, low-frequency workflow, admin tooling, or advanced boundary.
- Should be designed now, but may be implemented as red tests later.

## P0 Journeys

### P0-1 Auth Lifecycle

Normal flow:

- Guest registers.
- User verifies email by token or code.
- User logs in.
- Access token works.
- Refresh token cookie can renew access.
- User logs out.

Abnormal flow:

- Duplicate email registration fails.
- Wrong password fails.
- Unverified account cannot log in or cannot use protected routes, depending on product rule.
- Refresh without cookie fails.

Boundary flow:

- Weak password fails validation.
- Invalid verification token fails.
- Expired verification token fails.
- Reusing a consumed verification token fails idempotently.

Contract points:

- `POST /api/v1/auth/register`
- `GET /api/v1/auth/verify-email`
- `POST /api/v1/auth/verify-email-code`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`

Data points:

- `users` status.
- `verification_tokens` lifecycle.
- Refresh token cookie.
- Redis refresh/session state if applicable.

Expected red-test focus:

- Full-stack email verification UX and backend verification token behavior.
- Refresh cookie behavior through browser and backend.

### P0-2 Author Draft To Review

Normal flow:

- Author logs in.
- Author opens editor.
- Author creates a draft.
- Author uploads cover image.
- Author chooses category and tags.
- Author submits article for review.
- My Articles shows pending review status.

Abnormal flow:

- Guest cannot open editor.
- USER cannot open editor.
- Missing title/content fails validation.
- Invalid file type fails.
- Oversized file fails.
- Duplicate submit does not create duplicate review state.

Boundary flow:

- Long title.
- Special-character tags.
- Empty category list.
- Cover image replacement.
- Save after upload failure preserves form content.

Contract points:

- `POST /api/v1/articles`
- `PUT /api/v1/articles/{uuid}`
- `POST /api/v1/articles/{uuid}/submit`
- `POST /api/v1/files/upload`
- `GET /api/v1/categories`
- `GET /api/v1/tags/suggest`

Data points:

- Article status transition to `DRAFT` and `PENDING_REVIEW`.
- Tag auto-creation.
- File metadata.
- MinIO object existence.

Expected red-test focus:

- Real multipart upload from UI to backend to MinIO.
- UI article status matches backend status.

### P0-3 Admin Publish To Reader Visibility

Normal flow:

- Admin logs in.
- Admin sees pending review article.
- Admin publishes article.
- Reader sees article on list/detail.
- Reader can find article through search after indexing.

Abnormal flow:

- USER/AUTHOR cannot access admin review page.
- Publishing a non-pending article fails.
- Publishing missing article fails.

Boundary flow:

- Empty pending queue renders correctly.
- Pending queue pagination.
- Search waits for async indexing within a bounded window.

Contract points:

- `GET /api/v1/admin/articles/pending`
- `POST /api/v1/articles/{uuid}/publish`
- `GET /api/v1/articles`
- `GET /api/v1/articles/{uuid}`
- `GET /api/v1/articles/slug/{slug}`
- `GET /api/v1/search`

Data points:

- Article status transition to `PUBLISHED`.
- `published_at`.
- RabbitMQ article event.
- Elasticsearch document.

Expected red-test focus:

- Backend async event and ES index visibility from the UI journey.

### P0-4 Admin Reject And Author Resubmit

Normal flow:

- Admin rejects pending article with reason.
- Author sees rejected status and reason.
- Author edits article.
- Author resubmits article.
- Article returns to pending review.

Abnormal flow:

- Empty reject reason fails.
- Non-admin reject fails.
- Non-owner edit fails.

Boundary flow:

- Long reject reason.
- Rejected article keeps editable content.
- Resubmission clears or supersedes old rejection state according to product rule.

Contract points:

- `POST /api/v1/articles/{uuid}/reject`
- `GET /api/v1/articles/me`
- `GET /api/v1/articles/{uuid}/edit`
- `PUT /api/v1/articles/{uuid}`
- `POST /api/v1/articles/{uuid}/submit`

Data points:

- Article status transition from `PENDING_REVIEW` to `REJECTED` to `PENDING_REVIEW`.
- Rejection reason persistence.

Expected red-test focus:

- Rejection reason visibility and state transition across Admin and Author UI.

### P0-5 Reader Discovery And Interaction

Normal flow:

- Reader logs in.
- Reader searches or opens article list.
- Reader opens article detail.
- Reader likes article.
- Reader bookmarks article.
- Reader creates a comment.
- Reader replies to a comment.
- Reader unlikes and removes bookmark.

Abnormal flow:

- Guest interactions are blocked.
- Empty comment fails.
- Interacting with missing article fails.
- Liking a deleted comment fails.

Boundary flow:

- Like is idempotent.
- Bookmark is idempotent.
- Comment pagination.
- Article with no comments.
- Empty search result.

Contract points:

- `GET /api/v1/search`
- `GET /api/v1/articles/{uuid}`
- `POST /api/v1/articles/{articleUuid}/like`
- `DELETE /api/v1/articles/{articleUuid}/like`
- `POST /api/v1/articles/{articleUuid}/bookmark`
- `DELETE /api/v1/articles/{articleUuid}/bookmark`
- `GET /api/v1/users/me/bookmarks`
- `GET /api/v1/articles/{articleUuid}/comments`
- `POST /api/v1/articles/{articleUuid}/comments`
- `POST /api/v1/comments/{uuid}/like`

Data points:

- Article likes.
- Bookmarks.
- Comments and replies.
- Reading progress in Redis if the UI writes it during the journey.

Expected red-test focus:

- UI state remains consistent after idempotent like/bookmark operations.

### P0-6 Permission Guardrail

Normal flow:

- Guest can view public pages.
- Reader can view protected reader pages.
- Author can access editor and stats.
- Admin can access review page.

Abnormal flow:

- Guest protected route redirects to login.
- Reader cannot access editor/admin.
- Author cannot access admin review.
- Deleted account token fails.
- Expired token refresh behavior matches product rule.

Boundary flow:

- Login returns user to original route.
- Logged-in user visiting guest-only routes redirects to home.
- Refresh failure clears auth state.

Contract points:

- Frontend router metadata.
- `GET /api/v1/users/me`
- `POST /api/v1/auth/refresh`
- Protected endpoint 401/403 envelope.

Data points:

- JWT role.
- User status.
- Refresh token state.

Expected red-test focus:

- Browser route guards and backend authorization agree.

## P1 Journeys

### P1-1 User Settings And Account Lifecycle

- Update profile.
- Change password.
- Old password fails after change.
- Delete account.
- Deleted account cannot use old token.
- Boundary: empty nickname, long bio, wrong current password.

Primary layer: backend Testcontainers red E2E, plus one full-stack Playwright path.

### P1-2 File Management And Quota

- Upload file.
- List my files.
- Delete file.
- Check quota.
- Boundary: quota exceeded, unsupported MIME, missing multipart part.

Primary layer: backend Testcontainers red E2E.

### P1-3 Comment Management

- Edit comment within allowed window.
- Admin edit/delete behavior if supported.
- Soft delete leaves placeholder.
- Reply tree remains readable.
- Boundary: edit window expired, deleted parent with visible replies.

Primary layer: backend Testcontainers red E2E, plus UI smoke.

### P1-4 Reading Progress And Highlight

- Reading progress writes to Redis.
- Reopening article restores progress.
- Create/update/delete highlight.
- Boundary: invalid range, overlapping highlight, deleted article.

Primary layer: backend Testcontainers red E2E.

### P1-5 Tag And Discovery

- Tag suggest.
- Hot tags.
- Follow/unfollow tag.
- Tag detail page.
- Boundary: special characters, empty tag, no article under tag.

Primary layer: full-stack Playwright red E2E for discovery, backend E2E for data.

### P1-6 Public Content Pages

- Home latest/trending.
- Archive.
- Author page.
- Bookmarks page.
- Stats page.
- Boundary: empty state, pagination, invalid slug/handle.

Primary layer: frontend Playwright mock/integration red E2E.

## P2 Journeys

### P2-1 Series

- Create series.
- Add article to series.
- Reorder article.
- Remove article.
- Delete series.
- Boundary: duplicate position, published/private article visibility.

### P2-2 Article Versioning

- Manual snapshot.
- Auto snapshot visibility.
- Restore version.
- Promote auto to manual.
- Delete version.
- Boundary: restore stale version, delete active/latest version.

### P2-3 Version Preference

- Read effective preference.
- Update user override.
- Reset key to system default.
- Boundary: invalid key, invalid value type.

### P2-4 Admin Taxonomy

- Create/update/delete category.
- Update/delete tag.
- Boundary: slug collision, deleting category used by article.

### P2-5 Admin Search Maintenance

- Trigger reindex.
- Search result appears after reindex.
- Boundary: repeated reindex, ES temporarily unavailable.

### P2-6 Concurrency And Resilience

- Double-click submit.
- Two users interacting with the same article.
- Refresh token rotation race.
- Network retry behavior.
- Backend timeout and frontend recovery.

## Manual Phase 2 Mapping

Every automated red journey should have a matching manual checklist:

- User role.
- Initial data.
- UI route.
- Operation steps.
- Expected UI evidence.
- Expected API evidence.
- Expected database/Redis/ES/MinIO evidence.
- Cleanup notes.

Manual Phase 2 uses the real dev database and real running frontend/backend, not
Testcontainers. It validates that the same product journey works in the deployed-like
development environment.

## CI Direction

The red suites must be opt-in first:

- Backend red E2E can use a dedicated Maven profile or test naming convention.
- Full-stack Playwright red E2E can use a dedicated project/tag.
- CI can collect reports as non-blocking artifacts first.
- Promotion to blocking CI requires explicit decision after tests are made green.

Blocking CI should continue to run existing unit, integration, backend E2E, and stable
Playwright suites without inheriting intentional red failures.

## Open Decisions

1. Full-stack orchestration ownership:
   - Playwright starts backend and backend starts Testcontainers.
   - Or Java/Testcontainers starts backend and emits connection info for Playwright.
   - Or CI uses a compose-like wrapper with Testcontainers-compatible services.

2. Email verification strategy:
   - Read token/code directly from database in tests.
   - Or inspect a fake SMTP/MailHog service.
   - Or expose a test-only helper endpoint in test profile only.

3. Red suite naming:
   - Backend: `*RedE2E.java`, Maven profile `red-e2e`, or JUnit tag.
   - Frontend: Playwright project `fullstack-red`, spec folder `e2e/fullstack-red`, or tag.

4. CI behavior:
   - Non-blocking report-only job.
   - Manual workflow dispatch.
   - Scheduled red-suite run.

5. Data cleanup:
   - Per-test database cleanup.
   - Per-suite cleanup with deterministic test users.
   - Container-per-suite isolation.

## Acceptance Criteria For Phase 1

- P0/P1/P2 scenarios are written as executable tests according to priority.
- Tests can compile and start.
- Failures reach assertions or product-contract checks.
- Failure output is saved under `logs/`.
- Product code is not changed to make these tests green.
- Existing blocking CI remains green because intentional red tests are isolated.
- The same scenario list can be reused for Phase 2 manual real-database verification.
