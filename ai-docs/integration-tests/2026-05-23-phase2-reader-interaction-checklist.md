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
FROM user_article_likes
WHERE article_id = (SELECT id FROM articles WHERE uuid = '<article uuid>');

SELECT *
FROM user_bookmarks
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
