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

> 這條界線的判準**不是**「caller 知不知道對方的欄位名」——`CommentRepository` 本來就知道
> `users.nickname`——而是**該 schema 的穩定度**，以及**是否只透過 owner 控制的面存取**。

### 具體原則

- **讀（Read）**：跨模組讀 reference data 可直接 JOIN，避免 N+1
  - 例：`CommentRepository` 列表 SQL 可 `LEFT JOIN users` 取 `nickname` / `avatar_url`
- **寫（Write）**：跨模組寫業務 data 必透過 service
  - 例：`CommentService` 不直接執行 `UPDATE articles SET comment_count = comment_count + 1`，必走 `ArticleService.incrementCommentCount()`
- **計數同步**：使用反正規化欄位（`articles.comment_count`、`comments.like_count` 等）+ service 層原子 UPDATE
- **避免循環依賴**：若 A 模組 service 需要呼叫 B 模組 service，且 B 也需要 A，考慮抽出 Read 層（`XxxQueryService`）以 CQRS-lite 分離 Read/Write 責任
  - 例：`ArticleQueryService`（Read 層）組裝 DTO，注入 `ArticleService` + `ArticleLikeService`，兩者各自不互相依賴
- **集合述詞（Set Predicate）**：凡是用**他模組的欄位**做 filter / sort / paginate / count
  的查詢，一律由 **owner 模組以 facade 方法提供**，caller 不得自行 JOIN 他模組業務表。
  - 例：series 列表要「只列含至少一篇 PUBLISHED 文章的 series」→
    走 `ArticleFacade.countPublishedBySeriesIds()`，不得 `EXISTS (SELECT 1 FROM articles ...)`
  - 若該 facade 方法的傳輸量與內容量成正比（而非與頁大小成正比），
    **必須在 JavaDoc 標註界限與重新評估的門檻**
    （例：`ArticleFacade.java:266,275,284,301` 四個集合述詞方法皆已標註「重評門檻」）
  - 這條規則存在的原因：service interface 回傳的是**物件**、不是還能再加條件的 relation，
    所以跨邊界的集合述詞在舊規範下**無路可走**——這正是 `findings.md` **ARCH-13** 從 4 處
    惡化到 7 處的真因

### `*Facade` 的兩層契約

- **物件級讀取**（`findById` / `findByIds` / `findByUuid`）：**status-agnostic**，
  由 caller 自行判斷可見性
- **集合述詞方法**：可見性**直接內建**，且必須寫進方法名
  （`filterReadableIds`、`countPublishedBySeriesIds`），使契約在呼叫端一眼可辨
  （實作見 `ArticleFacade.java:266-301`，可見性委派 `ArticleVisibility.isReadableBy`，
  單一真相，不重寫一份 SQL 版本）

### 邊界判斷速查

```
Q: 我要在 CommentService 取 article 的 author_id（判斷權限）？
A: article 是業務 data → 走 ArticleQueryService.getAuthorId(articleUuid)

Q: 我要在 CommentRepository SQL 顯示留言者的 nickname？
A: users 是 reference data → 直接 LEFT JOIN users 取 nickname ✅

Q: CommentService 要更新 articles.comment_count？
A: articles 是業務 data → 走 ArticleService.incrementCommentCount(articleId) ✅
```

> **本節規範買到的是概念邊界，不是拆服務能力。** 跨模組的 8 個 `ON DELETE CASCADE`
> 外鍵與共用 `Long` 主鍵才是拆分的真正阻擋，見 `findings.md` **ARCH-30**。
