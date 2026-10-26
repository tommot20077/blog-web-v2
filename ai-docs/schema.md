# Database Schema（PostgreSQL）

> **真相來源**：本文件描述套用所有 migrations V1–V28 後的當前 DB schema。
> **維護規則**：每次新增 Flyway migration 都必須同步更新此文件（詳見 CLAUDE.md §Schema Maintenance）。
> 最後更新版本：**V28**

---

## Extension

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
```

---

## Tables

### users

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 對外公開識別碼 |
| email | VARCHAR(255) | NOT NULL UNIQUE | |
| password_hash | VARCHAR(255) | NOT NULL | bcrypt 雜湊 |
| nickname | VARCHAR(50) | NOT NULL | |
| username | VARCHAR(50) | NOT NULL UNIQUE DEFAULT '' | V3 新增 |
| avatar_url | VARCHAR(512) | | |
| bio | TEXT | | |
| website | VARCHAR(255) | | V4 新增 |
| social_links | TEXT | | V4 新增 JSONB；V10 改為 TEXT（Spring Data JDBC 相容） |
| location | VARCHAR(100) | | V18 新增；所在地（自由文字，nullable） |
| notification_comment | BOOLEAN | NOT NULL DEFAULT TRUE | V18 新增；留言通知偏好 |
| notification_like | BOOLEAN | NOT NULL DEFAULT TRUE | V18 新增；按讚通知偏好 |
| notification_review | BOOLEAN | NOT NULL DEFAULT TRUE | V18 新增；審核結果通知偏好 |
| notification_follow | BOOLEAN | NOT NULL DEFAULT TRUE | V18 新增；追蹤通知偏好 |
| notification_newsletter | BOOLEAN | NOT NULL DEFAULT TRUE | V18 新增；電子報訂閱偏好 |
| role | VARCHAR(20) | NOT NULL DEFAULT 'USER' | USER / AUTHOR / ADMIN |
| status | VARCHAR(20) | NOT NULL | PENDING / ACTIVE / DELETED |
| email_verified | BOOLEAN | NOT NULL DEFAULT FALSE | |
| token_version | VARCHAR(10) | | JWT stateful version |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `users_pkey`（auto）on id
- `users_uuid_key`（auto, UNIQUE）on uuid
- `users_email_key`（auto, UNIQUE）on email
- `users_username_key`（auto, UNIQUE）on username

**Foreign keys:** 無（被其他表參照）

---

### verification_tokens

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_id | BIGINT | NOT NULL REFERENCES users(id) | |
| token | VARCHAR(255) | NOT NULL UNIQUE | |
| type | VARCHAR(20) | NOT NULL | EMAIL_VERIFY / PASSWORD_RESET |
| expires_at | TIMESTAMP | NOT NULL | |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `verification_tokens_pkey`（auto）on id
- `verification_tokens_token_key`（auto, UNIQUE）on token

**Foreign keys:**
- `user_id` → `users(id)`（NO ACTION）

---

### articles

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 對外公開識別碼 |
| author_id | BIGINT | NOT NULL REFERENCES users(id) | |
| title | VARCHAR(200) | NOT NULL | |
| slug | VARCHAR(250) | NOT NULL UNIQUE | SEO-friendly URL 段落 |
| summary | VARCHAR(500) | | |
| content_md | TEXT | NOT NULL | 原始 Markdown |
| content_html | TEXT | | V5 新增；Markdown 預渲染 HTML |
| cover_image_url | VARCHAR(512) | | |
| status | VARCHAR(20) | NOT NULL DEFAULT 'DRAFT' | DRAFT / PENDING / PUBLISHED / REJECTED |
| reject_reason | TEXT | | V11 新增 |
| view_count | BIGINT | NOT NULL DEFAULT 0 | 反正規化計數 |
| like_count | INTEGER | NOT NULL DEFAULT 0 | V13 由 BIGINT 改為 INTEGER |
| comment_count | INTEGER | NOT NULL DEFAULT 0 | 反正規化計數 |
| version | BIGINT | NOT NULL DEFAULT 1 | V7 新增；樂觀鎖 |
| series_id | BIGINT | NULL REFERENCES series(id) ON DELETE SET NULL | V15 新增；所屬系列 |
| series_position | INTEGER | NULL | V15 新增；在系列內的排序位置 |
| toc | TEXT | | V19 新增；章節導覽 JSON |
| published_at | TIMESTAMP | | |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `articles_pkey`（auto）on id
- `articles_uuid_key`（auto, UNIQUE）on uuid
- `articles_slug_key`（auto, UNIQUE）on slug
- `idx_articles_author_id`（V2）on author_id
- `idx_articles_status`（V2）on status
- `idx_articles_created_at`（V2）on created_at DESC
- ~~`idx_articles_uuid`~~ V13 已 DROP（與 UNIQUE constraint 重複）
- `idx_articles_series_position`（V15, partial）on (series_id, series_position) WHERE series_id IS NOT NULL
- `idx_articles_published_latest`（V22, partial）on (published_at DESC NULLS LAST, id DESC) WHERE status = 'PUBLISHED'——公開列表預設排序；`view_count` / `comment_count` 刻意不建排序索引（頻繁回寫，避免失去 HOT update）

**Foreign keys:**
- `author_id` → `users(id)`（NO ACTION）
- `series_id` → `series(id)`（ON DELETE SET NULL）

---

### categories

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | |
| name | VARCHAR(50) | NOT NULL UNIQUE | |
| slug | VARCHAR(60) | NOT NULL UNIQUE | V23 起受 CHECK 約束：小寫英數字以單一連字號分隔 |
| description | VARCHAR(200) | | |
| sort_order | INT | NOT NULL DEFAULT 0 | 排序用 |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `categories_pkey`（auto）on id
- `categories_uuid_key`（auto, UNIQUE）on uuid
- `categories_name_key`（auto, UNIQUE）on name
- `categories_slug_key`（auto, UNIQUE）on slug

**Constraints:**
- `ck_categories_slug_format`（V23）CHECK (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$')——須與 `Category.SLUG_PATTERN`（API 層 `@Pattern`）一致

**Foreign keys:** 無

---

### article_categories

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| article_id | BIGINT | REFERENCES articles(id) ON DELETE CASCADE | |
| category_id | BIGINT | REFERENCES categories(id) ON DELETE CASCADE | |

PRIMARY KEY (article_id, category_id)

**Indexes:**
- `article_categories_pkey`（auto, composite PK）on (article_id, category_id)
- `idx_article_categories_category_id`（V6）on category_id

**Foreign keys:**
- `article_id` → `articles(id)` ON DELETE CASCADE
- `category_id` → `categories(id)` ON DELETE CASCADE

---

### tags

> V9 重建：原 V1 的 BIGSERIAL PK 版本已 DROP；現為 UUID PK。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PRIMARY KEY | |
| name | VARCHAR(100) | NOT NULL UNIQUE | |
| slug | VARCHAR(120) | NOT NULL UNIQUE | |
| color | VARCHAR(20) | | |
| icon | VARCHAR(100) | | |
| description | TEXT | | |
| parent_id | UUID | REFERENCES tags(id) | 階層式 tag；自我參照 |
| usage_count | INTEGER | NOT NULL DEFAULT 0 | 反正規化計數 |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `tags_pkey`（auto）on id
- `tags_name_key`（auto, UNIQUE）on name
- `tags_slug_key`（auto, UNIQUE）on slug

**Foreign keys:**
- `parent_id` → `tags(id)`（NO ACTION，自我參照）

---

### article_tags

> V9 重建：改以 articles.uuid 與 tags.id（UUID）為外鍵。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| article_id | UUID | NOT NULL REFERENCES articles(uuid) ON DELETE CASCADE | |
| tag_id | UUID | NOT NULL REFERENCES tags(id) | |

PRIMARY KEY (article_id, tag_id)

**Indexes:**
- `article_tags_pkey`（auto, composite PK）on (article_id, tag_id)

**Foreign keys:**
- `article_id` → `articles(uuid)` ON DELETE CASCADE
- `tag_id` → `tags(id)`（NO ACTION）

---

### user_tag_follows

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| user_id | UUID | NOT NULL | 注意：不是 users.id（BIGINT），而是 uuid |
| tag_id | UUID | NOT NULL REFERENCES tags(id) | |

PRIMARY KEY (user_id, tag_id)

**Indexes:**
- `user_tag_follows_pkey`（auto, composite PK）on (user_id, tag_id)

**Foreign keys:**
- `tag_id` → `tags(id)`（NO ACTION）

> ⚠️ `user_id` 欄位型別為 UUID，但沒有直接 FK 指向 `users(id)`（BIGINT）或 `users(uuid)`。這是 V9 遺留設計，若後續需要強 FK 完整性須補 migration。

---

### series

> V15 新增。文章系列（連載），一個作者可建立多個系列。V29（M4-b）起作者以 `author_uuid` 識別，表上沒有指向其他模組的 Long 欄位與 FK。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 對外公開識別碼 |
| title | VARCHAR(255) | NOT NULL | 系列標題 |
| slug | VARCHAR(255) | NOT NULL UNIQUE | SEO-friendly URL 段落 |
| description | TEXT | NULL | 系列描述 |
| cover_image_url | VARCHAR(512) | NULL | 封面圖 URL |
| author_uuid | UUID | NOT NULL | V29：作者的公開 UUID（取代 `author_id`）；**不建 FK**（跨模組不得有資料庫 FK），作者顯示資訊由 `UserFacade.getDisplayInfoByUuids` 批次解析 |
| article_count | INTEGER | NOT NULL DEFAULT 0 | 反正規化計數；由 service 維護（加入／移出以條件式寫入的 affected rows 決定，刪除文章由 `ArticleDeletedEvent` consumer 遞減） |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `series_pkey`（auto）on id
- `series_uuid_key`（auto, UNIQUE）on uuid
- `series_slug_key`（auto, UNIQUE）on slug
- `idx_series_author`（V15 建立於 `author_id`；V29 以同名在 `author_uuid` 上重建）on author_uuid

**Foreign keys:** 無（V29 已移除 `author_id` → `users(id)`（NO ACTION）；使用者只做軟刪除，該 FK 從未觸發，拿掉後行為不變，故不新增連動）。
被參照：`articles.series_id` → `series(id)`（ON DELETE SET NULL，article 模組的 FK，M6 才改為 `series_uuid`；刪除 series 時文章脫離系列的行為不變）。

---

### user_article_likes

> V15 由 `article_likes` 改名（命名一致化）。V26 起使用者與文章一律以 UUID 識別，不再有 Long 欄位與 FK。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | PK 索引名稱沿用舊表名：`article_likes_pkey`（V15 改表名時未改） |
| user_uuid | UUID | NOT NULL | V26 新增（取代已 DROP 的 `user_id`，由 `users.uuid` 回填）。**無 FK** |
| article_uuid | UUID | NOT NULL | V26 新增（取代已 DROP 的 `article_id`，由 `articles.uuid` 回填）。**無 FK** |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_user_article_likes_user_article` UNIQUE (user_uuid, article_uuid)（V26 於 uuid 欄位上以同名重建；`INSERT ... ON CONFLICT (user_uuid, article_uuid) DO NOTHING` 依賴它）

**Indexes:**
- `article_likes_pkey`（auto）on id
- `uq_user_article_likes_user_article`（auto, UNIQUE constraint）on (user_uuid, article_uuid)
- `idx_user_article_likes_article_uuid` on (article_uuid)（V26 新增；文章刪除連動與每日孤兒校正以 `article_uuid` 查詢，UNIQUE 以 `user_uuid` 開頭無法支援）

**Foreign keys:** 無（V26 已移除 `article_id` → `articles(id)` ON DELETE CASCADE 與 `user_id` → `users(id)`）。文章刪除 → 按讚刪除改由 `ReadingArticleDeletedConsumer`（事件）＋ `ArticleLikeOrphanReconciliationJob`（每日校正）負責。

---

### comments

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | |
| article_uuid | UUID | NOT NULL | V24 新增並由 article_id 回填；V25 起為文章的唯一識別（舊 `article_id` 已 DROP）。**無 FK**（跨模組不得有 FK），文章刪除由 `CommentArticleDeletedConsumer` 事件＋`CommentOrphanReconciliationJob` 每日校正清除 |
| parent_id | BIGINT | REFERENCES comments(id) ON DELETE CASCADE | NULL = 頂層；2 層上限由 service 強制 |
| user_uuid | UUID | NOT NULL | V25 新增（取代已 DROP 的 `user_id`，由 `users.uuid` 回填）。**無 FK**；user 為軟刪除，不連帶 |
| content | TEXT | NOT NULL | 原始 Markdown |
| content_html | TEXT | NOT NULL DEFAULT '' | V13 新增；Sanitized HTML |
| like_count | INTEGER | NOT NULL DEFAULT 0 | V13 新增；反正規化計數 |
| edited_at | TIMESTAMP | NULL | V13 新增；5 分鐘窗外不可編輯（service 規則） |
| deleted_at | TIMESTAMP | NULL | V13 新增；軟刪除時間戳 |
| deleted_by_role | VARCHAR(20) | NULL | V13 新增；AUTHOR / ADMIN / NULL |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

> V13 移除：`status VARCHAR(20) NOT NULL DEFAULT 'VISIBLE'`（改用 deleted_at 軟刪除模式）

**Indexes:**
- `comments_pkey`（auto）on id
- `comments_uuid_key`（auto, UNIQUE）on uuid
- `idx_comments_article_uuid_top_level`（V24, partial）on (article_uuid, created_at DESC) WHERE parent_id IS NULL——列出文章頂層留言
- `idx_comments_article_uuid`（V24）on (article_uuid)——涵蓋含回覆的所有列，供 `countByArticle` 與文章刪除連動（`deleteByArticleUuid`）
- `idx_comments_replies`（V13, partial）on (parent_id, created_at) WHERE parent_id IS NOT NULL
- `idx_comments_user_created`（V13；V25 以 user_uuid 重建，名稱不變）on (user_uuid, created_at DESC)

> V25 移除：`idx_comments_article_top_level`（隨 `article_id` 欄位 DROP；不重建，由 V24 的 `idx_comments_article_uuid_top_level` 取代）

**Foreign keys:**
- `parent_id` → `comments(id)` ON DELETE CASCADE（自我參照；模組內部關聯）
- 沒有任何指向其他模組（users、articles）的 FK——V25 已移除 `article_id` → `articles(id)`、`comments_article_uuid_fkey`（V24）、`user_id` → `users(id)`

---

### comment_likes

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_uuid | UUID | NOT NULL | V25 新增（取代已 DROP 的 `user_id`，由 `users.uuid` 回填）。**無 FK** |
| comment_id | BIGINT | NOT NULL REFERENCES comments(id) ON DELETE CASCADE | 模組內部關聯 |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_comment_likes_user_comment` UNIQUE (user_uuid, comment_id)（V25 於 user_uuid 上以同名重建；`INSERT ... ON CONFLICT (user_uuid, comment_id) DO NOTHING` 依賴它）

**Indexes:**
- `comment_likes_pkey`（auto）on id
- `uq_comment_likes_user_comment`（auto, UNIQUE constraint）on (user_uuid, comment_id)

**Foreign keys:**
- `comment_id` → `comments(id)` ON DELETE CASCADE（模組內部關聯）
- 沒有指向其他模組的 FK（V25 已移除 `user_id` → `users(id)`）

---

### files

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | |
| uploader_id | BIGINT | NOT NULL REFERENCES users(id) | |
| original_name | VARCHAR(255) | NOT NULL | |
| storage_key | VARCHAR(512) | NOT NULL UNIQUE | MinIO object key |
| content_type | VARCHAR(100) | NOT NULL | |
| size_bytes | BIGINT | NOT NULL | |
| category | VARCHAR(20) | NOT NULL | AVATAR / ARTICLE_COVER / ATTACHMENT 等 |
| reference_id | BIGINT | | 參照的業務實體 ID |
| reference_type | VARCHAR(50) | | 參照的業務實體類型 |
| metadata | JSONB | | 額外 metadata |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `files_pkey`（auto）on id
- `files_uuid_key`（auto, UNIQUE）on uuid
- `files_storage_key_key`（auto, UNIQUE）on storage_key
- `idx_files_uploader`（V1）on uploader_id
- `idx_files_reference`（V1）on (reference_type, reference_id)

**Foreign keys:**
- `uploader_id` → `users(id)`（NO ACTION）

---

### file_metadata

> V8 新增。與 `files` 表平行存在，用於儲存影像尺寸等 metadata（file module 使用 UUID PK）。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | UUID | PRIMARY KEY | |
| original_name | VARCHAR(255) | NOT NULL | |
| storage_path | VARCHAR(512) | NOT NULL | |
| content_type | VARCHAR(100) | NOT NULL | |
| size | BIGINT | NOT NULL | 位元組 |
| width | INTEGER | | 影像寬度（px）；非影像則 NULL |
| height | INTEGER | | 影像高度（px）；非影像則 NULL |
| usage_type | VARCHAR(50) | NOT NULL | AVATAR / COVER / ATTACHMENT 等 |
| has_thumbnail | BOOLEAN | NOT NULL DEFAULT FALSE | |
| uploader_id | UUID | NOT NULL | 對應 users.uuid |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| article_uuid | UUID | NULL | V20 新增。對應 articles.uuid；NULL = 未綁定（私有，僅上傳者與 ADMIN 可讀，fail-safe）；刻意不設 FK（跨模組邊界，與 cover_image_url 用字串的既有取捨一致） |

**Indexes:**
- `file_metadata_pkey`（auto）on id
- `idx_file_metadata_uploader_id`（V8）on uploader_id
- `idx_file_metadata_article_uuid`（V20）on article_uuid

**Foreign keys:** 無（uploader_id 為 UUID，邏輯上對應 users.uuid，但無 FK constraint；article_uuid 同理，邏輯上對應 articles.uuid，亦無 FK constraint）

---

### user_bookmarks

> V14 新增。使用者收藏文章（純 flag，user × article 1:1）。V26 起使用者與文章一律以 UUID 識別，不再有 Long 欄位與 FK。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_uuid | UUID | NOT NULL | V26 新增（取代已 DROP 的 `user_id`，由 `users.uuid` 回填）。**無 FK** |
| article_uuid | UUID | NOT NULL | V26 新增（取代已 DROP 的 `article_id`，由 `articles.uuid` 回填）。**無 FK** |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_user_bookmarks_user_article` UNIQUE (user_uuid, article_uuid)（V26 於 uuid 欄位上以同名重建；`INSERT ... ON CONFLICT (user_uuid, article_uuid) DO NOTHING` 依賴它）

**Indexes:**
- `user_bookmarks_pkey`（auto）on id
- `uq_user_bookmarks_user_article`（UNIQUE constraint）on (user_uuid, article_uuid)
- `idx_user_bookmarks_article_uuid` on (article_uuid)（V26 新增；文章刪除連動與每日孤兒校正以 `article_uuid` 查詢）

**Foreign keys:** 無（V26 已移除 `user_id` → `users(id)` 與 `article_id` → `articles(id)` ON DELETE CASCADE）。文章刪除 → 收藏刪除改由 `ReadingArticleDeletedConsumer`（事件）＋ `BookmarkOrphanReconciliationJob`（每日校正）負責。

---

### user_highlights

> V14 新增。使用者對文章段落劃線，附私人顏色標記與 note。V27 起使用者與文章一律以 UUID 識別，不再有 Long 欄位與 FK。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 對外暴露的 ID |
| user_uuid | UUID | NOT NULL | V27 新增（取代已 DROP 的 `user_id`，由 `users.uuid` 回填）。**無 FK** |
| article_uuid | UUID | NOT NULL | V27 新增（取代已 DROP 的 `article_id`，由 `articles.uuid` 回填）。**無 FK** |
| snippet | TEXT | NOT NULL | 被劃線的原文片段 |
| prefix | VARCHAR(64) | NOT NULL DEFAULT '' | 劃線前文（定位用） |
| suffix | VARCHAR(64) | NOT NULL DEFAULT '' | 劃線後文（定位用） |
| color | VARCHAR(7) | NOT NULL DEFAULT '#FFEB3B' | hex 色碼（6位）；CHECK constraint 驗證格式 |
| note | TEXT | NULL | 私人備註 |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `chk_user_highlights_color`: `color ~ '^#[0-9A-Fa-f]{6}$'`

**Indexes:**
- `user_highlights_pkey`（auto）on id
- `user_highlights_uuid_key`（auto, UNIQUE）on uuid
- `idx_user_highlights_article_for_user`（V14；V27 以 uuid 重建，名稱不變）on (user_uuid, article_uuid)
- `idx_user_highlights_user_recent`（V14；V27 以 uuid 重建，名稱不變）on (user_uuid, created_at DESC)
- `idx_user_highlights_article_uuid` on (article_uuid)（V27 新增；文章刪除連動與每日孤兒校正以 `article_uuid` 查詢，上列索引都以 `user_uuid` 開頭無法支援）

**Foreign keys:** 無（V27 已移除 `user_id` → `users(id)` 與 `article_id` → `articles(id)` ON DELETE CASCADE）。文章刪除 → 劃線刪除改由 `ReadingArticleDeletedConsumer`（事件）＋ `HighlightOrphanReconciliationJob`（每日校正）負責。

---

### user_reading_progress

> V14 新增。使用者對文章的閱讀進度（0.000–1.000 NUMERIC）。V27 起使用者與文章一律以 UUID 識別，不再有 Long 欄位與 FK。Redis 為主要儲存（key `reading:progress:{userUuid}:{articleUuid}`），DB 為 `ReadingProgressFlushJob` 的備份。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_uuid | UUID | NOT NULL | V27 新增（取代已 DROP 的 `user_id`，由 `users.uuid` 回填）。**無 FK** |
| article_uuid | UUID | NOT NULL | V27 新增（取代已 DROP 的 `article_id`，由 `articles.uuid` 回填）。**無 FK** |
| progress | NUMERIC(4,3) | NOT NULL | 0.000–1.000；CHECK constraint 驗證範圍 |
| last_heading_anchor | VARCHAR(255) | NULL | 最後停留的標題 anchor（如 `#section-2`） |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | 值為**使用者送出該進度的 JVM 時間**（非 flush 寫入 DB 的 DB 時鐘），作為單調寫入的版本：`ReadingProgressMapper.upsert` 以 `ON CONFLICT ... DO UPDATE ... WHERE updated_at <= EXCLUDED.updated_at` 只接受不比現有舊的寫入。**JVM 與 DB 需同時區（建議 UTC）**，欄位為 `timestamp without time zone`，不做時區轉換 |

**Constraints:**
- `uq_user_reading_progress_user_article`: UNIQUE (user_uuid, article_uuid)（V27 於 uuid 欄位上以同名重建；`INSERT ... ON CONFLICT (user_uuid, article_uuid) DO UPDATE` 依賴它）
- `chk_user_reading_progress_range`: `progress >= 0 AND progress <= 1`

**Indexes:**
- `user_reading_progress_pkey`（auto）on id
- `uq_user_reading_progress_user_article`（UNIQUE constraint）on (user_uuid, article_uuid)
- `idx_user_reading_progress_user_recent`（V14；V27 以 uuid 重建，名稱不變）on (user_uuid, updated_at DESC)
- `idx_user_reading_progress_article_uuid` on (article_uuid)（V27 新增；文章刪除連動與每日孤兒校正以 `article_uuid` 查詢）

**Foreign keys:** 無（V27 已移除 `user_id` → `users(id)` 與 `article_id` → `articles(id)` ON DELETE CASCADE）。文章刪除 → 閱讀進度刪除改由 `ReadingArticleDeletedConsumer`（事件）＋ `ReadingProgressOrphanReconciliationJob`（每日校正）負責；Redis 端殘留的 hash 由 `ReadingProgressFlushJob` 在確認文章已不存在時清除。

---

### article_versions

> V16 新增。文章版本快照，用於 draft history / versioning 功能。V28 起文章與作者一律以 UUID 識別，不再有 Long 欄位與 FK。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 對外公開識別碼 |
| article_uuid | UUID | NOT NULL | V28 新增（取代已 DROP 的 `article_id`，由 `articles.uuid` 回填）。**無 FK** |
| author_uuid | UUID | NOT NULL | V28 新增（取代已 DROP 的 `author_id`，由 `users.uuid` 回填；快照當時文章作者）。**無 FK** |
| type | VARCHAR(20) | NOT NULL CHECK (type IN ('AUTO','MANUAL','PUBLISHED')) | 快照類型 |
| title | VARCHAR(255) | NOT NULL | 版本標題 |
| slug | VARCHAR(255) | NOT NULL | 版本 slug（快照時刻） |
| content | TEXT | NOT NULL | 版本 Markdown 內容 |
| summary | VARCHAR(500) | NULL | |
| category_id | BIGINT | NULL | 快照時的分類 ID（非 FK，允許分類被刪） |
| cover_image_url | VARCHAR(512) | NULL | |
| status | VARCHAR(20) | NOT NULL | 快照時的文章狀態 |
| tags | UUID[] | NULL | 快照時的 tag UUID 陣列（PG array） |
| note | VARCHAR(255) | NULL | 版本備註（MANUAL 類型可填） |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `article_versions_pkey`（auto）on id
- `article_versions_uuid_key`（auto, UNIQUE）on uuid
- `idx_article_versions_article_created`（V16；V28 以 uuid 重建，名稱不變）on (article_uuid, created_at DESC)
- `idx_article_versions_article_type`（V16；V28 以 uuid 重建，名稱不變）on (article_uuid, type)

> 重建的兩個索引都以 `article_uuid` 開頭，同時支援文章刪除連動與每日孤兒校正以 `article_uuid` 查詢，不另建單欄索引。

**Foreign keys:** 無（V28 已移除 `article_id` → `articles(id)` ON DELETE CASCADE 與 `author_id` → `users(id)`）。文章刪除 → 版本快照刪除改由 `VersionArticleDeletedConsumer`（事件）＋ `ArticleVersionOrphanReconciliationJob`（每日校正）負責。

---

### user_preferences

> V16 新增。使用者通用 K-V 偏好設定表（可擴展到任意模組）。V28 起使用者一律以 UUID 識別，不再有 Long 欄位與 FK。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_uuid | UUID | NOT NULL | V28 新增（取代已 DROP 的 `user_id`，由 `users.uuid` 回填）。**無 FK**；使用者只軟刪除，不需連動 |
| pref_key | VARCHAR(100) | NOT NULL | 偏好鍵（如 `draft.auto_save_interval`） |
| pref_value | TEXT | NOT NULL | 偏好值（序列化為字串） |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_user_preferences_user_key` UNIQUE (user_uuid, pref_key)（V28 於 uuid 欄位上以同名重建；`INSERT ... ON CONFLICT (user_uuid, pref_key) DO UPDATE` 依賴它）

**Indexes:**
- `user_preferences_pkey`（auto）on id
- `uq_user_preferences_user_key`（auto, UNIQUE constraint）on (user_uuid, pref_key)

**Foreign keys:** 無（V28 已移除 `user_id` → `users(id)` ON DELETE CASCADE）。使用者只做軟刪除，原 CASCADE 從未觸發，拿掉後行為不變，故不新增連動。

---

### processed_events

> V17 新增。MQ event 冪等記錄表。所有 consumer 透過 `IdempotencyService` 對 (event_id, consumer_name) UNIQUE 做 dedup，避免重送導致重複扣分。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| event_id | UUID | NOT NULL | event 的 dedup key（producer 每次 publish random gen）|
| consumer_name | VARCHAR(100) | NOT NULL | 哪個 consumer 處理過 |
| processed_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_processed_events_event_consumer` UNIQUE (event_id, consumer_name) — 一個 event 多 consumer 訂閱時各自獨立冪等

**Indexes:**
- `processed_events_pkey`（auto）
- `uq_processed_events_event_consumer`（auto, UNIQUE）
- `idx_processed_events_processed_at` on (processed_at) — 給未來 cleanup 用

**Foreign keys:** 無

---

## Migration Index

| 版本 | 描述 |
|------|------|
| **V1** | 初始 schema — `users` / `verification_tokens` / `articles` / `tags`（BIGSERIAL）/ `article_tags` / `article_likes` / `comments`（含 status）/ `files` + `idx_files_*` |
| **V2** | `articles` 效能索引 — `idx_articles_uuid`、`idx_articles_author_id`、`idx_articles_status`、`idx_articles_created_at` |
| **V3** | `users` 新增 `username VARCHAR(50) UNIQUE NOT NULL DEFAULT ''` |
| **V4** | `users` 新增 `website VARCHAR(255)`、`social_links JSONB` |
| **V5** | `articles` 新增 `content_html TEXT`（Markdown 預渲染） |
| **V6** | 新建 `categories` + `article_categories`（多對多）；`idx_article_categories_category_id` |
| **V7** | `articles` 新增 `version BIGINT NOT NULL DEFAULT 1`（樂觀鎖） |
| **V8** | 新建 `file_metadata`（UUID PK）+ `idx_file_metadata_uploader_id` |
| **V9** | 重建 `tags`（UUID PK）+ `article_tags`（UUID FK）+ 新建 `user_tag_follows` |
| **V10** | `users.social_links` 型別由 JSONB 改為 TEXT（Spring Data JDBC 相容） |
| **V11** | `articles` 新增 `reject_reason TEXT` |
| **V12** | `article_likes.article_id`、`article_likes.user_id`、`comments.article_id`、`comments.user_id` 補 NOT NULL 約束 |
| **V13** | `articles.like_count` BIGINT → INTEGER；DROP `idx_articles_uuid`（重複）；`comments` 改造（DROP status，新增 content_html / like_count / edited_at / deleted_at / deleted_by_role + 3 個索引）；`article_likes` UNIQUE 順序調整為 `uq_article_likes_user_article(user_id, article_id)`；新建 `comment_likes` |
| **V14** | 新建 `user_bookmarks`（收藏，UNIQUE user×article）；`user_highlights`（劃線 + note，hex 色號 CHECK，2個索引）；`user_reading_progress`（NUMERIC(4,3) 0–1 範圍 CHECK，1個索引）；article_id 全部 ON DELETE CASCADE |
| **V15** | 新建 `series` 表（含 article_count 反正規化欄位，`idx_series_author`）；`articles` 加 `series_id`（FK ON DELETE SET NULL）+ `series_position`（partial index `idx_articles_series_position`）；`article_likes` 改名 `user_article_likes`（含 RENAME CONSTRAINT） |
| **V16** | 新建 `article_versions` 表（type CHECK: AUTO/MANUAL/PUBLISHED；tags UUID[]；article_id FK ON DELETE CASCADE；2 個索引）；新建 `user_preferences` 表（K-V 通用；UNIQUE(user_id, pref_key)；user_id FK ON DELETE CASCADE） |
| **V17** | 新建 `processed_events` 表（MQ event 冪等記錄）|
| **V18** | `users` 新增 `location VARCHAR(100)`（nullable）+ 5 個通知偏好 `notification_comment/like/review/follow/newsletter BOOLEAN NOT NULL DEFAULT TRUE`（設定頁面後端持久化） |
| **V19** | `articles` 新增 `toc TEXT`（nullable，無 DEFAULT；章節導覽 JSON，由渲染器於 create/update 重算） |
| **V20** | `file_metadata` 新增 `article_uuid UUID`（nullable，未綁定=私有）+ `idx_file_metadata_article_uuid`；刻意不設 FK（跨模組邊界，檔案存取控制地基） |
| **V21** | 資料遷移（無 schema 變更）：把 `articles.content_md` / `content_html` 內殘留的 MinIO 絕對網址改寫為 `/api/v1/files/{id}/content`，並依「上傳者==作者」回填 `file_metadata.article_uuid`；解決 V20 之前寫入的內容繞過 `canRead` 授權（bucket 私有時破圖、公開時等同無授權）的問題。冪等 |
| **V22** | `articles` 新增 partial index `idx_articles_published_latest` on (published_at DESC NULLS LAST, id DESC) WHERE status = 'PUBLISHED'（公開列表改伺服器端排序的預設排序；兼供 PERF-08 的 `findPublishedAfter` 範圍掃描）；刻意不為 view_count / comment_count 建排序索引 |
| **V23** | 資料遷移＋約束：正規化 `categories.slug`（轉小寫、非 [a-z0-9] 連續字元→`-`、去頭尾 `-`；空→`category-{id}`；撞名附加 `-{id}`；≤ 60 字，逐筆 RAISE NOTICE 記錄），**會改變不合格分類的網址且不留轉址**（Yuan 決定）；新增 CHECK `ck_categories_slug_format` |
| **V24** | ARCH-30 第 2 段 P1（Expand）：`comments` 新增 `article_uuid UUID`，由 `article_id` 回填後 `SET NOT NULL`；新 FK `comments_article_uuid_fkey` → `articles(uuid)` ON DELETE CASCADE；新 partial index `idx_comments_article_uuid_top_level`，以及涵蓋所有列的 `idx_comments_article_uuid`（FK CASCADE／含回覆計數）。舊 `article_id` 欄位／FK／索引保留（P4 移除） |
| **V25** | 跨模組識別全面改 UUID（comment 模組）：`comments` 新增 `user_uuid UUID`、`comment_likes` 新增 `user_uuid UUID`，皆由 `users.uuid` 回填後 `SET NOT NULL`（找不到使用者時 `RAISE EXCEPTION` 中止、不刪資料）；DROP `comments_article_uuid_fkey`（V24）；DROP 欄位 `comments.article_id`、`comments.user_id`、`comment_likes.user_id`（連帶移除各自的 FK、`idx_comments_article_top_level`、`idx_comments_user_created`、舊 `uq_comment_likes_user_comment`）；以 `user_uuid` 重建 `idx_comments_user_created` (user_uuid, created_at DESC) 與 `uq_comment_likes_user_comment` UNIQUE (user_uuid, comment_id)。新 uuid 欄位不建 FK；`parent_id`、`comment_id` 模組內部關聯不動 |
| **V26** | 跨模組識別全面改 UUID（reading 模組：按讚與收藏）：`user_article_likes`、`user_bookmarks` 各新增 `user_uuid UUID`、`article_uuid UUID`，分別由 `users.uuid`、`articles.uuid` 回填後 `SET NOT NULL`（找不到使用者或文章時 `RAISE EXCEPTION` 中止、不刪資料）；DROP 欄位 `user_id`、`article_id`（連帶移除各自的 FK——含 `article_id` → `articles(id)` ON DELETE CASCADE——與舊 UNIQUE）；以 uuid 欄位同名重建 `uq_user_article_likes_user_article`、`uq_user_bookmarks_user_article` UNIQUE (user_uuid, article_uuid)；新增 `idx_user_article_likes_article_uuid`、`idx_user_bookmarks_article_uuid`（article_uuid 單欄，供刪除連動與校正）。新 uuid 欄位不建 FK；`user_highlights`、`user_reading_progress` 不在本次遷移內（M2-c） |
| **V27** | 跨模組識別全面改 UUID（reading 模組：劃線與閱讀進度）：`user_highlights`、`user_reading_progress` 各新增 `user_uuid UUID`、`article_uuid UUID`，分別由 `users.uuid`、`articles.uuid` 回填後 `SET NOT NULL`（找不到使用者或文章時 `RAISE EXCEPTION` 中止、不刪資料）；DROP 欄位 `user_id`、`article_id`（連帶移除各自的 FK——含 `article_id` → `articles(id)` ON DELETE CASCADE——與含該欄位的索引／舊 UNIQUE）；以 uuid 欄位同名重建 `idx_user_highlights_article_for_user` (user_uuid, article_uuid)、`idx_user_highlights_user_recent` (user_uuid, created_at DESC)、`uq_user_reading_progress_user_article` UNIQUE (user_uuid, article_uuid)、`idx_user_reading_progress_user_recent` (user_uuid, updated_at DESC)；新增 `idx_user_highlights_article_uuid`、`idx_user_reading_progress_article_uuid`（article_uuid 單欄，供刪除連動與校正）。新 uuid 欄位不建 FK。至此 reading 模組四張表都不再有跨模組 Long 欄位與 FK |
| **V28** | 跨模組識別全面改 UUID（version 模組）：`article_versions` 新增 `article_uuid UUID`、`author_uuid UUID`，`user_preferences` 新增 `user_uuid UUID`，分別由 `articles.uuid`、`users.uuid` 回填後 `SET NOT NULL`（找不到文章、作者或使用者時 `RAISE EXCEPTION` 中止、不刪資料）；DROP 欄位 `article_versions.article_id`／`author_id`、`user_preferences.user_id`（連帶移除各自的 FK——含 `article_id` → `articles(id)` ON DELETE CASCADE 與 `user_id` → `users(id)` ON DELETE CASCADE——與含該欄位的索引／舊 UNIQUE）；以 uuid 欄位同名重建 `idx_article_versions_article_created` (article_uuid, created_at DESC)、`idx_article_versions_article_type` (article_uuid, type)、`uq_user_preferences_user_key` UNIQUE (user_uuid, pref_key)。新 uuid 欄位不建 FK；`article_versions.category_id`（快照分類、非 FK）不動。文章刪除 → 版本快照刪除改由事件＋每日校正負責；`user_preferences` 不新增連動 |
| **V29** | 跨模組識別全面改 UUID（series 模組）：`series` 新增 `author_uuid UUID`，由 `users.uuid` 回填後 `SET NOT NULL`（找不到作者時 `RAISE EXCEPTION` 中止、不刪資料）；DROP 欄位 `author_id`（連帶移除 `series_author_id_fkey` → `users(id)` 與 `idx_series_author` (author_id)）；以同名在 `author_uuid` 上重建 `idx_series_author`。新 uuid 欄位不建 FK；`articles.series_id` → `series(id)` ON DELETE SET NULL 是 article 模組的 FK，到 M6 才動。使用者只做軟刪除，不新增連動 |

---

## Field Conventions

| Convention | 說明 |
|------------|------|
| `uuid UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4()` | 所有對外可見的業務實體均有 uuid；API 層只暴露 uuid，不暴露 BIGSERIAL id |
| `created_at / updated_at TIMESTAMP` | 所有業務 entity；Spring Data JDBC 用 `@CreatedDate` / `@LastModifiedDate` |
| `deleted_at TIMESTAMP NULL` | 軟刪除模式（comments；users 用 status 欄位替代） |
| `deleted_by_role VARCHAR(20) NULL` | 記錄刪除者角色（AUTHOR / ADMIN），僅 comments 使用 |
| 反正規化計數 | `articles.{view_count, like_count, comment_count}`、`comments.like_count`、`tags.usage_count`；由 service 層原子 UPDATE 維護 |
| Naming: UNIQUE constraint | 明確命名 `uq_{table}_{purpose}`（例 `uq_article_likes_user_article`）；避免靠 PG 自動命名 |
| Naming: B-tree index | `idx_{table}_{purpose}`（例 `idx_comments_article_uuid_top_level`）；複合索引用語意名而非欄位串接 |
