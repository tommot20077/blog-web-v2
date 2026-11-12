# Phase 2 System Evidence Checklist

## Setup

- Confirm backend, frontend, PostgreSQL, Redis, Elasticsearch, MinIO, and RabbitMQ are running.
- Create an evidence folder under `logs/manual-phase2-YYYY-MM-DD/`.
- Record the manual test timestamp and the accounts/articles used in the journey checklists.

## Normal Flow

- [ ] Capture service health before executing journey checklists.
- [ ] Capture PostgreSQL row counts before and after Auth, Author/Admin, and Reader journeys.
- [ ] Capture Elasticsearch count/search evidence after publishing and searching an article.
- [ ] Capture Redis key evidence after login and article detail viewing.
- [ ] Capture MinIO object evidence after cover upload.
- [ ] Capture RabbitMQ/backend log evidence around publish/index events.

## Abnormal Flow

- [ ] Capture backend log evidence for rejected auth, permission, upload, and validation requests.
- [ ] Capture Elasticsearch evidence when a searched article is not indexed yet.
- [ ] Capture Redis/refresh-token evidence when refresh is called without a valid cookie.

## Evidence

### Health

- [ ] Backend readiness: `GET http://localhost:9010/actuator/health/readiness`
- [ ] Backend liveness: `GET http://localhost:9010/actuator/health/liveness`
- [ ] OpenAPI: `GET http://localhost:9010/v3/api-docs`
- [ ] Frontend: `http://localhost:5500`

### PostgreSQL

```sql
SELECT COUNT(*) FROM users;
SELECT COUNT(*) FROM articles;
SELECT COUNT(*) FROM comments;
SELECT COUNT(*) FROM user_bookmarks;
SELECT COUNT(*) FROM user_article_likes;
```

### Elasticsearch

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

### Redis

- [ ] Inspect refresh/session keys after login.
- [ ] Inspect reading progress keys after opening article detail.

### MinIO

- [ ] Confirm upload bucket exists.
- [ ] Confirm cover object appears after author upload.
- [ ] Confirm deleted file behavior after file delete test if executed.

### RabbitMQ

- [ ] Check queues/exchanges are healthy.
- [ ] Check backend logs around article publish and search indexing events.

### Backend Logs

- [ ] Capture logs around auth.
- [ ] Capture logs around upload.
- [ ] Capture logs around publish/reject.
- [ ] Capture logs around search indexing.

### Evidence Storage

Save screenshots, API responses, SQL outputs, and log snippets under:

```text
logs/manual-phase2-YYYY-MM-DD/
```

## Cleanup

Ask Yuan before destructive cleanup in PostgreSQL, Elasticsearch, Redis, MinIO, or RabbitMQ.
