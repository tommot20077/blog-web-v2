# Draft History / Versioning 模組設計

> **Spec date:** 2026-05-03
> **Branch:** `feature/draft-history-versioning`
> **Migration:** V16
> **Status:** Approved by Yuan, ready for implementation plan

---

## 1. Goal & 範圍

新增 `blog-module-version` 模組為文章編輯流程提供：

1. **編輯期災後恢復（A 主）**：自動快照（時間 + 字元差距觸發）+ 滾動 N 份保留，瀏覽器當機 / 誤關分頁可恢復
2. **手動快照與還原點（C 加）**：作者可主動建立快照（永久保留）作為里程碑
3. **發布凍結**：每次 publish 自動生成 `Published vN` 快照永久保留
4. **使用者偏好可配置**：自動快照的開關 / 保留份數 / 觸發門檻 user 可調，system 預設來自 `application.yaml`

**設計核心：以 MQ event 取代同步 facade**。Article 模組不依賴 Version 模組（單向）— 為下一階段架構重寫（把 batch 1-3 的 facade 改為 event-driven）樹立範本。

### 不做的事

- 公開讀者看「文章歷史版本」（屬未來功能；本批次 history 為作者私用）
- Admin 可看其他人 history（隱私考量；未來真有調查需求再開 logged endpoint）
- 文章間的 diff / merge（單篇 restore 即可）
- 自動快照 cross-device 同步（同一作者多裝置編輯不在範圍）

### 邊界

- 所有 endpoint 限**作者本人**（service 層 `author_id == currentUserId` 檢查）
- Controller `@PreAuthorize("isAuthenticated()")`（不限 ARTICLE_CREATE，因 version 是創作工具不是創作動作）
- 跨模組依賴：`blog-module-version → blog-module-article`（單向 inject `ArticleRepository` / `ArticleMarkdownRenderer` / `ArticleEventPublisher`）
- Article 模組對 Version **零依賴**（只發 MQ event）

---

## 2. 設計決策摘要

| 決策點 | 選擇 | 理由 |
|------|------|------|
| 主要使用情境 | A（自動快照 + 災後恢復）+ C（手動快照） | 兩者組合最 user-friendly |
| 保留範圍 | 自動滾動 N（預設 50）+ 手動永久 + Published 永久 | 容量可控，重要里程碑可 pin |
| 觸發條件（自動） | 時間 + 字元差距混合（預設 60s + 50 chars） | 平衡災後恢復粒度與 DB 壓力 |
| 快照內容 | 全 user 可編輯欄位（除 contentHtml）| markdown 是 source of truth，contentHtml 衍生 |
| Publish 行為 | 凍結 `Published vN` + 清自動 + 保留手動 | publish 為里程碑，自動快照清空省空間 |
| 儲存形式 | 純 PostgreSQL `article_versions` 表 | 個人部落格規模 DB 完全 hold 得住，TOAST 自動壓縮 TEXT |
| Restore 語意 | 後端先 stash 當前再覆蓋（不丟資料） | 防止誤點還原丟失正在編輯內容 |
| 快照類型 | 3 類 AUTO / MANUAL / PUBLISHED；pre-restore 歸 AUTO | 簡單夠用；極端 case（連按 restore）有 promote 救援機制 |
| 配置層級 | system yaml + user override（`user_preferences` K-V 表） | 通用 K-V 表為未來 bookmark/reading 偏好預留擴展 |
| 權限 | 完全私有（只有作者）；admin 不能看 | Draft 是創作隱私 |
| 跨模組通訊 | **MQ event-driven**（`ArticleContentChangedEvent`） | 避免 batch 1-3 的 facade + @Lazy 循環依賴；為下階段重寫範本 |
| 模組位置 | 新模組 `blog-module-version` | 對齊 batch 1-3 模式，避免 article 模組膨脹 |
| 配額參數 validation | retain[1,300] / interval[10,600s] / diff[0,5000，0=disable] | 防止 user 設破壞性值 |

---

## 3. 架構與模組邊界

### 依賴關係（零循環）

```
┌──────────────────┐                  ┌─────────────────────┐
│  Article module  │                  │  Version module     │
│                  │ ──publish event─→│                     │
│  ArticleService  │  (RabbitMQ)      │ ArticleVersionCon-  │
│  ArticleEvent-   │                  │ sumer (subscribe)   │
│  Publisher       │                  │                     │
└──────────────────┘                  │ VersioningService   │
        ▲                             │                     │
        │                             │ 內部 inject:        │
        │ inject (read + write,       │  - ArticleRepository│
        │ 單向，無 facade)             │  - ArticleMarkdown  │
        │                             │    Renderer         │
        │                             │  - ArticleEvent-    │
        │                             │    Publisher        │
        │                             │  - PreferenceResolver│
        │                             │  - AutoSnapshotPolicy│
        └─────────────────────────────┴─────────────────────┘
```

**Article 模組對 Version 完全不知情**（連 facade interface 都沒有）。Version 模組單向依賴 Article 模組提供的：
- `ArticleRepository` — restore 時讀當前 + 寫回
- `ArticleMarkdownRenderer` — restore 後重 render contentHtml
- `ArticleEventPublisher` — restore 後通知 search re-index（發既有 ArticleUpdatedEvent）

### 為什麼 MQ-driven 而非 facade

Batch 1-3 用同步 facade pattern（comment / reading / series），series 開始撞循環依賴用 `@Lazy` setter injection 蓋牌。Batch 4 改示範 MQ 解耦：

| Aspect | Facade + @Lazy（batch 1-3）| MQ event（batch 4）|
|---|---|---|
| Article 對下游模組 | 知情（inject facade interface）| 不知情（只發 event）|
| 啟動順序 | 脆弱（@Lazy 蓋循環）| 健壯（單向 bean graph）|
| 測試 | 雙向 mock 複雜 | Producer / Consumer 各自獨立 mock |
| 擴展新訂閱者 | 加 facade method + impl | 訂同 routing key 即可 |

**下階段重構目標**：把 batch 1-3 的 facade 對齊本批次的 MQ pattern。

### Version 模組組件

```
blog-module-version/
├─ pom.xml
├─ src/main/java/dowob/xyz/blog/module/version/
│  ├─ config/    VersionRabbitMqConfig
│  ├─ consumer/  ArticleVersionConsumer       (@RabbitListener)
│  ├─ controller/ VersionController
│  │              PreferenceController
│  ├─ service/   VersioningService            (write/restore/list/promote/delete)
│  │             AutoSnapshotPolicy            (時間+字元門檻判斷)
│  │             PreferenceResolver            (user_pref → yaml fallback)
│  ├─ repository/ ArticleVersionRepository
│  │              UserPreferenceRepository
│  ├─ mapper/    VersionMapper
│  ├─ model/
│  │  ├─ ArticleVersion.java                  (entity, @Table article_versions)
│  │  ├─ UserPreference.java                  (entity, @Table user_preferences)
│  │  └─ dto/
│  │     ├─ request/  CreateManualSnapshotRequest, UpdatePreferenceRequest
│  │     └─ response/ VersionSummaryResponse, VersionDetailResponse,
│  │                  EffectiveConfigResponse, AutoSnapshotConfig (record)
│  └─ exception/ VersionErrorCode (V0101-V0106)
└─ src/test/...
```

---

## 4. Schema（V16）

### article_versions

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 對外識別 |
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | 文章刪除連動清快照 |
| author_id | BIGINT | NOT NULL REFERENCES users(id) | 反正規化（避免 query JOIN articles 拿 author 做權限檢查）|
| type | VARCHAR(20) | NOT NULL CHECK (type IN ('AUTO','MANUAL','PUBLISHED')) | 用 CHECK 不用 ENUM type |
| title | VARCHAR(255) | NOT NULL | snapshot 當時 |
| slug | VARCHAR(255) | NOT NULL | snapshot 當時 |
| content | TEXT | NOT NULL | markdown source（PG TOAST 自動壓縮）|
| summary | VARCHAR(500) | NULL | snapshot 當時 |
| category_id | BIGINT | NULL | 不加 FK（category 後刪不該讓 snapshot 失效）|
| cover_image_url | VARCHAR(512) | NULL | snapshot 當時 |
| status | VARCHAR(20) | NOT NULL | snapshot 當時 article.status |
| tags | UUID[] | NULL | PG array — 快照當時 tag UUID 列表（避免 join 表）|
| note | VARCHAR(255) | NULL | MANUAL: user 命名；PUBLISHED: 自動 `Published vN` |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `article_versions_pkey`（auto）
- `article_versions_uuid_key`（auto, UNIQUE）
- `idx_article_versions_article_created` on (article_id, created_at DESC) — 列表主路徑
- `idx_article_versions_article_type` on (article_id, type) — 配額計算 + type 篩選

**FK:**
- `article_id` → `articles(id)` ON DELETE CASCADE
- `author_id` → `users(id)` NO ACTION

### user_preferences

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_id | BIGINT | NOT NULL REFERENCES users(id) ON DELETE CASCADE | |
| pref_key | VARCHAR(100) | NOT NULL | 如 `version.auto.retain` |
| pref_value | TEXT | NOT NULL | 字串值（boolean / int / json 都序列化）|
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_user_preferences_user_key` UNIQUE (user_id, pref_key)

**Indexes:**
- `user_preferences_pkey`（auto）
- `uq_user_preferences_user_key`（auto, UNIQUE）— 等同 (user_id) 索引

**初始 pref keys（4 個 version 模組用 + 未來其他模組擴展）:**

| pref_key | 型別 | yaml 預設 |
|---|---|---|
| `version.auto.enabled` | boolean | true |
| `version.auto.retain` | int | 50 |
| `version.auto.interval-seconds` | int | 60 |
| `version.auto.diff-chars` | int | 50 |

User override = INSERT/UPSERT 一筆 row per non-null 欄位。Read 時 fallback：user_preferences → application.yaml。

### V16 SQL 框架

```sql
CREATE TABLE article_versions (
    id              BIGSERIAL    PRIMARY KEY,
    uuid            UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    article_id      BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    author_id       BIGINT       NOT NULL REFERENCES users(id),
    type            VARCHAR(20)  NOT NULL CHECK (type IN ('AUTO','MANUAL','PUBLISHED')),
    title           VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    summary         VARCHAR(500) NULL,
    category_id     BIGINT       NULL,
    cover_image_url VARCHAR(512) NULL,
    status          VARCHAR(20)  NOT NULL,
    tags            UUID[]       NULL,
    note            VARCHAR(255) NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_article_versions_article_created ON article_versions(article_id, created_at DESC);
CREATE INDEX idx_article_versions_article_type    ON article_versions(article_id, type);

CREATE TABLE user_preferences (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pref_key   VARCHAR(100) NOT NULL,
    pref_value TEXT         NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_preferences_user_key UNIQUE (user_id, pref_key)
);
```

V16 不動 articles 表。

---

## 5. 快照寫入流程（4 種觸發）

### 5.1 AUTO（自動快照，event-driven）

**觸發鏈：**
```
[Editor 自動 save] → ArticleController.updateArticle (HTTP)
                  → ArticleServiceImpl.updateArticle()
                       └─ DB UPDATE articles
                       └─ articleEventPublisher.publishContentChanged(article, SAVED)
                  → MQ exchange=article.events routing=article.content.changed
                       ↓
                  ArticleVersionConsumer @RabbitListener queue=version.snapshot
                       └─ AutoSnapshotPolicy.shouldSnapshot(articleId)
                       └─ 若 yes: VersioningService.recordAutoSnapshot(articleId)
```

**`AutoSnapshotPolicy.shouldSnapshot(articleId)` 演算法：**

```
1. article = articleRepo.findById(articleId)            // 拿 author + 當前 content
2. cfg = preferenceResolver.resolveForUser(article.authorId)
3. if !cfg.enabled                                       → false
4. lastAuto = repo.findLatestByArticleAndType(articleId, AUTO)
5. if lastAuto == null                                   → true (第一次)
6. if now - lastAuto.createdAt < cfg.intervalSeconds     → false
7. if cfg.diffChars > 0:
       diff = abs(article.content.length - lastAuto.content.length)
       if diff < cfg.diffChars                           → false
   // diffChars == 0 表示 disabled，純看時間
8. return true
```

⚠ Step 7 用 `abs(length 差)` 是字元變動的近似 — 完全準確要算 Levenshtein 太貴。新增段落準確；同字數改寫會漏觸發但下次時間/字元門檻撞到時還會抓到。可接受。

**寫入 + 配額執行（同 transaction）:**

```sql
INSERT INTO article_versions (uuid, article_id, author_id, type, title, slug, content,
                               summary, category_id, cover_image_url, status, tags, note)
VALUES (...);

-- 滾動 N（cfg.retain，預設 50）
DELETE FROM article_versions
 WHERE id IN (
   SELECT id FROM article_versions
    WHERE article_id = #{articleId} AND type = 'AUTO'
    ORDER BY created_at DESC
    OFFSET #{retain}
 );
```

### 5.2 MANUAL（手動快照，同步）

**觸發鏈：**
```
[User 點「儲存快照」] → POST /api/v1/articles/{uuid}/versions/manual
                       body: { note?: "發布前最後改" }
                       ↓
                    VersionController.createManual()
                       └─ 權限檢查（必須是 article author，否則 V0102）
                       └─ VersioningService.recordManualSnapshot(articleId, note)
                            └─ INSERT article_versions (type=MANUAL, note=...)
                            └─ 不執行配額（手動永久保留）
```

直接同步 — UX 是 user 點按鈕後立即看到「已存」，不走 MQ。

### 5.3 PUBLISHED（發布凍結，event-driven）

**觸發鏈：**
```
[User 點 publish] → ArticleServiceImpl.publishArticle()
                       └─ DB UPDATE articles SET status='PUBLISHED'
                       └─ articleEventPublisher.publishPublished(article)        // 既有，給 search/recommend
                       └─ articleEventPublisher.publishContentChanged(article, PUBLISHED)  // 新增，給 version
                  → MQ
                       ↓
                  ArticleVersionConsumer
                       └─ VersioningService.freezePublished(articleId)
                            ├─ vN = count(article_id=?, type='PUBLISHED') + 1
                            ├─ INSERT article_versions (type='PUBLISHED', note='Published vN')
                            └─ DELETE FROM article_versions WHERE article_id=? AND type='AUTO'
```

**為什麼發兩個 event 而非一個：** ArticlePublishedEvent payload 大（含 contentText 給 ES）；ArticleContentChangedEvent 是輕量 marker（給 version）。職責分離。

### 5.4 PRE_RESTORE（restore 前 stash，歸 AUTO，同步）

由 `VersioningService.restore` 內部直接做（不走 MQ，因為 restore 本身就是同步用戶動作）：

```
restore(versionUuid):
  1. version = repo.findByUuid(versionUuid)
  2. article = articleRepo.findById(version.articleId)
  3. 權限檢查（version.authorId == currentUserId，否則 V0102）
  4. 寫 stash：INSERT article_versions(type=AUTO, copy 當前 article 全部欄位)
     └─ 觸發 retention（剩 49 真正 AUTO + 1 stash = 50）
  5. 寫回 articles：UPDATE articles SET title/slug/content/summary/category_id/cover_image_url/status
                                          = version 內容
  6. 清舊 article_tags + 寫回 version.tags 對應的 article_tags rows
     └─ tag 在 stash 後可能被刪除 → 找不到的 tag log warn 但不報錯
  7. 重新 render contentHtml = articleMarkdownRenderer.render(version.content)
  8. articleEventPublisher.publishContentChanged(article, RESTORED)  → version consumer no-op
  9. articleEventPublisher.publishUpdated(article)                    → search re-index（PUBLISHED 才動作）
  return ArticleResponse
```

⚠ Step 4 的 stash 跟既有 AUTO snapshot 邏輯相同，共用 `recordAutoSnapshot()`（內部 INSERT + retention）。差別只在 step 5-9 接著做覆蓋與 event 發送。

---

## 6. 配置層級（PreferenceResolver + yaml + API）

### `PreferenceResolver` Service

```java
@Service
@RequiredArgsConstructor
public class PreferenceResolver {
    private final UserPreferenceRepository repo;

    @Value("${version.auto.enabled:true}")           private boolean defaultEnabled;
    @Value("${version.auto.retain:50}")              private int defaultRetain;
    @Value("${version.auto.interval-seconds:60}")    private int defaultIntervalSeconds;
    @Value("${version.auto.diff-chars:50}")          private int defaultDiffChars;

    public AutoSnapshotConfig resolveForUser(Long userId) {
        Map<String, String> userPrefs = repo.findByUserIdAsMap(userId);
        return new AutoSnapshotConfig(
            getBool(userPrefs, "version.auto.enabled",          defaultEnabled),
            getInt (userPrefs, "version.auto.retain",           defaultRetain),
            getInt (userPrefs, "version.auto.interval-seconds", defaultIntervalSeconds),
            getInt (userPrefs, "version.auto.diff-chars",       defaultDiffChars)
        );
    }
}

public record AutoSnapshotConfig(
    boolean enabled, int retain, int intervalSeconds, int diffChars
) {}
```

### `application.yaml` 新增

```yaml
version:
  auto:
    enabled: true
    retain: 50
    interval-seconds: 60
    diff-chars: 50
```

Admin 改全站預設 = 改 yaml + redeploy（個人部落格頻率夠低）。

### Validation 範圍

| 欄位 | 範圍 | 不在範圍 → V0105 |
|---|---|---|
| `enabled` | true/false | bool 自然限定 |
| `retain` | [1, 300] | 上限 300 |
| `intervalSeconds` | [10, 600] | 上限 10 分鐘 |
| `diffChars` | [0, 5000] | 0 = disabled（純時間觸發）|

### User-facing API

```
GET    /api/v1/me/preferences/version              → EffectiveConfigResponse
PUT    /api/v1/me/preferences/version              → patch override（partial，null 欄位 skip）
DELETE /api/v1/me/preferences/version/{key}        → 重置某 key 回系統預設
```

`EffectiveConfigResponse` 每個欄位含 `value` + `source`：

```json
{
  "enabled":          { "value": true, "source": "system" },
  "retain":           { "value": 30,   "source": "user" },
  "intervalSeconds":  { "value": 60,   "source": "system" },
  "diffChars":        { "value": 100,  "source": "user" }
}
```

`UpdatePreferenceRequest`（PATCH 語意）:
```json
{ "enabled": true|null, "retain": 30|null, "intervalSeconds": 60|null, "diffChars": 100|null }
```

UPSERT per non-null 欄位：

```sql
INSERT INTO user_preferences (user_id, pref_key, pref_value) VALUES (?, ?, ?)
ON CONFLICT (user_id, pref_key) DO UPDATE
   SET pref_value = EXCLUDED.pref_value, updated_at = CURRENT_TIMESTAMP;
```

---

## 7. API 端點 + 錯誤碼 + DTO

### Endpoints（Version 模組）

| Method | Path | Auth | 描述 |
|---|---|---|---|
| GET | `/api/v1/articles/{uuid}/versions?type=&page=&size=` | author 本人（service 檢查）| 列表（不含 content）|
| GET | `/api/v1/articles/{uuid}/versions/{versionUuid}` | author 本人 | 詳情（含 content，給預覽）|
| POST | `/api/v1/articles/{uuid}/versions/manual` | author 本人 | 手動快照 |
| POST | `/api/v1/articles/{uuid}/versions/{versionUuid}/restore` | author 本人 | 還原 |
| POST | `/api/v1/articles/{uuid}/versions/{versionUuid}/promote` | author 本人 | AUTO → MANUAL（救援機制）|
| DELETE | `/api/v1/articles/{uuid}/versions/{versionUuid}` | author 本人 | 刪除（只允許 MANUAL）|

### Endpoints（Preference 模組 — 通用）

| Method | Path | Auth | 描述 |
|---|---|---|---|
| GET | `/api/v1/me/preferences/version` | 任何登入者 | Effective config |
| PUT | `/api/v1/me/preferences/version` | 任何登入者 | Patch override |
| DELETE | `/api/v1/me/preferences/version/{key}` | 任何登入者 | 重置 |

⚠ Controller 層 `@PreAuthorize("isAuthenticated()")`，service 層做 author check。

### 錯誤碼（VersionErrorCode）

| Code | Name | HTTP | 訊息 |
|---|---|---|---|
| V0101 | VERSION_NOT_FOUND | 400 | Version 不存在 |
| V0102 | VERSION_ACCESS_DENIED | 400 | 不可操作他人的 Version |
| V0103 | CANNOT_DELETE_PUBLISHED | 400 | PUBLISHED 凍結快照不可刪除 |
| V0104 | CANNOT_PROMOTE_NON_AUTO | 400 | 只有 AUTO 類型可升級為 MANUAL |
| V0105 | PREFERENCE_INVALID | 400 | 配置值超出合理範圍 |
| V0106 | ARTICLE_NOT_FOUND | 400 | 操作的 Article 不存在 |

### DTOs

**`VersionSummaryResponse`（列表用，不含 content）:**

```java
{
  uuid: UUID,
  type: AUTO|MANUAL|PUBLISHED,
  note: String?,
  createdAt: LocalDateTime,
  authorId: Long,
  contentLength: int       // 字元數，給前端排序/顯示用
}
```

**`VersionDetailResponse`（詳情用，含完整快照）:**

```java
{
  uuid, type, note, createdAt, authorId,
  // 快照當時的 article 內容（除 contentHtml 之外的 user 可編輯欄位）
  title, slug, content, summary,
  categoryId, coverImageUrl, status,
  tags                     // List<UUID>
}
```

**`CreateManualSnapshotRequest`:**
```java
{ note: String? @Size(max=255) }
```

**`UpdatePreferenceRequest`（partial）:**
```java
{
  enabled: Boolean?,
  retain: Integer? @Min(1) @Max(300),
  intervalSeconds: Integer? @Min(10) @Max(600),
  diffChars: Integer? @Min(0) @Max(5000)
}
```

**`EffectiveConfigResponse`** — 結構見 §6。

---

## 8. MQ Event 設計

### Event Record 定義

新檔 `blog-module-article/.../event/ArticleContentChangedEvent.java`：

```java
public record ArticleContentChangedEvent(
    Long articleId,
    UUID articleUuid,
    Long authorId,
    Action action,
    Instant occurredAt
) {
    public enum Action { SAVED, PUBLISHED, RESTORED }
}
```

**設計原則：payload 故意輕量** — 只帶 ID + 動作，consumer 自己拉完整 article。對齊既有 `ArticleViewedEvent` 風格。

### Routing 配置

`ArticleRabbitMqConfig` 加常數：
```java
public static final String ROUTING_KEY_CONTENT_CHANGED = "article.content.changed";
```

新檔 `VersionRabbitMqConfig`：
```java
@Configuration
public class VersionRabbitMqConfig {
    public static final String QUEUE_VERSION_SNAPSHOT = "version.snapshot";
    public static final String DLQ_ROUTING_KEY = "dlq.version.snapshot";

    @Bean Queue versionSnapshotQueue() { ... dlqArgs ... }
    @Bean Binding bindVersionSnapshot(Queue, @Qualifier("articleEventsExchange") TopicExchange) {
        return BindingBuilder.bind(...).to(...).with(ArticleRabbitMqConfig.ROUTING_KEY_CONTENT_CHANGED);
    }
}
```

### Consumer 實作

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class ArticleVersionConsumer {
    private final VersioningService versioningService;
    private final AutoSnapshotPolicy autoSnapshotPolicy;

    @RabbitListener(queues = VersionRabbitMqConfig.QUEUE_VERSION_SNAPSHOT)
    public void onContentChanged(ArticleContentChangedEvent event) {
        try {
            switch (event.action()) {
                case SAVED -> {
                    if (autoSnapshotPolicy.shouldSnapshot(event.articleId())) {
                        versioningService.recordAutoSnapshot(event.articleId());
                    }
                }
                case PUBLISHED -> versioningService.freezePublished(event.articleId());
                case RESTORED  -> { /* no-op，restore 已在 VersioningService 內部寫過 stash */ }
            }
        } catch (Exception e) {
            log.error("處理 ArticleContentChangedEvent 失敗 articleId={} action={}",
                event.articleId(), event.action(), e);
            throw e;  // re-throw 讓 RabbitMQ requeue / 進 DLQ
        }
    }
}
```

### Producer 改動（Article 模組）

抽出 `ArticleEventPublisher` component（位 article 模組），把 `ArticleServiceImpl` 既有的 `publishUpdatedEvent` private method refactor 進來，並加 `publishContentChanged`：

```java
@Component
@RequiredArgsConstructor
public class ArticleEventPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final UserFacade userFacade;
    private final ArticleMapper articleMapper;

    public void publishContentChanged(Article article, Action action) {
        try {
            rabbitTemplate.convertAndSend(
                ArticleRabbitMqConfig.EXCHANGE,
                ArticleRabbitMqConfig.ROUTING_KEY_CONTENT_CHANGED,
                new ArticleContentChangedEvent(
                    article.getId(), article.getUuid(), article.getAuthorId(),
                    action, Instant.now()
                )
            );
        } catch (Exception e) {
            log.warn("ArticleContentChangedEvent 發送失敗（best-effort）: {}", e.getMessage(), e);
        }
    }

    /** 既有 ArticleUpdatedEvent — only PUBLISHED 才發 */
    public void publishUpdated(Article article) { ... }
    /** 既有 ArticlePublishedEvent / Deleted / Tagged — refactor 進來 */
    public void publishPublished(Article article) { ... }
    public void publishDeleted(Article article) { ... }
    public void publishTagged(Article article, List<UUID> tagIds) { ... }
}
```

`ArticleServiceImpl` 改 inject `ArticleEventPublisher`，原本散落在各 method 的 MQ 發送邏輯統一走 publisher。

### 可靠性語意（at-least-once / best-effort）

| 場景 | 行為 | 接受度 |
|---|---|---|
| Article 寫成功 + MQ broker 不可用 → SAVED event 漏發 | 漏一次自動快照；下次 update 又會 trigger | ✓ 可接受 |
| Article 寫成功 + PUBLISHED event 漏發 | published version 沒凍結；user 看不到 published 標記 | ⚠ 較嚴重但低頻；user 可手動 snapshot 救援 |
| MQ broker OK + consumer crash | requeue 或進 DLQ；admin 介入 | ✓ 既有 DLQ 機制 |
| Consumer DB 寫入失敗 | re-throw → requeue | ✓ |

採 best-effort 對齊既有 ArticleServiceImpl 慣例。

---

## 9. 跨模組整合（Restore 後的 search 通知）

**問題:** Version 模組 restore 寫回 article 後，search index 怎麼同步？

**選 A（採用）: 複用既有 `ArticleUpdatedEvent`**

`VersioningService.restore` 末尾呼叫 `articleEventPublisher.publishUpdated(article)`。既有 search listener 已訂 `article.updated`（only PUBLISHED 狀態），無需改 search 模組。

```java
// restore 末尾
articleEventPublisher.publishContentChanged(article, RESTORED);  // version consumer no-op
articleEventPublisher.publishUpdated(article);                    // search re-index（PUBLISHED 才動作）
```

**為什麼不選 B（改 search 訂 ContentChanged）：** 超出 batch 4 範圍（屬下一階段架構重寫）；ArticleUpdatedEvent payload 含 contentText / authorUsername / tags 給 ES 索引，ContentChanged 是輕量 marker，改 binding 會讓 search 多一次 DB 查。下一階段重構統一處理。

### Article 模組改動清單（給 batch 4 用）

1. 新增 `ArticleContentChangedEvent` record
2. `ArticleRabbitMqConfig` 加 `ROUTING_KEY_CONTENT_CHANGED` 常數
3. 新建 `ArticleEventPublisher` component
4. `ArticleServiceImpl` refactor：原本散落的 `rabbitTemplate.convertAndSend` 改用 publisher；`updateArticle / publishArticle` 加發 `ContentChanged(SAVED/PUBLISHED)`

零循環：Article 模組對 Version 完全不知情。

---

## 10. 測試策略（~50 tests）

### Unit tests（22）

| 測試類別 | 數量 | 涵蓋 |
|---|---|---|
| `AutoSnapshotPolicyTest` | 6 | enabled false / 第一次 / interval 不到 / diff 不到 / diffChars=0（純時間）/ 都過 |
| `VersioningServiceTest` | 12 | recordAuto + retention / recordManual / freezePublished + 清 AUTO / restore + stash / promote AUTO→MANUAL / delete MANUAL / V0101-V0106 |
| `PreferenceResolverTest` | 4 | 全 yaml fallback / 全 user override / 部分 override / disabled |

### IT（Testcontainers + MockMvc，18）

| 測試類別 | 數量 | 涵蓋 |
|---|---|---|
| `VersionControllerIT` | 11 | GET list / GET detail / POST manual / POST restore / POST promote / DELETE / 401 / V0101 / V0102 / V0103 / V0104 |
| `ArticleVersionConsumerIT` | 4 | SAVED→AUTO 寫入 / PUBLISHED→freeze + 清 AUTO / RESTORED→no-op / retention(51 個 AUTO 後最舊被刪) |
| `PreferenceControllerIT` | 5 | GET fallback / GET partial / PUT full / PUT invalid (V0105) / DELETE 重置 |

### Cross-module IT（5）— 端到端流程

| 測試 | 描述 |
|---|---|
| 1 | PUT /articles/{uuid} → MQ 觸發 → article_versions 多一筆 AUTO（驗證自動快照鏈）|
| 2 | POST /articles/{uuid}/publish → freeze 凍結 + 清 AUTO（驗證 publish 鏈）|
| 3 | POST /versions/{uuid}/restore → article 內容更新 + ArticleUpdatedEvent 發出（驗證 restore + search 通知）|
| 4 | DELETE /articles/{uuid} → article_versions ON DELETE CASCADE 自動清（驗證 schema 連動）|
| 5 | User config 改 `enabled=false` → 後續 update 不再產生 AUTO |

---

## 11. 模組依賴 / 收尾

### `blog-module-version/pom.xml`

```xml
<dependencies>
  <dependency><groupId>dowob.xyz</groupId><artifactId>blog-infrastructure</artifactId></dependency>
  <dependency><groupId>dowob.xyz</groupId><artifactId>blog-module-article</artifactId></dependency>

  <dependency><groupId>dowob.xyz</groupId><artifactId>blog-db-migration</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
  <dependency><groupId>com.redis</groupId><artifactId>testcontainers-redis</artifactId><scope>test</scope></dependency>
  <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
</dependencies>
```

**單向依賴**：`version → article`（不含 user / search 等）。

### Root pom.xml + blog-start/pom.xml

- root pom `<modules>` 加 `blog-module-series` 之後：`<module>blog-module-version</module>`
- root pom `<dependencyManagement>` Internal Modules 區塊加版本宣告
- `blog-start/pom.xml` 加 dependency

### MyBatisConfig @MapperScan

加入 `dowob.xyz.blog.module.version.mapper`。**批 1/2/3 教訓 — 漏這個 E2E 必爆**。

### DatabaseCleaner 補新表

`blog-start/.../e2e/support/DatabaseCleaner.cleanAll()` 加：
```java
jdbcTemplate.execute("DELETE FROM article_versions");
jdbcTemplate.execute("DELETE FROM user_preferences");
```

順序：`articles` 之前（FK ON DELETE CASCADE 其實會自動，但顯式刪較直觀）。

### Schema.md 更新

加 `article_versions` + `user_preferences` 兩張表完整說明 + Migration Index V16 條目。

---

## 12. Implementation Plan 預估（~17 tasks）

```
T1   V16 migration（article_versions + user_preferences）
T2   refactor article 模組：抽 ArticleEventPublisher + 加 ArticleContentChangedEvent
T3   ArticleServiceImpl 改用 publisher，發 ContentChanged event
T4   blog-module-version 模組骨架 + pom + MapperScan
T5   ArticleVersion / UserPreference entity + Repository
T6   VersionErrorCode + DTOs (request/response/AutoSnapshotConfig)
T7   VersionMapper（含 batch query / retention DELETE / count by type）
T8   PreferenceResolver + AutoSnapshotPolicy (TDD，~10 unit tests)
T9   VersioningService.recordAutoSnapshot + retention (TDD)
T10  VersioningService.recordManualSnapshot / freezePublished / promote / delete (TDD)
T11  VersioningService.restore (TDD，stash + 寫回 + tags + render + events)
T12  ArticleVersionConsumer + VersionRabbitMqConfig + IT
T13  VersionController + IT (11 cases)
T14  PreferenceController + IT (5 cases)
T15  Cross-module IT（5 個 e2e flow）
T16  DatabaseCleaner 補表清理 + schema.md 更新 V16
T17  Plan-time 補做：跨模組整合驗證 + 最終 sanity check
```

---

## 13. 後續批次預告

- **下一階段架構重寫**：把 batch 1-3 的 facade pattern（comment / reading / series）改成 MQ-driven，對齊本批次 + search/recommend 既有設計。可能新事件如 `ArticleStateEvent` 替代多個 facade method。
- **可能的批 5+**：Bookmark 分類、`GET /me/highlights` 列表頁、Series 章節（series 內小節分類）、Tags index 整合 Series。
