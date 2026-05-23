# Phase 2 Manual Real DB Checklist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convert the same E2E journeys into manual verification checklists for the real dev database and running frontend/backend.

**Architecture:** Add Markdown checklists under `ai-docs/integration-tests/` that map user steps to UI evidence, API evidence, database evidence, Redis/ES/MinIO evidence, and cleanup. This phase does not add automated tests.

**Tech Stack:** Markdown, Spring Boot dev profile, Vue/Vite dev server, PostgreSQL, Redis, Elasticsearch, MinIO, RabbitMQ, PowerShell.

---

## Manual Verification Contract

This plan creates checklists only.

- Do not delete or mutate dev data without Yuan approval.
- Do not change product code.
- Each checklist must include setup, steps, evidence, and cleanup.
- Evidence must be concrete: URL, API endpoint, SQL query, Redis/ES command, or log path.

## File Structure

Create:

- `ai-docs/integration-tests/2026-05-23-phase2-manual-real-db-index.md`
- `ai-docs/integration-tests/2026-05-23-phase2-auth-checklist.md`
- `ai-docs/integration-tests/2026-05-23-phase2-author-review-checklist.md`
- `ai-docs/integration-tests/2026-05-23-phase2-reader-interaction-checklist.md`
- `ai-docs/integration-tests/2026-05-23-phase2-system-evidence-checklist.md`

Reference:

- `docs/superpowers/specs/2026-05-23-fullstack-e2e-red-design.md`
- `docs/superpowers/plans/2026-05-23-p0-backend-red-e2e-plan.md`
- `docs/superpowers/plans/2026-05-23-p0-fullstack-playwright-red-e2e-plan.md`

## Task 1: Add Manual Checklist Index

**Files:**

- Create: `ai-docs/integration-tests/2026-05-23-phase2-manual-real-db-index.md`

- [ ] **Step 1: Write the index**

Create the file:

```markdown
# Phase 2 Manual Real DB Verification Index

Date: 2026-05-23
Environment: dev profile, real dev database

## Purpose

These checklists verify the same journeys defined by the E2E red-test design against
the real running frontend/backend and real dev database.

## Required Running Services

- Backend: `http://localhost:9010`
- Frontend: `http://127.0.0.1:5500` or `http://localhost:5500`
- PostgreSQL: dev `blog_v2_db`
- Redis: dev Redis
- Elasticsearch: dev Elasticsearch
- MinIO: dev MinIO
- RabbitMQ: dev RabbitMQ

## Checklists

- Auth lifecycle: `2026-05-23-phase2-auth-checklist.md`
- Author/Admin review: `2026-05-23-phase2-author-review-checklist.md`
- Reader interaction: `2026-05-23-phase2-reader-interaction-checklist.md`
- System evidence: `2026-05-23-phase2-system-evidence-checklist.md`

## Rule

Ask Yuan before destructive cleanup in the real dev database.
```

- [ ] **Step 2: Commit index**

```powershell
git status --short
git add ai-docs/integration-tests/2026-05-23-phase2-manual-real-db-index.md
git commit -m "docs(e2e): 新增 phase2 真資料庫手測索引"
```

## Task 2: Auth Manual Checklist

**Files:**

- Create: `ai-docs/integration-tests/2026-05-23-phase2-auth-checklist.md`

- [ ] **Step 1: Write auth checklist**

Create the file:

```markdown
# Phase 2 Auth Manual Checklist

## Setup

- Open frontend: `http://localhost:5500`
- Confirm backend readiness: `GET http://localhost:9010/actuator/health/readiness`
- Use a unique email: `manual-auth-{timestamp}@test.local`

## Normal Flow

- [ ] Register with unique email, username, nickname, and valid password.
- [ ] Confirm UI shows verification-required state.
- [ ] Retrieve verification token/code from dev DB only after Yuan confirms this is acceptable.
- [ ] Verify email through the UI route or API route.
- [ ] Login through UI.
- [ ] Refresh the page and confirm login state remains.
- [ ] Logout and confirm protected routes redirect to login.

## Abnormal Flow

- [ ] Register same email again; expect stable duplicate-email error.
- [ ] Login with wrong password; expect stable auth error.
- [ ] Call refresh without cookie; expect 401.

## Evidence

UI:

- Register page success state.
- Login page error state.
- Authenticated navigation state.

API:

- `POST /api/v1/auth/register`
- `GET /api/v1/auth/verify-email`
- `POST /api/v1/auth/login`
- `POST /api/v1/auth/refresh`
- `POST /api/v1/auth/logout`

SQL:

```sql
SELECT id, email, username, role, status, email_verified
FROM users
WHERE email = '<manual email>';

SELECT id, user_id, type, consumed_at, expires_at
FROM verification_tokens
WHERE user_id = (SELECT id FROM users WHERE email = '<manual email>');
```

Logs:

- Backend log around registration, verification, login, refresh, logout.

## Cleanup

Ask Yuan before deleting the manual user from dev DB.
```

- [ ] **Step 2: Commit auth checklist**

```powershell
git status --short
git add ai-docs/integration-tests/2026-05-23-phase2-auth-checklist.md
git commit -m "docs(e2e): 新增 auth 真資料庫手測清單"
```

## Task 3: Author/Admin Review Manual Checklist

**Files:**

- Create: `ai-docs/integration-tests/2026-05-23-phase2-author-review-checklist.md`

- [ ] **Step 1: Write author/admin checklist**

Create the file:

```markdown
# Phase 2 Author/Admin Review Manual Checklist

## Setup

- Confirm an AUTHOR account exists and is verified.
- Confirm an ADMIN account exists and is verified.
- Confirm at least one category exists, or create one through admin API/UI.
- Prepare one small image file for cover upload.

## Normal Publish Flow

- [ ] Login as AUTHOR.
- [ ] Open `/editor`.
- [ ] Enter title, summary, content, category, and tags.
- [ ] Upload cover image.
- [ ] Save draft.
- [ ] Confirm URL changes to `/editor/{uuid}`.
- [ ] Submit article for review.
- [ ] Open `/my-articles` and confirm status is pending.
- [ ] Login as ADMIN.
- [ ] Open `/admin/review`.
- [ ] Confirm pending article appears.
- [ ] Publish article.
- [ ] Logout or switch to READER/GUEST.
- [ ] Confirm article appears in list/detail.
- [ ] Search title and confirm article appears after indexing.

## Reject/Resubmit Flow

- [ ] Create another AUTHOR draft and submit it.
- [ ] Login as ADMIN.
- [ ] Reject with a concrete reason.
- [ ] Login as AUTHOR.
- [ ] Confirm `/my-articles` shows rejected status and reason.
- [ ] Edit the rejected article.
- [ ] Resubmit.
- [ ] Confirm status returns to pending.

## Abnormal Flow

- [ ] USER or GUEST opens `/editor`; expect redirect or permission error.
- [ ] USER or AUTHOR opens `/admin/review`; expect redirect or permission error.
- [ ] Submit article without title/content; expect validation error.
- [ ] Upload unsupported file type; expect upload error and form content preserved.

## Evidence

API:

- `POST /api/v1/articles`
- `PUT /api/v1/articles/{uuid}`
- `POST /api/v1/files/upload`
- `POST /api/v1/articles/{uuid}/submit`
- `GET /api/v1/admin/articles/pending`
- `POST /api/v1/articles/{uuid}/publish`
- `POST /api/v1/articles/{uuid}/reject`
- `GET /api/v1/search`

SQL:

```sql
SELECT id, uuid, title, status, reject_reason, published_at, author_id
FROM articles
WHERE title LIKE '%<manual title>%'
ORDER BY id DESC;

SELECT id, article_id, file_id, usage_type
FROM article_files
WHERE article_id = (SELECT id FROM articles WHERE title = '<manual title>');
```

Elasticsearch:

```http
GET /blog_articles/_search
{
  "query": {
    "match": {
      "title": "<manual title>"
    }
  }
}
```

MinIO:

- Confirm uploaded object exists for cover image path if object key is available from DB/API.

RabbitMQ:

- Check backend logs for article publish/index events.

## Cleanup

Ask Yuan before deleting manual articles or uploaded files from dev DB/MinIO.
```

- [ ] **Step 2: Commit author/admin checklist**

```powershell
git status --short
git add ai-docs/integration-tests/2026-05-23-phase2-author-review-checklist.md
git commit -m "docs(e2e): 新增 author admin 真資料庫手測清單"
```

## Task 4: Reader Interaction Manual Checklist

**Files:**

- Create: `ai-docs/integration-tests/2026-05-23-phase2-reader-interaction-checklist.md`

- [ ] **Step 1: Write reader checklist**

Create the file:

```markdown
# Phase 2 Reader Interaction Manual Checklist

## Setup

- Confirm a verified READER account exists.
- Confirm at least one PUBLISHED article exists.
- Confirm frontend and backend are running.

## Normal Flow

- [ ] Login as READER.
- [ ] Open home, article list, or search.
- [ ] Open a published article detail page.
- [ ] Like the article.
- [ ] Unlike the article.
- [ ] Bookmark the article.
- [ ] Open `/bookmarks` and confirm the article appears.
- [ ] Remove the bookmark.
- [ ] Return to article detail.
- [ ] Add a top-level comment.
- [ ] Reply to the comment.
- [ ] Refresh page and confirm comments remain.

## Abnormal Flow

- [ ] Logout.
- [ ] Try like/bookmark/comment as GUEST; expect redirect or permission error.
- [ ] Submit empty comment; expect validation error.
- [ ] Open non-existing article UUID; expect error page or API error UI.

## Boundary Flow

- [ ] Double-click like and confirm final state is stable.
- [ ] Double-click bookmark and confirm no duplicate bookmark row.
- [ ] Add enough comments to inspect pagination if data volume allows.

## Evidence

API:

- `POST /api/v1/articles/{articleUuid}/like`
- `DELETE /api/v1/articles/{articleUuid}/like`
- `POST /api/v1/articles/{articleUuid}/bookmark`
- `DELETE /api/v1/articles/{articleUuid}/bookmark`
- `GET /api/v1/users/me/bookmarks`
- `POST /api/v1/articles/{articleUuid}/comments`
- `GET /api/v1/articles/{articleUuid}/comments`

SQL:

```sql
SELECT *
FROM article_likes
WHERE article_id = (SELECT id FROM articles WHERE uuid = '<article uuid>');

SELECT *
FROM bookmarks
WHERE article_id = (SELECT id FROM articles WHERE uuid = '<article uuid>');

SELECT id, uuid, article_id, parent_id, content, deleted_at
FROM comments
WHERE article_id = (SELECT id FROM articles WHERE uuid = '<article uuid>')
ORDER BY id DESC;
```

Redis:

- If reading progress is written during the article view, inspect matching progress keys.

## Cleanup

Ask Yuan before deleting manual comments, likes, or bookmarks from dev DB.
```

- [ ] **Step 2: Commit reader checklist**

```powershell
git status --short
git add ai-docs/integration-tests/2026-05-23-phase2-reader-interaction-checklist.md
git commit -m "docs(e2e): 新增 reader 真資料庫手測清單"
```

## Task 5: System Evidence Checklist

**Files:**

- Create: `ai-docs/integration-tests/2026-05-23-phase2-system-evidence-checklist.md`

- [ ] **Step 1: Write system evidence checklist**

Create the file:

```markdown
# Phase 2 System Evidence Checklist

## Health

- [ ] Backend readiness: `GET http://localhost:9010/actuator/health/readiness`
- [ ] Backend liveness: `GET http://localhost:9010/actuator/health/liveness`
- [ ] OpenAPI: `GET http://localhost:9010/v3/api-docs`
- [ ] Frontend: `http://localhost:5500`

## PostgreSQL

```sql
SELECT COUNT(*) FROM users;
SELECT COUNT(*) FROM articles;
SELECT COUNT(*) FROM comments;
SELECT COUNT(*) FROM bookmarks;
```

## Elasticsearch

```http
GET /_cluster/health
GET /blog_articles/_count
GET /blog_articles/_search
{
  "query": {
    "match_all": {}
  },
  "size": 5
}
```

## Redis

- [ ] Inspect refresh/session keys after login.
- [ ] Inspect reading progress keys after opening article detail.

## MinIO

- [ ] Confirm upload bucket exists.
- [ ] Confirm cover object appears after author upload.
- [ ] Confirm deleted file behavior after file delete test if executed.

## RabbitMQ

- [ ] Check queues/exchanges are healthy.
- [ ] Check backend logs around article publish and search indexing events.

## Backend Logs

- [ ] Capture logs around auth.
- [ ] Capture logs around upload.
- [ ] Capture logs around publish/reject.
- [ ] Capture logs around search indexing.

## Evidence Storage

Save screenshots, API responses, SQL outputs, and log snippets under:

```text
logs/manual-phase2-YYYY-MM-DD/
```
```

- [ ] **Step 2: Commit system evidence checklist**

```powershell
git status --short
git add ai-docs/integration-tests/2026-05-23-phase2-system-evidence-checklist.md
git commit -m "docs(e2e): 新增 phase2 系統證據清單"
```

## Task 6: Final Documentation Check

**Files:**

- No new files.

- [ ] **Step 1: Run markdown path check**

```powershell
Get-ChildItem ai-docs\integration-tests\2026-05-23-phase2-*.md |
  Select-Object Name, Length, LastWriteTime
```

Expected:

- Five checklist files are listed.

- [ ] **Step 2: Run diff check**

```powershell
New-Item -ItemType Directory -Force logs | Out-Null
git diff --check 2>&1 | Tee-Object -FilePath logs\phase2-manual-checklist-diff-check.log
```

Expected:

- Exit code `0`.

## Final Acceptance

- Manual checklists cover Auth, Author/Admin, Reader, and system evidence.
- Each checklist has setup, normal flow, abnormal flow, evidence, and cleanup.
- Destructive cleanup requires Yuan approval.
- The checklists map to the automated red-test journeys.
