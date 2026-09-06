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
  - 若該 facade 方法的輸入無上界（傳輸量與內容量成正比，而非與頁大小成正比），
    **必須在實作端經 `BatchedQuery` 切批，不得只在 JavaDoc 標註門檻**——
    JavaDoc 標註是給 reviewer 看的，切批才是給執行期用的
    （例：`countPublishedBySeriesIds` / `filterReadableIds`；回傳單一物件的
    `findPrevPublishedInSeries` / `findNextPublishedInSeries` 傳輸量不隨內容量成長，不適用本款）
  - **切批只對「跨批可合併」的查詢安全**：合法形狀為集合聯集、key 不重疊的 Map 合併，
    以及呼叫端以 `groupingBy` 消費、且同一 key 的列必落在同一批者。
    帶 `ORDER BY` ＋ `LIMIT` 或跨批聚合（`SUM` / `AVG` / `DISTINCT`）者**禁止切批**——
    各批的前 N 名合併不等於全域前 N 名，切下去會**靜默給出錯誤答案**，比不切批更危險
    （現存唯一該禁的是 `ArticleRecommendMapper.xml` 的 `findByTagIds`）
  - 「以頁大小為界」不等於有上界：分頁 `size` 由 client 決定，其上界來自
    `PageQuery`（`blog-common/api/request`）的 `MAX_SIZE`，而非呼叫端自律。
    新增分頁端點一律收 `PageQuery`，不得自行宣告 `@RequestParam int size`
  - 跨模組 SQL 一律寫 `NOT EXISTS`，**不得寫 `NOT IN`**：子查詢結果含 NULL 時，
    三值邏輯下 `NOT IN` 恆為空集合，且 planner 因須保留該語意**無法**轉成 anti-join
  - 這條規則存在的原因：service interface 回傳的是**物件**、不是還能再加條件的 relation，
    所以跨邊界的集合述詞在舊規範下**無路可走**——這正是 `findings.md` **ARCH-13** 從 4 處
    惡化到 7 處的真因

### `*Facade` 的兩層契約

- **物件級讀取**（`findById` / `findByIds` / `findByUuid`）：**status-agnostic**，
  由 caller 自行判斷可見性
- **集合述詞方法**：可見性**直接內建**，且必須寫進方法名
  （`filterReadableIds`、`countPublishedBySeriesIds`），使契約在呼叫端一眼可辨
  （可見性委派 `ArticleVisibility.isReadableBy` 這份單一真相，不重寫一份 SQL 版本）
- **`Published*` 與 `Readable*` 不是同義詞**：`Published*`（`countPublishedBySeriesIds`、
  `findPrevPublishedInSeries`）是**狀態字面量**，與是誰在問無關；`Readable*`
  （`filterReadableIds`）是**依觀看者身分而定的可見性政策**（PUBLISHED，或作者本人，或
  ADMIN），方法簽章必須帶 viewer identity。混淆兩者是草稿外洩或內容誤藏的成因，命名前先問
  方法實際做的是哪一種，選對應的字。

### 述詞三格分類與各自的退路

跨模組查詢的正確軸不是「要不要 JOIN」，而是**這個述詞的真相由誰擁有、那份真相有幾個副本**。

| 述詞性質 | 判準 | 例 | 跨模組作法 | 效能門檻觸發時的退路 |
|---|---|---|---|---|
| reference data 投影 | schema 穩定、只讀 | `users.nickname` | 直接 JOIN | 不變 |
| 狀態字面量集合述詞 | 述詞**不含** viewer 參數 | `countPublishedBySeriesIds` | owner facade | **可退到 owner 發布的唯讀 view ＋ JOIN**，真相仍單份 |
| viewer-dependent 政策 | 述詞**含** viewer 身分 | `filterReadableIds` | owner facade | **不得下推**——下推即政策第二份 |

> **方案 B（DB view ＋ JOIN）的否決只涵蓋第三格。**
> `backlog/2026-09-04-cross-module-set-predicate.md` §2.4 以「可見性 viewer-dependent，
> view 包不住」否決 B，該理由對 `Readable*` 成立，但對 `Published*` **不成立**——
> 後者是狀態字面量、不含 viewer 參數，view 完全包得住，且 view 與 facade 方法在
> 真相歸屬上等價（都是 owner 控制的面），差別只在 view 保留了 relation 的可組合性。
> 未來重評時**必須先判定該述詞落在哪一格**，不得整批沿用一個不適用的否決。

> **代價要記在帳上**：facade 回傳的是物件、不是 relation，跨過邊界那一刻 DB 的
> 關聯代數被截斷，`EXISTS` 這類 semi-join 只能在 JVM 裡手工重做——失去「找到就停」
> 與「不必物化中間結果」兩個性質，分頁形狀從 O(頁) 變成 O(集合)。
> 現行量級下這筆交易划算（換掉的是一整類「下架沒下乾淨」的安全事故），但它會
> **突然變貴而非漸進變貴**，因為那是全量載入的形狀。

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
