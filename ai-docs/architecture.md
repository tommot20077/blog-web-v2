# Architecture & Design

## Core Design Philosophy

*   **Modular Monolith**: Distinct modules (`user`, `article`, `file`, `search`) with strict boundaries.
*   **Facade Pattern**: Modules interact ONLY via Service Interfaces, never direct Repository/SQL access.
*   **DDD-Lite**: Rich Domain Models (Entity encapsulates logic), Aggregate Roots enforce consistency.

## Tech Stack & Decisions

*   **Security**:
    *   **ECDSA (ES256)** for JWT (Never use RSA).
    *   **Stateful JWT**: Redis `user:auth:{id}` stores Token Version. Validation requires strictly checking this version.
*   **Infrastructure**: K3s (Kubernetes), MinIO (S3 compatible), PostgreSQL, Redis, RabbitMQ, Elasticsearch.
*   **API**: Always return `ApiResponse<T>`. All external IDs must be **UUIDs**.

---

## Cross-Module Boundary Rules（中庸級別）

本專案採 modular monolith 架構，跨模組邊界遵守「中庸級別」規則——比 micro-service 寬鬆（允許 DB JOIN），比 big-ball-of-mud 嚴格（寫操作必走 service）。

### Reference Data 可 JOIN，業務 Data 必走 service

| 表性質 | 範例 | 跨模組訪問方式 |
|-------|------|--------------|
| **Reference Data**（穩定、全系統共用） | `users`、`tags` | ✅ 可在 SQL 直接 JOIN（僅讀取） |
| **業務 Data**（owner module 控制變更） | `articles`、`comments` | ❌ 必走 owner module 的 service interface |

### 具體原則

- **讀（Read）**：跨模組讀 reference data 可直接 JOIN，避免 N+1
  - 例：`CommentRepository` 列表 SQL 可 `LEFT JOIN users` 取 `nickname` / `avatar_url`
- **寫（Write）**：跨模組寫業務 data 必透過 service
  - 例：`CommentService` 不直接執行 `UPDATE articles SET comment_count = comment_count + 1`，必走 `ArticleService.incrementCommentCount()`
- **計數同步**：使用反正規化欄位（`articles.comment_count`、`comments.like_count` 等）+ service 層原子 UPDATE
- **避免循環依賴**：若 A 模組 service 需要呼叫 B 模組 service，且 B 也需要 A，考慮抽出 Read 層（`XxxQueryService`）以 CQRS-lite 分離 Read/Write 責任
  - 例：`ArticleQueryService`（Read 層）組裝 DTO，注入 `ArticleService` + `ArticleLikeService`，兩者各自不互相依賴

### 邊界判斷速查

```
Q: 我要在 CommentService 取 article 的 author_id（判斷權限）？
A: article 是業務 data → 走 ArticleQueryService.getAuthorId(articleUuid)

Q: 我要在 CommentRepository SQL 顯示留言者的 nickname？
A: users 是 reference data → 直接 LEFT JOIN users 取 nickname ✅

Q: CommentService 要更新 articles.comment_count？
A: articles 是業務 data → 走 ArticleService.incrementCommentCount(articleId) ✅
```
