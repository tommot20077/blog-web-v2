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

SELECT id, uuid, original_name, storage_key, content_type, category, reference_type, reference_id
FROM files
WHERE reference_type = 'ARTICLE'
  AND reference_id = (SELECT id FROM articles WHERE title = '<manual title>');
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

- Confirm uploaded object exists for `files.storage_key` if the object key is available from DB/API.

RabbitMQ:

- Check backend logs for article publish/index events.

## Cleanup

Ask Yuan before deleting manual articles, uploaded files, search index documents, or MinIO objects.
