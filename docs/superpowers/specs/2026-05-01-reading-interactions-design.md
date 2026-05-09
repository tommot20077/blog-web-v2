# Reading Interactions 模組設計（批 2）

- **作者**：Yuan + Claude
- **日期**：2026-05-01
- **分支**：`feature/reading-interactions`（worktree `.worktrees/feature-reading-interactions`）
- **批次**：後端 Roadmap 批 2（共 4 批）
- **依賴**：批 1 已完成（Comment + Like + ArticleQueryService CQRS Read 層）
- **前置 PR**：#27（批 1）必須先 merge 才能 base 到 develop

---

## 1. 範圍

### 涵蓋

- 新模組 `blog-module-reading`（**3 個功能合一個模組**：Bookmark / Highlight / Reading Progress）
  - **Bookmark** — 純 flag 收藏（user × article）
  - **Highlight & Note** — 文字定位用 snippet + anchor 策略；hex 色號；純文字 note
  - **Reading Progress** — Redis 主、DB 備份；3 天 TTL；progress >= 0.95 視為完成
- DB Migration `V14`：3 張表（user_bookmarks / user_highlights / user_reading_progress）
- 修改 `blog-module-article`：
  - `ArticleQueryService` 注入兩個新 service（BookmarkService + ReadingProgressService）
  - `ArticleSummaryResponse` / `ArticleResponse` 加 `bookmarked` + `lastReadProgress`
- `RedisKeyConstant` 新增 reading progress keys
- `ReadingProgressFlushJob`（@Scheduled 5 分鐘）
- TDD 測試（49 tests：28 unit + 21 IT）

### 不涵蓋

- Bookmark 分類 / 資料夾（推遲到批 5+）
- Highlight 顏色 enum（使用者 settings 自選 hex）
- Highlight `note` 的 Markdown render（純文字儲存 + 前端 textContent）
- Highlight 在 `ArticleResponse` 上露面（content-layer，前端從獨立端點 inject）
- `GET /me/highlights`（個人後台 highlight 列表，推遲）
- Reading Progress 完整閱讀歷史 / 已讀清單
- Reading Progress 多裝置同步
- Reading Progress 手動清除端點（`DELETE /progress`）— 非必要
- Sharing / Public highlights / Annotations API
- Admin 介入 highlight / bookmark / progress（私人資料無此需求）
- 軟刪除（本批全硬刪除）

### 邊界

- 所有端點 `@PreAuthorize("isAuthenticated()")` — 私人資料
- 共用同一個新模組 `blog-module-reading`
- 跨模組透過 `ArticleService.findIdByUuid` 取 article PK
- Redis key 用 `userId:articleUuid` 格式（避免 DB id 依賴）
- 完成判定 `progress >= 0.95` 即視為已讀完
- 文章硬刪除時：DB 走 FK CASCADE 清；Redis ghost 進度自然 TTL 過期

---

## 2. 設計決策摘要

| 決策點 | 選擇 | 理由 |
|------|------|------|
| Highlight 文字定位 | D — snippet + prefix/suffix anchor | 個人部落格規模；snippet self-healing 比精確 offset 重要 |
| Reading Progress 紀錄內容 | D — progress + last_heading_anchor | heading-aware 還原 UX 最好 |
| Reading Progress 更新策略 | 3 — 滾動 throttle + unload beacon | 確保關 tab 不丟資料 |
| Reading Progress 儲存 | Y — Redis 3 天 TTL + 5 分鐘 flush 到 DB | 中斷重續使用窗口短；保留 DB 為將來閱讀歷史 |
| Reading Progress 完成行為 | progress >= 0.95 → DEL Redis + UPSERT DB（保留 row） | 釋放 Redis；保留紀錄供將來「我讀完幾篇」UX |
| Bookmark 範圍 | A — 純 flag | 跟 article_likes 對稱；不 over-design |
| Highlight 顏色 | hex 色號（user setting 帶入）| 彈性；不綁固定 enum |
| Highlight note | 純文字（無 Markdown）| 個人記錄夠用；無需 sanitize |
| Highlight 編輯時間限制 | 無 | 私人資料無編輯戰問題 |
| Highlight 刪除 | 硬刪除 | 私人資料無保留結構需求 |
| ArticleResponse 擴充 | bookmarked + lastReadProgress（不含 highlight）| metadata vs content layer 區分 |
| 模組邊界 | 沿用批 1 中庸級別 | Reference Data JOIN OK；業務 Data 走 service |
| 循環依賴 | 沿用 ArticleQueryService Read 層 | 新增依賴只進 QueryService，無逆向 |

---

## 3. 資料模型

### V14 Migration

檔案：`blog-db-migration/src/main/resources/db/migration/V14__add_reading_interactions.sql`

```sql
-- V14__add_reading_interactions.sql

-- ─────────────────────────────────────────────
-- 1. user_bookmarks（純 flag）
-- ─────────────────────────────────────────────
CREATE TABLE user_bookmarks (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL REFERENCES users(id),
    article_id BIGINT    NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_bookmarks_user_article UNIQUE (user_id, article_id)
);

-- UNIQUE (user_id, article_id) 自動建 index，覆蓋 is-bookmarked + my-bookmarks 列表

-- ─────────────────────────────────────────────
-- 2. user_highlights（snippet + anchor + 私人 note）
-- ─────────────────────────────────────────────
CREATE TABLE user_highlights (
    id         BIGSERIAL    PRIMARY KEY,
    uuid       UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    article_id BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    snippet    TEXT         NOT NULL,                    -- max 500 chars (app-level)
    prefix     VARCHAR(64)  NOT NULL DEFAULT '',         -- 32 chars + buffer
    suffix     VARCHAR(64)  NOT NULL DEFAULT '',
    color      VARCHAR(7)   NOT NULL DEFAULT '#FFEB3B',  -- hex 色號
    note       TEXT         NULL,                        -- max 2000 chars (app-level)，純文字
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_highlights_color CHECK (color ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE INDEX idx_user_highlights_article_for_user
    ON user_highlights(user_id, article_id);

CREATE INDEX idx_user_highlights_user_recent
    ON user_highlights(user_id, created_at DESC);

-- ─────────────────────────────────────────────
-- 3. user_reading_progress（Redis 主，DB 備份）
-- ─────────────────────────────────────────────
CREATE TABLE user_reading_progress (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id),
    article_id          BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    progress            NUMERIC(4,3) NOT NULL,                       -- 0.000 - 1.000
    last_heading_anchor VARCHAR(255) NULL,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_reading_progress_user_article UNIQUE (user_id, article_id),
    CONSTRAINT chk_user_reading_progress_range CHECK (progress >= 0 AND progress <= 1)
);

CREATE INDEX idx_user_reading_progress_user_recent
    ON user_reading_progress(user_id, updated_at DESC);
```

### Spring Data JDBC Entities

```java
@Table("user_bookmarks")
public class UserBookmark {
    @Id Long id;
    @Column("user_id") Long userId;
    @Column("article_id") Long articleId;
    @CreatedDate @Column("created_at") LocalDateTime createdAt;
}

@Table("user_highlights")
public class UserHighlight {
    @Id Long id;
    UUID uuid;                          // setUuid(UUID.randomUUID()) before save
    @Column("user_id") Long userId;
    @Column("article_id") Long articleId;
    String snippet;
    String prefix;
    String suffix;
    String color;                       // hex
    String note;                        // nullable
    @CreatedDate @Column("created_at") LocalDateTime createdAt;
    @LastModifiedDate @Column("updated_at") LocalDateTime updatedAt;
}

@Table("user_reading_progress")
public class UserReadingProgress {
    @Id Long id;
    @Column("user_id") Long userId;
    @Column("article_id") Long articleId;
    BigDecimal progress;                // NUMERIC(4,3)
    @Column("last_heading_anchor") String lastHeadingAnchor;
    @LastModifiedDate @Column("updated_at") LocalDateTime updatedAt;
}
```

### Redis 結構（Reading Progress）

```
Key:        reading:progress:{userId}:{articleUuid}
Type:       Hash
Fields:
  progress      "0.612"            (string；HSET 用)
  lastHeading   "intro" or null
  updatedAt     "1730438400000"    (epoch millis)
TTL:        3 天（每次 PUT 重置）
完成判斷:   service 層 progress >= 0.95 時 DEL key（節省記憶體）

Dirty 追蹤:  reading:dirty (Set)
Members 形式: "{userId}:{articleUuid}"
HSET 時 SADD 加入；flush 後 SREM
```

`RedisKeyConstant` 新增（blog-common）：

```java
public static final String READING_PROGRESS_PREFIX = "reading:progress:";
public static final String READING_DIRTY_KEY = "reading:dirty";
public static final long READING_PROGRESS_TTL_DAYS = 3L;
public static final BigDecimal READING_PROGRESS_COMPLETED_THRESHOLD = new BigDecimal("0.95");
```

---

## 4. API 端點

### Bookmark

| Method | Path | Auth | 描述 |
|--------|------|------|------|
| `POST` | `/api/v1/articles/{articleUuid}/bookmark` | 登入 | 收藏（idempotent）|
| `DELETE` | `/api/v1/articles/{articleUuid}/bookmark` | 登入 | 取消收藏（idempotent）|
| `GET` | `/api/v1/users/me/bookmarks?page=&size=` | 登入 | 我的收藏列表（回 PageResult<ArticleSummaryResponse>，bookmarked = true 已填）|

⚠ 不開 `/bookmark-status` 端點 — `bookmarked` 欄位內嵌 ArticleResponse。

### Highlight

| Method | Path | Auth | 描述 |
|--------|------|------|------|
| `POST` | `/api/v1/articles/{articleUuid}/highlights` | 登入 | 建立 highlight + 可選 note |
| `GET` | `/api/v1/articles/{articleUuid}/highlights` | 登入 | 撈我在這篇文章的所有 highlight |
| `PUT` | `/api/v1/highlights/{uuid}` | 登入 + 擁有者 | 更新（color / note）|
| `DELETE` | `/api/v1/highlights/{uuid}` | 登入 + 擁有者 | 硬刪除 |

⚠ 無 admin 端點。`GET /me/highlights` 推遲到批 5。

### Reading Progress

| Method | Path | Auth | 描述 |
|--------|------|------|------|
| `PUT` | `/api/v1/articles/{articleUuid}/progress` | 登入 | 更新進度（HSET Redis + SADD dirty）|
| `GET` | `/api/v1/articles/{articleUuid}/progress` | 登入 | 查我的進度（Redis 優先）|

⚠ 不開 `DELETE /progress` 端點（罕用，等真有需求再加）。
⚠ `lastReadProgress` 欄位內嵌 ArticleResponse；獨立 GET 給前端細粒度拉取（如進入文章時只查單一進度）。

---

## 5. DTO

### Bookmark

無專屬 DTO。我的收藏列表回 `PageResult<ArticleSummaryResponse>`。

### Highlight

```java
public class CreateHighlightRequest {
    @NotBlank @Size(max = 500)
    private String snippet;

    @Size(max = 64)
    private String prefix = "";

    @Size(max = 64)
    private String suffix = "";

    @NotBlank
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private String color;

    @Size(max = 2000)
    private String note;
}

public class UpdateHighlightRequest {
    @Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    private String color;            // optional, only update if non-null

    @Size(max = 2000)
    private String note;             // optional
}

public class HighlightResponse {
    private UUID uuid;
    private String snippet;
    private String prefix;
    private String suffix;
    private String color;
    private String note;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### Reading Progress

```java
public class UpdateProgressRequest {
    @NotNull
    @DecimalMin("0.000") @DecimalMax("1.000")
    private BigDecimal progress;

    @Size(max = 255)
    private String lastHeading;
}

public class ProgressResponse {
    private BigDecimal progress;
    private String lastHeading;
    private LocalDateTime updatedAt;
}
```

### ArticleSummaryResponse / ArticleResponse 新增欄位

```java
private Boolean bookmarked;             // null = 未登入；false / true = 已知狀態
private BigDecimal lastReadProgress;    // null = 未讀過 / 已讀完 / 未登入
```

---

## 6. 錯誤碼（新增 `ReadingErrorCode`）

| Code | Message | 觸發 |
|------|---------|-----|
| `R0201` | Highlight 不存在 | UUID 找不到 |
| `R0202` | 不可變更他人的 Highlight | 編輯 / 刪除非自己的 |
| `R0301` | 進度值超出範圍 | progress > 1 or < 0（雖然 Bean Validation 會擋，留作守備）|

---

## 7. 模組邊界與整合

### 模組劃分

```
新增：blog-module-reading
  ├─ controller/
  │   ├─ BookmarkController
  │   ├─ HighlightController
  │   └─ ReadingProgressController
  ├─ service/
  │   ├─ BookmarkService
  │   ├─ HighlightService
  │   └─ ReadingProgressService (Redis 主 + DB 備份)
  ├─ repository/
  │   ├─ UserBookmarkRepository
  │   ├─ UserHighlightRepository
  │   └─ UserReadingProgressRepository
  ├─ mapper/                  (MyBatis：batch 查詢、UPSERT、JOIN)
  │   ├─ BookmarkMapper        (findBookmarkedArticleIdsByUser, paginated my-bookmarks)
  │   ├─ HighlightMapper       (僅作為備用，主要由 repo 處理)
  │   └─ ReadingProgressMapper (upsertOnConflict, batchFindByUserAndArticleIds)
  ├─ model/                   (3 entity + DTO + ReadingErrorCode)
  ├─ job/
  │   └─ ReadingProgressFlushJob  (@Scheduled 5min)
  └─ config/                  (CommonModule TestApp etc.)

修改：blog-module-article
  ├─ ArticleQueryService 注入 BookmarkService + ReadingProgressService
  └─ ArticleSummaryResponse / ArticleResponse 加 bookmarked + lastReadProgress

修改：blog-common
  └─ RedisKeyConstant 加 READING_PROGRESS_*

修改：blog-start
  └─ pom.xml 加 blog-module-reading dependency

修改：root pom.xml
  ├─ <modules> 加 blog-module-reading
  └─ <dependencyManagement> 加 blog-module-reading internal entry
```

### 跨模組依賴方向

```
blog-module-reading ─── inject ───→ ArticleService.findIdByUuid (read-only)
blog-module-article  
  └─ ArticleQueryService ─── inject ──→ BookmarkService / ReadingProgressService (read-only)

→ 無循環，QueryService 永遠 read-only 不被反向呼叫
```

### 模組邊界規則（沿用批 1）

- Reference Data（users）可 SQL JOIN — 本批沒這需求（Bookmark/Highlight/Progress 不需要 author 資訊）
- 業務 Data（articles）走 ArticleService — 取 article PK by UUID
- 計數 / 反正規化 — 本批不需要（Bookmark/Highlight/Progress 都沒對 articles 表的反正規化）

### Highlight `note` 安全性

- 儲存：DB 純文字 TEXT（不做 sanitize）
- 顯示：前端用 `textContent` 而非 `innerHTML` → 自動 escape，防 self-XSS
- 後端不需要 markdown render 或 sanitize
- 跟 user.bio 等私人欄位處理方式一致

---

## 8. Reading Progress Redis Pattern 細節

### 寫入流程

```java
@Transactional   // 用於 DB UPSERT；Redis 操作不在 tx
public void update(Long userId, UUID articleUuid, BigDecimal progress, String lastHeading) {
    Long articleId = articleService.findIdByUuid(articleUuid);
    String key = READING_PROGRESS_PREFIX + userId + ":" + articleUuid;

    if (progress.compareTo(READING_PROGRESS_COMPLETED_THRESHOLD) >= 0) {
        // 已讀完
        redisTemplate.delete(key);
        progressMapper.upsert(userId, articleId, progress, lastHeading);
    } else {
        // 進行中
        Map<String, String> hash = Map.of(
            "progress", progress.toPlainString(),
            "lastHeading", lastHeading != null ? lastHeading : "",
            "updatedAt", String.valueOf(System.currentTimeMillis())
        );
        redisTemplate.opsForHash().putAll(key, hash);
        redisTemplate.expire(key, READING_PROGRESS_TTL_DAYS, TimeUnit.DAYS);
        redisTemplate.opsForSet().add(READING_DIRTY_KEY, userId + ":" + articleUuid);
    }
}
```

### 讀取流程

```java
public Optional<ProgressResponse> get(Long userId, UUID articleUuid) {
    String key = READING_PROGRESS_PREFIX + userId + ":" + articleUuid;
    Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
    if (!hash.isEmpty()) {
        return Optional.of(fromHash(hash));
    }
    // miss → DB
    Long articleId = articleService.findIdByUuid(articleUuid);
    Optional<UserReadingProgress> dbVal = repo.findByUserIdAndArticleId(userId, articleId);
    dbVal.ifPresent(p -> cacheToRedis(key, p));    // 回填 Redis 帶 TTL
    return dbVal.map(this::toResponse);
}
```

### 批次讀取（給 ArticleQueryService 用）

```java
public Map<Long, BigDecimal> batchGetProgress(Long userId, List<Long> articleIds) {
    if (userId == null || articleIds.isEmpty()) return Collections.emptyMap();

    // 1. 從 article ids 取 uuids（透過 article 模組的 mapper）
    Map<Long, UUID> idToUuid = articleMapper.findUuidsByIds(articleIds);    // 新需要的方法

    // 2. 用 Redis pipeline 一次 HGET 多 keys
    List<Object> redisResults = redisTemplate.executePipelined((RedisCallback<?>) connection -> {
        for (UUID uuid : idToUuid.values()) {
            connection.hashCommands().hGetAll(("reading:progress:" + userId + ":" + uuid).getBytes());
        }
        return null;
    });

    // 3. 整合 — Redis 命中的直接用；miss 的查 DB 一次性補完
    Map<Long, BigDecimal> result = new HashMap<>();
    List<Long> missingIds = new ArrayList<>();
    int i = 0;
    for (Map.Entry<Long, UUID> entry : idToUuid.entrySet()) {
        Map<?, ?> hash = (Map<?, ?>) redisResults.get(i++);
        if (hash != null && !hash.isEmpty()) {
            result.put(entry.getKey(), parseProgress(hash));
        } else {
            missingIds.add(entry.getKey());
        }
    }
    if (!missingIds.isEmpty()) {
        // DB batch fetch
        List<UserReadingProgress> dbRows = progressMapper.findByUserAndArticleIds(userId, missingIds);
        dbRows.forEach(p -> result.put(p.getArticleId(), p.getProgress()));
    }
    return result;
}
```

⚠ 需要在 ArticleMapper 加新方法 `findUuidsByIds(List<Long>)`，提供 id → uuid 反向映射。

### Flush Job

```java
@Component
@RequiredArgsConstructor
public class ReadingProgressFlushJob {

    private final StringRedisTemplate redisTemplate;
    private final UserReadingProgressMapper progressMapper;
    private final ArticleService articleService;

    @Scheduled(fixedDelayString = "${reading.progress.flush-interval-ms:300000}")  // 5 min
    public void flush() {
        Set<String> dirtyEntries = redisTemplate.opsForSet().members(READING_DIRTY_KEY);
        if (dirtyEntries == null || dirtyEntries.isEmpty()) return;

        for (String entry : dirtyEntries) {
            try {
                String[] parts = entry.split(":");
                Long userId = Long.parseLong(parts[0]);
                UUID articleUuid = UUID.fromString(parts[1]);
                String key = READING_PROGRESS_PREFIX + entry;
                Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);

                if (hash.isEmpty()) {
                    // key expired before flush — 從 dirty 移除（資料已喪失）
                    redisTemplate.opsForSet().remove(READING_DIRTY_KEY, entry);
                    continue;
                }

                Long articleId = articleService.findIdByUuid(articleUuid);
                if (articleId == null) {
                    // article 已被硬刪除 — 移除 dirty + Redis key
                    redisTemplate.delete(key);
                    redisTemplate.opsForSet().remove(READING_DIRTY_KEY, entry);
                    continue;
                }

                progressMapper.upsert(userId, articleId, parseProgress(hash), parseHeading(hash));
                redisTemplate.opsForSet().remove(READING_DIRTY_KEY, entry);
            } catch (Exception e) {
                log.warn("flush 跳過無效 entry，下次重試 - {}", entry, e);
                // 不從 dirty 移除，下次重試
            }
        }
    }
}
```

### DB UPSERT（PostgreSQL）

```java
// UserReadingProgressMapper (MyBatis)
@Update("""
    INSERT INTO user_reading_progress (user_id, article_id, progress, last_heading_anchor, updated_at)
    VALUES (#{userId}, #{articleId}, #{progress}, #{lastHeading}, CURRENT_TIMESTAMP)
    ON CONFLICT (user_id, article_id) DO UPDATE SET
      progress = EXCLUDED.progress,
      last_heading_anchor = EXCLUDED.last_heading_anchor,
      updated_at = CURRENT_TIMESTAMP
    """)
int upsert(@Param("userId") Long userId,
           @Param("articleId") Long articleId,
           @Param("progress") BigDecimal progress,
           @Param("lastHeading") String lastHeading);
```

---

## 9. TDD 測試案例

### A. BookmarkServiceTest（unit）

| # | 測試 |
|---|------|
| 1 | `bookmark_firstTime_createsRow` |
| 2 | `bookmark_alreadyBookmarked_isIdempotent` |
| 3 | `unbookmark_existing_deletesRow` |
| 4 | `unbookmark_notBookmarked_isIdempotent` |
| 5 | `isBookmarked_returnsCorrectFlag` |
| 6 | `batchIsBookmarked_returnsCorrectIdSet` |

### B. BookmarkControllerIT

```
- POST /bookmark - 200 + row
- POST /bookmark - idempotent
- DELETE /bookmark - 200 + row removed
- DELETE /bookmark - idempotent
- GET /me/bookmarks - paginated ArticleSummaryResponse
- POST /bookmark - 401 unauthenticated
```

### C. HighlightServiceTest（unit）

| # | 測試 |
|---|------|
| 1 | `createHighlight_savesWithUuidAndAllFields` |
| 2 | `createHighlight_emptyNote_savesAsNull` |
| 3 | `getByArticle_returnsUserOwnedOnly` |
| 4 | `updateHighlight_byOwner_updatesColorAndNote` |
| 5 | `updateHighlight_partialUpdate_keepsUntouched` |
| 6 | `updateHighlight_byNonOwner_throwsAccessDenied` |
| 7 | `deleteHighlight_byOwner_hardDeletes` |
| 8 | `deleteHighlight_byNonOwner_throwsAccessDenied` |
| 9 | `deleteHighlight_nonExistent_throwsR0201` |
| 10 | `getByArticle_paginationOrSorting` |

### D. HighlightControllerIT

```
- POST /articles/{uuid}/highlights - 200 with valid request
- POST - 400 if snippet > 500 chars
- POST - 400 if color invalid hex
- GET /articles/{uuid}/highlights - 只回我的
- PUT /highlights/{uuid} - 200 by owner
- PUT /highlights/{uuid} - 403 by non-owner (A0006)
- DELETE /highlights/{uuid} - 200 by owner
- POST - 401 unauthenticated
```

### E. ReadingProgressServiceTest（unit，mock RedisTemplate）

| # | 測試 |
|---|------|
| 1 | `update_progressBelowThreshold_writesRedisAndAddsDirty` |
| 2 | `update_progressBelowThreshold_setsTTL` |
| 3 | `update_progressAboveThreshold_deletesRedisAndUpsertsDb` |
| 4 | `get_redisHit_returnsFromRedis` |
| 5 | `get_redisMiss_fallsBackToDbAndCachesBack` |
| 6 | `batchGetProgress_pipelinesAndMergesDbMiss` |
| 7 | `batchGetProgress_unauthenticated_returnsEmpty` |
| 8 | `update_lastHeadingNull_storesEmptyString` |

### F. ReadingProgressControllerIT（IT，含 Redis testcontainer）

```
- PUT /progress 0.6 - 200 + Redis Hash 有資料
- PUT /progress 1.0 - 200 + Redis 無 + DB 有
- GET /progress - Redis 命中
- GET /progress - Redis miss → DB → Redis 回填
- PUT - 400 if progress > 1.0
- PUT - 401 unauthenticated
```

### G. ReadingProgressFlushJobTest（unit）

| # | 測試 |
|---|------|
| 1 | `flush_dirtyEntries_upsertedAndRemovedFromDirty` |
| 2 | `flush_redisKeyExpiredButDirtyExists_removesDirtyEntry` |
| 3 | `flush_dbErrorOnSingleEntry_keepsItInDirtyForRetry` |
| 4 | `flush_articleAlreadyDeleted_removesDirtyAndKey` |

### H. 跨模組整合 IT

```
- articleResponse_includesBookmarkedFlag       (bookmark → GET /articles/{uuid} → bookmarked: true)
- articleSummary_includesLastReadProgress      (PUT progress 0.5 → GET /articles → lastReadProgress)
- articleSummary_unauth_progressIsNull
- deleteArticle_cascadeRemovesBookmarks_highlights_progress
```

### 統計

| 模組 | unit | IT |
|------|------|------|
| Bookmark | 6 | 5 |
| Highlight | 10 | 7 |
| Reading Progress Service | 8 | 5 |
| Flush Job | 4 | — |
| 跨模組 IT | — | 4 |
| **共** | **28** | **21** |

**總計 49 tests。**

---

## 10. 實作順序建議

1. **V14 migration**（同步更新 schema.md）
2. **`blog-module-reading` 模組骨架**（pom + 目錄 + root pom + blog-start）
3. **Common：RedisKeyConstant 擴充**
4. **3 個 entity + Repository**（Bookmark / Highlight / ReadingProgress）
5. **DTO + ReadingErrorCode**
6. **BookmarkService + Mapper（batch 查詢）**（TDD）
7. **BookmarkController + IT**
8. **HighlightService**（TDD）
9. **HighlightController + IT**
10. **ReadingProgressService（Redis 路徑）**（TDD with mock）
11. **ReadingProgressMapper + DB UPSERT**
12. **ReadingProgressController + IT（with Redis testcontainer）**
13. **ReadingProgressFlushJob**（TDD）
14. **ArticleQueryService 擴充 + ArticleResponse `bookmarked` / `lastReadProgress` 欄位**
15. **跨模組整合 IT**
16. **schema.md 同步寫入 V14 內容**

每步嚴格 TDD：紅 → 綠 → Refactor。測試輸出存 `./logs/`。

---

## 11. 後續批次（提醒）

完成本批後：

- **批 3** — Article Series（系列文 1/N 進度）
- **批 4** — Draft History / Versioning（編輯器版本歷史）

---

## 附錄：相關文件更新清單

實作完成時須同步更新：

- [ ] `ai-docs/schema.md` 補 V14 新增的 3 張表 + 索引（per CLAUDE.md Schema Maintenance 規則）
- [ ] （可選）`ai-docs/architecture.md` 加一段 Reading Interactions 模組描述
- [ ] auto memory：批 2 完成記錄
