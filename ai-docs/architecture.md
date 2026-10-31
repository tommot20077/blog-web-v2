# Architecture & Design

## Core Design Philosophy

*   **Modular Monolith**: Distinct modules (`user`, `article`, `file`, `search`) with strict boundaries.
*   **Facade Pattern**: Modules interact ONLY via Service Interfaces, never direct Repository/SQL access.
*   **DDD-Lite**: Rich Domain Models (Entity encapsulates logic), Aggregate Roots enforce consistency.

## Tech Stack & Decisions

*   **Security**:
    *   **ECDSA (ES256)** for JWT (Never use RSA).
    *   **Stateful JWT**: Redis `user:auth:{uuid}` stores Token Version. Validation requires strictly checking this version.
*   **Infrastructure**: K3s (Kubernetes), SeaweedFS (S3 compatible; replaced the discontinued MinIO community edition on 2026-09-27 — the backend still connects through the MinIO Java SDK, which is a generic S3 client, and keeps the `minio.*` config keys), PostgreSQL, Redis, RabbitMQ, Elasticsearch.
*   **API**: Always return `ApiResponse<T>`. All external IDs must be **UUIDs**.

---

## Cross-Module Boundary Rules（中庸級別）

本專案採 modular monolith 架構，跨模組邊界遵守「中庸級別」規則——比 micro-service 寬鬆（共用一個資料庫、
少數 reference data 可 JOIN），比 big-ball-of-mud 嚴格（跨模組讀寫一律走 owner 的 facade，模組之間沒有 FK）。

### 跨模組識別與資料完整性

- **識別一律 UUID**：模組內部主鍵維持 `BIGINT`（Long），但 Long **不得跨越模組邊界**——
  他模組的參照欄位存 owner 的 `uuid`（例：`comments.article_uuid`、`articles.author_uuid`），
  facade 方法的參數與回傳、事件 payload、`AuthUser` principal 一律以 UUID 表達識別
  - `AuthUser` 只有 `uuid` 與 `role`；user 模組需要自己的主鍵時，以 uuid 在模組內查出
- **跨模組不得有 FK**：外鍵只能指向同模組的表（例：`comment_likes → comments` 可以，`comments → articles` 不行）。
  理由：跨模組 FK 讓刪除語意由 DB 隱性決定、把兩個模組的 schema 綁死，也是拆服務的真正阻擋（ARCH-30）
- **刪除連動 = 事件 ＋ 每日校正**：
  1. owner 刪除後發事件（例：`ArticleDeletedEvent`，帶 uuid 與 `eventId`），參照方以 `ConsumerExecutor` 消費並清理自己的資料；consumer 必須冪等
  2. 參照方另有一個繼承 `OrphanReconciliationJob` 的每日排程，以 owner 的 `filterExistingUuids`（不分狀態的存在性查詢）找出孤兒並清理，
     補上事件遺失的情況；框架在 owner 回傳異常（例如全部判定為孤兒）時中止並告警，**寧可不刪也不誤刪**
  3. 清理的語意由參照方決定：可刪除自己的列，也可只是把參照欄位設 NULL（例：series 刪除後，文章只清空 `series_uuid`，絕不刪文章）
- **使用者顯示資訊**（暱稱、頭像）：以 `UserFacade.getDisplayInfoByUuids` 每頁**一次**批次取得，不得逐筆查詢；
  facade 不依使用者狀態過濾，是否顯示由呼叫端決定

### Reference Data 可 JOIN，業務 Data 必走 facade

| 表性質 | 範例 | 跨模組訪問方式 |
|-------|------|--------------|
| **Reference Data**（穩定、全系統共用） | `tags`、`article_tags` | ✅ **僅 article 模組**可在 SQL 直接 JOIN（僅讀取）；其他模組走 facade |
| **業務 Data**（owner module 控制變更） | `users`、`articles`、`comments`、`series`…… | ❌ 必走 owner module 的 facade |

> `tags`／`article_tags` 維持 reference data 是 Yuan 2026-10-31 的決定：article 的列表與推薦 SQL 以 tag 過濾、排序，
> 改走 facade 會把 semi-join 搬進 JVM（見下方「代價」）。拆服務時改為 `TagFacade`。
> `users` 原本也是 reference data，2026-10 的 UUID 重構改為一律走 `UserFacade`——讀取使用者資料的面只剩 user 模組控制的那一個。

> 這條界線的判準**不是**「caller 知不知道對方的欄位名」，而是**該 schema 的穩定度**，以及**是否只透過 owner 控制的面存取**。

### 具體原則

- **讀（Read）**：跨模組讀業務 data 走 owner facade；需要多筆時用批次方法，每頁一次，避免 N+1
  - 例：留言列表顯示留言者暱稱 → 收集該頁的 `userUuid`，呼叫一次 `UserFacade.getDisplayInfoByUuids`
- **寫（Write）**：跨模組寫業務 data 必透過 facade
  - 例：comment 模組不直接執行 `UPDATE articles SET comment_count = comment_count + 1`，必走 `ArticleFacade.incrementCommentCount(articleUuid)`
- **計數同步**：使用反正規化欄位（`articles.comment_count`、`comments.like_count` 等）+ owner 端原子 UPDATE；
  參照方的寫入以 `INSERT ... ON CONFLICT DO NOTHING` 達成冪等，**只在實際插入／刪除一列時**才呼叫 owner 調整計數
- **避免循環依賴**：若 A 模組 service 需要呼叫 B 模組 service，且 B 也需要 A，考慮抽出 Read 層（`XxxQueryService`）以 CQRS-lite 分離 Read/Write 責任
  - 例：`ArticleQueryService`（Read 層）組裝 DTO，注入 `ArticleService` + `ArticleLikeService`，兩者各自不互相依賴
- **集合述詞（Set Predicate）**：凡是用**他模組的欄位**做 filter / sort / paginate / count
  的查詢，一律由 **owner 模組以 facade 方法提供**，caller 不得自行 JOIN 他模組業務表。
  - 例：series 列表要「只列含至少一篇 PUBLISHED 文章的 series」→
    走 `ArticleFacade.countPublishedBySeriesUuids()`，不得 `EXISTS (SELECT 1 FROM articles ...)`
  - 若該 facade 方法的輸入無上界（傳輸量與內容量成正比，而非與頁大小成正比），
    **必須在實作端經 `BatchedQuery` 切批，不得只在 JavaDoc 標註門檻**——
    JavaDoc 標註是給 reviewer 看的，切批才是給執行期用的
    （例：`countPublishedBySeriesUuids` / `filterReadableUuids`；回傳單一物件的
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
  - 這條規則存在的原因：facade 回傳的是**物件**、不是還能再加條件的 relation，
    所以跨邊界的集合述詞在舊規範下**無路可走**——這正是 `findings.md` **ARCH-13** 從 4 處
    惡化到 7 處的真因

### `*Facade` 的兩層契約

- **識別型別**：facade 方法名稱以 `Id`／`Ids` 結尾的參數、回傳 DTO 中以 `Id`／`Ids` 結尾的欄位，型別不得為 `Long`（含集合與 `Map<Long, …>`）
- **物件級讀取**（`findByUuid` / `findContentByUuid` / `existsByUuid` / `filterExistingUuids`）：**status-agnostic**，
  由 caller 自行判斷可見性；刪除連動的校正排程依賴這個性質（存在性 ≠ 可見性）
- **集合述詞方法**：可見性**直接內建**，且必須寫進方法名
  （`filterReadableUuids`、`countPublishedBySeriesUuids`），使契約在呼叫端一眼可辨
  （可見性委派 `ArticleVisibility.isReadableBy` 這份單一真相，不重寫一份 SQL 版本）
- **`Published*` 與 `Readable*` 不是同義詞**：`Published*`（`countPublishedBySeriesUuids`、
  `findPrevPublishedInSeries`）是**狀態字面量**，與是誰在問無關；`Readable*`
  （`filterReadableUuids`）是**依觀看者身分而定的可見性政策**（PUBLISHED，或作者本人，或
  ADMIN），方法簽章必須帶 viewer identity。混淆兩者是草稿外洩或內容誤藏的成因，命名前先問
  方法實際做的是哪一種，選對應的字。

### 述詞三格分類與各自的退路

跨模組查詢的正確軸不是「要不要 JOIN」，而是**這個述詞的真相由誰擁有、那份真相有幾個副本**。

| 述詞性質 | 判準 | 例 | 跨模組作法 | 效能門檻觸發時的退路 |
|---|---|---|---|---|
| reference data 投影 | schema 穩定、只讀 | `tags.name`（僅 article 模組） | 直接 JOIN | 不變 |
| 狀態字面量集合述詞 | 述詞**不含** viewer 參數 | `countPublishedBySeriesUuids` | owner facade | **可退到 owner 發布的唯讀 view ＋ JOIN**，真相仍單份 |
| viewer-dependent 政策 | 述詞**含** viewer 身分 | `filterReadableUuids` | owner facade | **不得下推**——下推即政策第二份 |

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
Q: 我要在 comment 模組取文章的作者（判斷權限）？
A: article 是業務 data → ArticleFacade.findByUuid(articleUuid) 取 ArticleData.authorUuid() ✅

Q: 我要在留言列表顯示留言者的 nickname？
A: users 是業務 data → 收集該頁 userUuid，呼叫一次 UserFacade.getDisplayInfoByUuids ✅
   （不得 LEFT JOIN users）

Q: comment 模組要更新 articles.comment_count？
A: articles 是業務 data → ArticleFacade.incrementCommentCount(articleUuid) ✅

Q: 我的新表要參照文章？
A: 存 article_uuid（不加 FK）→ 訂閱 ArticleDeletedEvent 清理 ＋ 新增 OrphanReconciliationJob 子類別 ✅
   並在跨模組 FK 守衛的「表 → 模組」對照表登記新表
```

### 守衛（規則 ↔ 哪個測試會紅）

| 規則 | 守衛（`blog-start/src/test/java/.../architecture/`） |
|---|---|
| 不得 import 他模組的內部類別 | `CrossModuleImportTest` |
| mapper／repository SQL 不得存取他模組業務表（含 `users`；`tags`／`article_tags` 僅 article 模組例外） | `CrossModuleBoundaryTest` |
| facade 不得以 Long 表達識別 | `FacadeIdentifierTypeTest` |
| 事件無 Long 欄位、必有 `eventId` | `EventContractTest` |
| 資料庫中沒有跨模組 FK；每張表都有登記 owner 模組 | `NoCrossModuleForeignKeyIT` |
| 模組 pom 不得以 compile scope 依賴其他模組 | `ModulePomDependencyTest` |
| 交易內不得直接發 MQ | `TransactionMqBoundaryTest` |

> **本節規範買到的是概念邊界與可拆分性，不是已經拆好。** 2026-10 的 UUID 重構（V25～V34）移除了
> 全部跨模組 FK、讓 Long 主鍵不再跨越模組邊界（`findings.md` **ARCH-30** 已解決）。
> 真正拆服務時剩下的代價：共用同一個資料庫交易的流程要改成事件或 saga、`tags` 的 JOIN 要改走 `TagFacade`、
> 以及上方「代價」所述的集合述詞全量載入形狀。
