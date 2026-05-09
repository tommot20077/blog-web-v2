# Comment + Like 模組設計（批 1）

- **作者**：Yuan + Claude
- **日期**：2026-05-01
- **分支**：`docs/backend-roadmap-specs`
- **批次**：後端 Roadmap 批 1（共 4 批）
- **後續**：批 2 = Bookmark + Highlight + Reading Progress

---

## 1. 範圍

### 涵蓋

- 新模組 `blog-module-comment`
  - 文章評論 CRUD（2 層巢狀 / 自動發佈 / 5 分鐘編輯窗 / 軟刪除）
  - 留言按讚（`comment_likes` 新表）
- 修改 `blog-module-article`
  - 補建 `ArticleLikeService` / `ArticleLikeController`（既有 `article_likes` 表沒對應 service）
  - `ArticleSummaryResponse` / `ArticleResponse` 新增 `liked` 欄位
  - 計數更新方法 `incrementCommentCount` / `decrementCommentCount`
- DB Migration `V13`
- TDD 測試（unit + IT）

### 不涵蓋

- 留言審核佇列 / Admin queue（決策：自動發佈）
- 信譽機制 / 檢舉系統
- 留言通知（被回覆 / 被按讚的通知）
- Email / push notification
- Rate limit / Captcha / 反垃圾
- 排程校正 job（採純反正規化計數，需要時手動 backfill）
- 攤平表 / closure table（2 層模型不需要）
- 全文搜尋留言（ES 索引）

### 邊界

- Comment 與 Like 共用一份 spec、共用一份 V13 migration
- 實作順序：Comment 先 → ArticleLike 補建 → CommentLike
- 兩者都遵循既有 `ApiResponse<T>` 信封與 `GlobalExceptionHandler` 行為
- 所有 endpoint 用 `@PreAuthorize("isAuthenticated()")` 守衛寫操作；列表公開

---

## 2. 設計決策摘要

| 決策點 | 選擇 | 理由 |
|------|------|------|
| Comment 巢狀深度 | 2 層（YouTube 式） | 個人部落格留言量低；查詢一次 join 解決；UI 易維護 |
| Comment 審核 | 自動發佈 | 留言量低，事後刪除已足夠 |
| Comment 編輯 | 5 分鐘窗（Admin 不受限）| 給打錯字容錯，避免編輯戰；Admin 編輯是維運行為 |
| Comment 刪除 | 軟刪除 + 佔位 | 保留 thread 完整性；`deleted_at` + `deleted_by_role` |
| Comment Markdown | 內聯子集（粗、斜、inline code、連結、引用 `>`）| 禁止圖片 / 標題 / 程式碼區塊；連結加 `nofollow noopener` |
| Comment 排序 | 預設新→舊；可切換舊→新；reply 永遠舊→新 | 主流社群預期；reply 按時間軸最自然 |
| Like 範圍 | 文章 + 留言獨立兩張表 | Spring Data JDBC 處理多型表彆扭；獨立表結構簡潔 |
| Like 計數 | 反正規化欄位 + service 層 SQL `+1`/`-1` | 列表查詢快；DB 原子操作避免並發問題 |
| Like 冪等 | POST 重複呼叫不報錯；DB UNIQUE 兜底 | 配合前端樂觀 UI |
| 軟刪 top-level 可否被 reply | 不可（C0106 凍結）| 內容看不到，再 reply 接不上脈絡 |
| 軟刪留言可否被按讚 | 不可（C0104）| 內容已隱藏，按讚無語意 |
| FK 策略 | 全部建 FK；`user_id` NO ACTION（user 軟刪不觸發）；其他 CASCADE | 跟既有 schema 一致；DB 兜資料完整性 |
| 模組邊界 | 中庸級別 — Reference Data 可 JOIN（users），業務 Data 必走 service（articles）| 主流 modular monolith 折衷 |
| Markdown 庫 | flexmark-java + OWASP HtmlSanitizer | flexmark 從 parser 層禁用語法；OWASP 業界標準 |

---

## 3. 資料模型

### V13 Migration

檔案：`blog-db-migration/src/main/resources/db/migration/V13__add_comment_and_comment_likes_schema.sql`

```sql
-- V13__add_comment_and_comment_likes_schema.sql

-- 1. articles：統一 like_count 型別 (BIGINT → INTEGER)
ALTER TABLE articles ALTER COLUMN like_count TYPE INTEGER;

-- 2. 清理重複索引（articles_uuid_key 已是 UNIQUE，idx_articles_uuid 多餘）
DROP INDEX IF EXISTS idx_articles_uuid;

-- 3. 改造既有 comments 表
ALTER TABLE comments DROP COLUMN status;

ALTER TABLE comments ADD COLUMN content_html    TEXT     NOT NULL DEFAULT '';
ALTER TABLE comments ADD COLUMN like_count      INTEGER  NOT NULL DEFAULT 0;
ALTER TABLE comments ADD COLUMN edited_at       TIMESTAMP NULL;
ALTER TABLE comments ADD COLUMN deleted_at      TIMESTAMP NULL;
ALTER TABLE comments ADD COLUMN deleted_by_role VARCHAR(20) NULL;
    -- 'AUTHOR' = 留言者自己刪 | 'ADMIN' = 管理員刪 | NULL = 未刪

CREATE INDEX idx_comments_article_top_level
    ON comments(article_id, created_at DESC)
    WHERE parent_id IS NULL;

CREATE INDEX idx_comments_replies
    ON comments(parent_id, created_at)
    WHERE parent_id IS NOT NULL;

CREATE INDEX idx_comments_user_created
    ON comments(user_id, created_at DESC);

-- 4. article_likes：UNIQUE 順序 → (user_id, article_id)
ALTER TABLE article_likes
    DROP CONSTRAINT article_likes_article_id_user_id_key;

ALTER TABLE article_likes
    ADD CONSTRAINT uq_article_likes_user_article UNIQUE (user_id, article_id);

-- 5. 新建 comment_likes 表
CREATE TABLE comment_likes (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL REFERENCES users(id),
    comment_id BIGINT    NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_comment_likes_user_comment UNIQUE (user_id, comment_id)
);
```

### Spring Data JDBC Entities

```java
// blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/Comment.java
@Table("comments")
public class Comment {
    @Id Long id;
    UUID uuid;                  // 必須在 save 前手動設值（既有 Bug 教訓：不可依賴 DB DEFAULT）
    Long articleId;
    Long parentId;              // null = top-level
    Long userId;
    String content;             // 原始 Markdown
    String contentHtml;         // sanitize 後的 HTML
    Integer likeCount;
    LocalDateTime editedAt;
    LocalDateTime deletedAt;
    String deletedByRole;       // AUTHOR / ADMIN / null
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}

// blog-module-comment/src/main/java/dowob/xyz/blog/module/comment/model/CommentLike.java
@Table("comment_likes")
public class CommentLike {
    @Id Long id;
    Long userId;
    Long commentId;
    LocalDateTime createdAt;
}
```

### 索引命名規範（同步寫入 `flyway-convention.md`）

```
規則
─────────────────────────────────────────────────────────
1. 全部小寫 + 底線
2. 上限 < 50 字元
3. 不在表名後重複加 _id（idx_articles_author > idx_articles_author_id）
4. 複合索引一律用「語意名」而非欄位串接

類型對照
─────────────────────────────────────────────────────────
Primary Key       → {table}_pkey                       PG 自動，沿用
Unique Constraint → uq_{table}_{purpose}               明確命名於 ADD CONSTRAINT
Foreign Key       → {table}_{col}_fkey                 PG 自動，沿用
B-tree Index      → idx_{table}_{purpose}              purpose 為語意名
Partial Index     → idx_{table}_{purpose}              purpose 包含條件語意
GIN/GiST          → gin_{table}_{col} / gist_{table}_{col}
```

---

## 4. API 端點

### Comment

| Method | Path | Auth | 描述 |
|--------|------|------|------|
| `GET` | `/api/v1/articles/{articleUuid}/comments?page=1&size=20&sort=newest` | 公開 | 列表（含軟刪除佔位）|
| `POST` | `/api/v1/articles/{articleUuid}/comments` | 登入 | 建立留言 / reply |
| `PUT` | `/api/v1/comments/{uuid}` | 登入 + 作者本人 / Admin | 編輯（5 分鐘窗對 author 生效；Admin 不受限）|
| `DELETE` | `/api/v1/comments/{uuid}` | 登入 + 作者本人 / Admin | 軟刪除 |

`sort` 列舉值：`newest`（預設）/ `oldest`

### Article Like

| Method | Path | Auth | 描述 |
|--------|------|------|------|
| `POST` | `/api/v1/articles/{uuid}/like` | 登入 | 按讚（idempotent）|
| `DELETE` | `/api/v1/articles/{uuid}/like` | 登入 | 取消讚（idempotent）|

⚠ 不開 `/like-status` 端點 —— `liked` 欄位內嵌於 `ArticleResponse` / `ArticleSummaryResponse` 中。

### Comment Like

| Method | Path | Auth | 描述 |
|--------|------|------|------|
| `POST` | `/api/v1/comments/{uuid}/like` | 登入 | 按讚（軟刪除留言阻擋）|
| `DELETE` | `/api/v1/comments/{uuid}/like` | 登入 | 取消讚 |

⚠ 不開 `/like-status` 端點 —— `liked` 欄位內嵌於 `CommentResponse` 中。

---

## 5. DTO

### Request

```java
public class CreateCommentRequest {
    @NotBlank
    @Size(max = 2000)
    private String content;

    private UUID parentUuid;   // optional; null = top-level
}

public class EditCommentRequest {
    @NotBlank
    @Size(max = 2000)
    private String content;
}
```

### Response

```java
public class CommentResponse {
    private UUID uuid;
    private UUID parentUuid;          // null = top-level
    private String content;           // raw markdown（提供前端編輯用）
    private String contentHtml;       // sanitized HTML
    private AuthorSummary author;     // null when deleted=true（隱私保護）
    private Integer likeCount;
    private Boolean liked;            // 當前使用者是否已按讚（未登入則 false）
    private LocalDateTime createdAt;
    private LocalDateTime editedAt;   // null = 未編輯
    private Boolean deleted;          // true = 軟刪除佔位
    private String deletedByRole;     // 'AUTHOR' | 'ADMIN' | null
    private List<CommentResponse> replies;
        // 僅 top-level 帶 replies；reply 自身的 replies 永遠空陣列（2 層限制）
}

public class AuthorSummary {
    private UUID uuid;
    private String nickname;
    private String avatarUrl;
}

public class ArticleCommentListResponse {
    private PageResult<CommentResponse> topLevels;
    private Integer totalCommentCount;  // 全部 thread 留言數（含 reply、含軟刪除佔位）
}
```

### 軟刪除留言的 response 規則

```
deleted = true 時:
  content       = ""
  contentHtml   = ""
  author        = null     ← 隱私
  likeCount     保留        ← 仍顯示
  liked         = false    ← 不允許再按讚
  replies       保留        ← 結構不破壞
  其他元資料    保留        ← createdAt 等
```

### Article 模組 DTO 變更

`ArticleSummaryResponse` 與 `ArticleResponse` 新增欄位：

```java
private Boolean liked;  // 當前使用者是否已按讚；未登入永遠 false
```

列表查詢時 service 用 `IN` 一次撈所有 `article_likes WHERE user_id = current AND article_id IN (...)`，避免 N+1。

---

## 6. 錯誤碼

新增 `CommentErrorCode`（沿用既有 `IErrorCode` 模式）：

| Code | Message | 觸發 |
|------|---------|-----|
| `C0101` | 留言不存在 | UUID 找不到 |
| `C0102` | 不可超過 2 層巢狀 | reply 的 reply |
| `C0103` | 編輯時限已過 | 5 分鐘窗外（非 Admin）|
| `C0104` | 留言已刪除，無法操作 | 對軟刪留言編輯 / 按讚 |
| `C0105` | 父留言不屬於此文章 | 跨文章 reply |
| `C0106` | 父留言已凍結 | reply 到軟刪除 top-level |

`AccessDeniedException` 由 `GlobalExceptionHandler` 統一處理回 `403` ApiResponse（既有行為）。

---

## 7. 模組邊界與整合

### 模組劃分

```
新增：blog-module-comment
  ├─ controller/  CommentController, CommentLikeController
  ├─ service/     CommentService, CommentLikeService, CommentMarkdownRenderer
  ├─ repository/  CommentRepository, CommentLikeRepository
  ├─ model/       Comment, CommentLike
  ├─ dto/         CreateCommentRequest, EditCommentRequest, CommentResponse, AuthorSummary
  └─ exception/   CommentErrorCode

修改：blog-module-article
  ├─ ArticleService           新增 incrementCommentCount / decrementCommentCount /
  │                           incrementLikeCount / decrementLikeCount
  ├─ ArticleLikeService       新增（既有表沒對應 service）
  ├─ ArticleLikeController    新增
  ├─ ArticleSummaryResponse / ArticleResponse 加 `liked` 欄位
  └─ ArticleRepository / ArticleLikeRepository batch is-liked 查詢
```

### 模組邊界規則（中庸級別）

寫入 `ai-docs/architecture.md`：

| 表性質 | 範例 | 跨模組訪問 |
|-------|------|----------|
| **Reference Data**（穩定、全系統共用） | `users`、`tags` | ✅ 可 SQL JOIN（讀）|
| **業務 Data**（owner module 控制變更）| `articles`、`comments` | ❌ 必走 owner module 的 service |

具體應用：
- `CommentRepository` 列表 SQL 可 JOIN `users` 取 `nickname` / `avatar_url`（一次 query 完成）
- `CommentService` 不直接 `UPDATE articles SET comment_count + 1`，必走 `ArticleService.incrementCommentCount()`
- 留言要文章 status / 權限資訊 → 走 `ArticleService` 而非 JOIN articles

### 計數更新（並發安全）

```java
// ArticleService 新增方法
@Modifying
@Query("UPDATE articles SET comment_count = comment_count + 1 WHERE id = :id")
void incrementCommentCount(@Param("id") Long id);

@Modifying
@Query("UPDATE articles SET comment_count = comment_count - 1 WHERE id = :id AND comment_count > 0")
void decrementCommentCount(@Param("id") Long id);

@Modifying
@Query("UPDATE articles SET like_count = like_count + 1 WHERE id = :id")
void incrementLikeCount(@Param("id") Long id);

@Modifying
@Query("UPDATE articles SET like_count = like_count - 1 WHERE id = :id AND like_count > 0")
void decrementLikeCount(@Param("id") Long id);
```

```java
// CommentService.createComment（範例）
@Transactional
public CommentResponse createComment(Long articleId, Long userId, CreateCommentRequest req) {
    // 1. 驗證父留言（2 層、跨文章、凍結檢查）
    Long parentId = resolveParent(articleId, req.getParentUuid());

    // 2. Render markdown → HTML
    String contentHtml = markdownRenderer.render(req.getContent());

    // 3. 建立 Comment
    Comment c = new Comment();
    c.setUuid(UUID.randomUUID());
    c.setArticleId(articleId);
    c.setParentId(parentId);
    c.setUserId(userId);
    c.setContent(req.getContent());
    c.setContentHtml(contentHtml);
    c.setLikeCount(0);
    Comment saved = commentRepository.save(c);

    // 4. 跨模組更新計數（透過 service）
    articleService.incrementCommentCount(articleId);

    return mapper.toResponse(saved, /* author from current user */);
}
```

### Markdown 渲染與 Sanitize

```java
@Service
public class CommentMarkdownRenderer {
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final PolicyFactory sanitizer;

    public CommentMarkdownRenderer() {
        // flexmark：禁用 image / heading / fenced code block / table / list extension
        // 只保留 inline emphasis、inline code、link、blockquote
        MutableDataSet options = new MutableDataSet();
        // ... 只啟用 inline emphasis、code、link、blockquote
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();

        // OWASP：白名單 element + 強制注入 rel="nofollow noopener" + target="_blank"
        this.sanitizer = new HtmlPolicyBuilder()
            .allowElements("p", "br", "strong", "em", "code", "blockquote", "a")
            .allowUrlProtocols("http", "https")
            .allowAttributes("href").onElements("a")
            .requireRelNofollowOnLinks()
            .toFactory();
    }

    public String render(String markdown) {
        Node doc = parser.parse(markdown);
        String html = renderer.render(doc);
        return sanitizer.sanitize(html);
    }
}
```

依賴新增（`blog-module-comment/pom.xml`）：

```xml
<dependency>
    <groupId>com.vladsch.flexmark</groupId>
    <artifactId>flexmark</artifactId>
</dependency>
<dependency>
    <groupId>com.googlecode.owasp-java-html-sanitizer</groupId>
    <artifactId>owasp-java-html-sanitizer</artifactId>
</dependency>
```

### 權限與 Auth（沿用既有 RBAC）

| 操作 | Auth 規則 | RBAC Permission |
|------|---------|-----------------|
| 列表留言 | 公開 | — |
| 建留言 | `@PreAuthorize("isAuthenticated()")` | `COMMENT_WRITE`（USER 預設有）|
| 編輯自己留言 | service 檢查 `userId == comment.userId` | `COMMENT_WRITE` |
| 刪自己留言 | service 檢查 `userId == comment.userId` | `COMMENT_DELETE`（USER 預設有）|
| 刪他人留言 | RBAC 檢查 `hasRole('ADMIN')` | `COMMENT_DELETE` + ADMIN |
| 編輯他人留言（Admin）| RBAC 檢查 `hasRole('ADMIN')` | ADMIN |
| 文章按讚 / 留言按讚 | `@PreAuthorize("isAuthenticated()")` | 不需特殊 permission |

---

## 8. 軟刪除細節

### 列表查詢策略（service 層）

```
1. 撈所有 top-level（含軟刪除，因為要顯示佔位）
   SELECT c.*, u.nickname, u.avatar_url
   FROM comments c LEFT JOIN users u ON c.user_id = u.id
   WHERE c.article_id = ? AND c.parent_id IS NULL
   ORDER BY c.created_at DESC LIMIT ? OFFSET ?

2. 一次撈所有 replies（用 IN 條件）
   SELECT c.*, u.nickname, u.avatar_url
   FROM comments c LEFT JOIN users u ON c.user_id = u.id
   WHERE c.parent_id IN (?, ?, ...)
   ORDER BY c.parent_id, c.created_at ASC

3. 過濾「軟刪除且沒 child」的 reply（leaf reply 軟刪 → 隱藏）
   實務上：reply 沒有 child（2 層）→ 軟刪 reply 一律隱藏

4. service 層轉 DTO：
   - 軟刪除 row 的 content/contentHtml 替換成 ""
   - author 設為 null
   - 保留 likeCount / createdAt / deletedByRole

5. 一次 batch 查 article_likes / comment_likes 撈當前使用者 liked 狀態
```

### 軟刪除 top-level 的 reply 限制

```
建立 reply 時 service 檢查：
  if (parent.deletedAt != null) {
    throw new BusinessException(C0106, "父留言已凍結");
  }
```

---

## 9. TDD 測試案例

### CommentServiceTest（unit）

| # | 測試名 | 對應決策 |
|---|------|--------|
| **建立 — 基本** |||
| 1 | `createComment_topLevel_savesWithUuidAndDefaults` | Schema |
| 2 | `createComment_renderInlineMarkdown_storesContentHtml` | Markdown 子集 |
| 3 | `createComment_sanitizesXss` | OWASP sanitizer |
| **建立 — 巢狀規則** |||
| 4 | `createComment_replyToTopLevel_succeeds` | 2 層 |
| 5 | `createComment_replyToReply_throwsBusinessException` | 2 層（C0102）|
| 6 | `createComment_parentNotInSameArticle_throwsBusinessException` | C0105 |
| 7 | `createComment_parentSoftDeleted_throwsBusinessException` | C0106（凍結）|
| **建立 — 計數** |||
| 8 | `createComment_incrementsArticleCommentCount` | 反正規化 |
| 9 | `createComment_replyAlsoIncrementsArticleCount` | 反正規化 |
| **編輯** |||
| 10 | `editComment_within5Min_updatesContentAndSetsEditedAt` | 5 分鐘窗 |
| 11 | `editComment_after5Min_throwsBusinessException` | C0103 |
| 12 | `editComment_byOtherUser_throwsAccessDenied` | 權限 |
| 13 | `editComment_byAdminAfter5Min_succeeds` | Admin 不受限 |
| 14 | `editComment_renderHtml_updatesContentHtml` | Markdown |
| **刪除** |||
| 15 | `deleteComment_byOwner_softDeletesAndMarksAuthor` | 軟刪除 |
| 16 | `deleteComment_byAdmin_softDeletesAndMarksAdmin` | 軟刪除 |
| 17 | `deleteComment_byOther_throwsAccessDenied` | 權限 |
| 18 | `deleteComment_decrementsArticleCommentCount` | 反正規化 |
| 19 | `deleteComment_topLevelWithReplies_keepsRowAsTombstone` | 軟刪除 + 佔位 |
| 20 | `deleteComment_leafReply_softDeletes` | 一致語意 |
| **查詢** |||
| 21 | `listComments_topLevelNewestFirstByDefault` | 預設排序 |
| 22 | `listComments_canToggleToOldestFirst` | 排序切換 |
| 23 | `listComments_includesReplies_orderedAscWithinThread` | 對話脈絡 |
| 24 | `listComments_softDeletedTopLevelShownAsTombstone` | 佔位 |
| 25 | `listComments_softDeletedLeafReplyHidden` | 隱藏規則 |
| 26 | `listComments_paginated` | 分頁 |
| 27 | `listComments_includesAuthorInfoFromJoin` | Reference data JOIN |
| 28 | `listComments_includesLikedFlagForCurrentUser` | batch is-liked |

### CommentControllerIT

| # | 測試名 |
|---|------|
| 1 | `POST /articles/{uuid}/comments — 401 when unauthenticated` |
| 2 | `POST — 200 with valid markdown` |
| 3 | `POST — reply to reply returns C0102` |
| 4 | `PUT /comments/{uuid} — 5min window enforced` |
| 5 | `DELETE /comments/{uuid} — owner soft deletes` |
| 6 | `DELETE /comments/{uuid} — admin can delete any` |
| 7 | `DELETE /comments/{uuid} — non-owner non-admin gets 403` |
| 8 | `GET /articles/{uuid}/comments — paginated, default newest first` |
| 9 | `GET /articles/{uuid}/comments?sort=oldest` |
| 10 | `GET — soft-deleted top-level shown as tombstone with replies still visible` |
| 11 | `comment_count on Article reflects actual count after create + delete` |

### ArticleLikeServiceTest

| # | 測試名 |
|---|------|
| 1 | `likeArticle_firstTime_createsRowAndIncrementsCount` |
| 2 | `likeArticle_alreadyLiked_isIdempotentNoChange` |
| 3 | `likeArticle_concurrentSameUser_uniqueConstraintProtects` |
| 4 | `unlikeArticle_existing_deletesRowAndDecrementsCount` |
| 5 | `unlikeArticle_notLiked_isIdempotentNoChange` |
| 6 | `getMyLikedArticles_returnsUserLikesNewestFirst` |
| 7 | `batchIsLikedByCurrentUser_returnsCorrectFlags` |
| 8 | `likeArticle_unauthenticated_throwsBadCredentials` |

### CommentLikeServiceTest

跟 ArticleLikeService 結構一致，加上：
- `likeComment_softDeleted_throwsBusinessException` (C0104)

### Controller IT

| 路徑 | 行為 |
|------|------|
| `POST /articles/{uuid}/like` | 200 OK；冪等 |
| `DELETE /articles/{uuid}/like` | 200 OK；冪等 |
| `POST /comments/{uuid}/like` | 同上 |
| `DELETE /comments/{uuid}/like` | 同上 |
| `POST /comments/{uuid}/like — soft-deleted comment 400` | C0104 |

### 跨模組整合測試

| # | 測試名 |
|---|------|
| 1 | `articleSummary_includesCommentCountAndLikeCount` |
| 2 | `articleSummary_includesLikedFlagForCurrentUser` |
| 3 | `deleteArticle_cascadeDeletesComments` |
| 4 | `deleteArticle_cascadeDeletesArticleLikes` |
| 5 | `deleteComment_cascadeDeletesCommentLikes` |

---

## 10. 實作順序建議

1. V13 migration 寫好 + 在本地 dev DB 跑通
2. blog-module-comment 骨架（pom.xml、目錄結構、TestApplication）
3. Comment Entity + Repository + 對應 unit/IT 紅燈
4. CommentService（先 createComment + 2 層驗證）→ 綠燈
5. CommentMarkdownRenderer（flexmark + OWASP）→ 綠燈
6. CommentService 補齊 edit / delete / list
7. CommentController + IT
8. ArticleLikeService 補建（既有 article_likes 表）
9. ArticleLikeController + IT
10. CommentLikeService + Controller + IT
11. ArticleSummaryResponse / ArticleResponse 加 `liked` + batch is-liked 查詢
12. 跨模組整合測試

每步嚴格 TDD：紅 → 綠 → Refactor。測試輸出存 `./logs/`。
不重跑全套測試，只跑當前模組 / 當前測試類別。

---

## 11. 後續批次（提醒）

完成本批後，下一批：

**批 2：Bookmark + Highlight & Note + Reading Progress**
- 共用「user × article 私人狀態」資料模型族群
- 預期可重用本批的 Reference Data JOIN / 計數模式 / FK 策略

**批 3、批 4** 暫時擱置（Article Series、Draft History）。

---

## 附錄：相關文件更新清單

實作完成時須同步更新：

- [ ] `ai-docs/schema.md`（task #7：建立並寫入 V13 後狀態）
- [ ] `ai-docs/flyway-convention.md`（task #7：加入索引命名規範段落）
- [ ] `ai-docs/architecture.md`（task #7：加入「中庸級別模組邊界」規則）
- [ ] `CLAUDE.md`（task #7：規定每次 migration 後同步更新 schema.md）
- [ ] auto memory（task #7：寫入 schema.md 維護流程）
