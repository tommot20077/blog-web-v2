# E2E User Journey Mock Design

Date: 2026-05-16
Status: Approved for design by Yuan
Scope: Playwright mock-mode E2E scenario design for the blog-web-v2 system

## Goal

Build a stable Playwright mock-mode E2E test suite that validates user journeys across
the frontend without depending on remote database state. The suite should prove that
core flows, routing, UI state, error handling, permissions, and persistence behavior
work from a user's point of view.

Real backend integration remains valuable, but it is not the main axis of this MVP.
Integration tests should stay as a smaller contract smoke layer for API compatibility
and infrastructure confidence.

## Strategy

Use a hybrid matrix:

- User journeys are the primary structure.
- Feature-domain specs cover states that do not naturally belong in one role journey.
- Mock data is centralized and reset per spec/test to avoid state leakage.
- API failure branches use Playwright route overrides scoped to one test.

This keeps the suite readable as product behavior while still covering edge states.

## Test Organization

### Journey Specs

`reader-journey.spec.ts`

- Browsing from home to article list and article detail.
- Search and tag navigation.
- Like, bookmark, comment, and related article navigation.
- Unauthenticated interaction guards.
- Empty search results and empty bookmarks.

`author-journey.spec.ts`

- Login as author.
- Create draft, upload cover, save draft, and land on `/editor/{uuid}`.
- Edit an existing draft from `/my-articles`.
- Submit for review and verify status in the article list.
- Verify draft, pending, rejected, and published states.
- Preserve form data when save, upload, or submit fails.
- Block USER and unauthenticated access to author-only routes.

`admin-journey.spec.ts`

- Login as admin.
- View pending review list.
- Publish an article and verify it leaves the queue.
- Reject an article and verify the author can see the rejected reason.
- Verify empty queue and load failure states.
- Block non-admin and unauthenticated access to admin routes.

### Supplemental Feature Specs

`auth-boundary.spec.ts`

- Guest-only route handling.
- Auth-required route redirect behavior.
- Role mismatch behavior.
- Expired or missing auth state UI behavior in mock mode.

`content-discovery.spec.ts`

- Home trending articles, latest articles, and hot tags.
- Tag detail page, related tags, follow/unfollow behavior.
- Related article section on article detail.
- Archive and author pages.
- Empty states for tag, archive, and author content.

`editor-resilience.spec.ts`

- Draft load failure.
- Category list load failure.
- Tag suggestion empty state and failure.
- Save double-click does not create duplicate requests.
- Switching `/editor/{a}` to `/editor/{b}` does not retain stale content.

`system-states.spec.ts`

- 404 page.
- 500 page.
- Generic API 500 toast.
- Network timeout or unreachable API state.
- Loading skeleton resolves into content without overlaying final UI.

## Scenario Template

Every feature should be designed through this table before writing tests:

| Type | Question | Expected Evidence |
| --- | --- | --- |
| Happy | Can the user complete the intended task? | UI updates, route changes, visible data, and action feedback are correct. |
| Empty | What happens when there is no data? | Empty state is clear and no error UI appears. |
| Error | What happens when the API or network fails? | Toast or error state appears and existing user input/state is preserved where appropriate. |
| Permission | What happens when the user is logged out or lacks role? | Redirect or permission message matches the route contract. |
| Persistence | What happens on refresh or revisit? | State is restored from mock data or reset according to product rules. |

Core features should cover all five where meaningful. Smaller features should cover at
least happy plus one of empty, error, or permission.

## Mock State Design

Mock mode needs a single resettable state source for E2E:

- `resetMockState()` restores deterministic seed data before each spec or test group.
- Users:
  - reader with and without bookmarks, comments, and followed tags.
  - author with one draft, one pending review article, one published article, and one rejected article.
  - admin with a pending review queue.
- Articles:
  - with cover image.
  - without cover image.
  - with multiple tags and categories.
  - with long Markdown content.
  - with sanitized unsafe Markdown payload.
- Tags:
  - tag with articles.
  - tag with no articles.
  - followed tag.
  - unfollowed tag.
- Failures:
  - default mock data should stay successful.
  - failure cases should be introduced with per-test `page.route()` overrides.

## Shared E2E Helpers

Use helpers to keep specs focused on user behavior:

- `loginAs(page, role)` for reader, author, and admin login state.
- `expectToast(page, text)` for consistent toast assertions.
- `expectAuthRedirect(page, returnUrl?)` for auth-required route behavior.
- `mockApiFailure(page, endpoint, status, body)` for scoped API failures.
- `goToArticle(page, kind)` for selecting deterministic article fixtures.
- `resetMockState(page)` or equivalent setup helper for test isolation.

Specs should not inline large JSON payloads. Use factories or named fixtures.

## P0 Scope

P0 establishes the MVP user-journey suite.

Reader:

- Browse from home to list to article detail.
- Search and tag filtering.
- Like and unlike from article detail.
- Bookmark article and see it in `/bookmarks`.
- Remove bookmark from `/bookmarks`.
- Add a comment and see it in the thread.
- Follow or unfollow a tag when UI supports it.
- Unauthenticated like, bookmark, comment, and tag follow are blocked.
- Search no-result and bookmarks empty states render correctly.

Author:

- Create draft and save.
- Upload cover image in mock mode.
- Return to `/my-articles` and edit existing draft.
- Submit article for review.
- See draft, pending, rejected, and published statuses.
- Failed save, failed upload, and failed submit preserve form state.
- USER and unauthenticated sessions cannot access `/editor` or `/my-stats`.

Admin:

- See pending review list.
- Publish an article and see it leave the pending list.
- Reject an article with reason and see it leave the pending list.
- Author can see rejected reason in their article list.
- Empty pending list renders correctly.
- Pending list load failure renders an error state.
- USER and AUTHOR cannot access `/admin/review`.

## P1 Scope

P1 expands coverage after P0 is stable:

- Home trending, latest, and hot tag sections.
- Related articles on article detail.
- Archive page.
- Author page.
- Tag detail empty and populated states.
- Editor category failure and tag suggestion failure.
- Editor route switching between two article UUIDs.
- Global 404, 500, generic API failure, and timeout states.

## P2 Scope

These are excluded from the MVP Playwright suite unless UI workflows become mature:

- Series CRUD and article assignment.
- Article version manual snapshot, promote, restore, and delete.
- Version preference.
- Admin category and tag CRUD.
- Admin search reindex.
- Highlight CRUD.

They can stay covered by service tests, backend tests, or later feature-specific
Playwright specs once the UI is a real user workflow.

## Real Backend Contract Smoke

Mock E2E does not replace real integration. Keep a small integration layer for:

- Auth login and refresh.
- Article lifecycle API.
- File upload and delete.
- Search/tag contract.
- Settings profile update.

These tests should use the real backend and remote dev database only when explicitly
running integration mode. They should not require any local database service.

## Acceptance Criteria

- The MVP mock suite is organized by the hybrid matrix above.
- Each P0 journey has happy, empty, error, and permission coverage.
- Mock data is resettable and centralized.
- Failure scenarios are test-local and do not mutate global fixtures.
- Test names describe user-visible behavior in Traditional Chinese.
- The suite can run without starting a local database service.
- Real backend smoke tests remain separate from mock MVP tests.
