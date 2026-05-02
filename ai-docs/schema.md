# Database Schema（PostgreSQL）

> **真相來源**：本文件描述套用所有 migrations V1–V16 後的當前 DB schema。
> **維護規則**：每次新增 Flyway migration 都必須同步更新此文件（詳見 CLAUDE.md §Schema Maintenance）。
> 最後更新版本：**V16**

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
| slug | VARCHAR(60) | NOT NULL UNIQUE | |
| description | VARCHAR(200) | | |
| sort_order | INT | NOT NULL DEFAULT 0 | 排序用 |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `categories_pkey`（auto）on id
- `categories_uuid_key`（auto, UNIQUE）on uuid
- `categories_name_key`（auto, UNIQUE）on name
- `categories_slug_key`（auto, UNIQUE）on slug

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

> V15 新增。文章系列（連載），一個作者可建立多個系列。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 對外公開識別碼 |
| title | VARCHAR(255) | NOT NULL | 系列標題 |
| slug | VARCHAR(255) | NOT NULL UNIQUE | SEO-friendly URL 段落 |
| description | TEXT | NULL | 系列描述 |
| cover_image_url | VARCHAR(512) | NULL | 封面圖 URL |
| author_id | BIGINT | NOT NULL REFERENCES users(id) | |
| article_count | INTEGER | NOT NULL DEFAULT 0 | 反正規化計數；由 service 維護 |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `series_pkey`（auto）on id
- `series_uuid_key`（auto, UNIQUE）on uuid
- `series_slug_key`（auto, UNIQUE）on slug
- `idx_series_author`（V15）on author_id

**Foreign keys:**
- `author_id` → `users(id)`（NO ACTION）

---

### user_article_likes

> V15 由 `article_likes` 改名（命名一致化）。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | V12 補 NOT NULL |
| user_id | BIGINT | NOT NULL REFERENCES users(id) | V12 補 NOT NULL |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_user_article_likes_user_article` UNIQUE (user_id, article_id)（V15 由 `uq_article_likes_user_article` 改名）

**Indexes:**
- `user_article_likes_pkey`（auto）on id
- `uq_user_article_likes_user_article`（auto, UNIQUE constraint）on (user_id, article_id)

**Foreign keys:**
- `article_id` → `articles(id)` ON DELETE CASCADE
- `user_id` → `users(id)`（NO ACTION）

---

### comments

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | |
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | V12 補 NOT NULL |
| parent_id | BIGINT | REFERENCES comments(id) ON DELETE CASCADE | NULL = 頂層；2 層上限由 service 強制 |
| user_id | BIGINT | NOT NULL REFERENCES users(id) | V12 補 NOT NULL；user 軟刪除不 cascade |
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
- `idx_comments_article_top_level`（V13, partial）on (article_id, created_at DESC) WHERE parent_id IS NULL
- `idx_comments_replies`（V13, partial）on (parent_id, created_at) WHERE parent_id IS NOT NULL
- `idx_comments_user_created`（V13）on (user_id, created_at DESC)

**Foreign keys:**
- `article_id` → `articles(id)` ON DELETE CASCADE
- `parent_id` → `comments(id)` ON DELETE CASCADE（自我參照）
- `user_id` → `users(id)`（NO ACTION，user 軟刪除不連帶）

---

### comment_likes

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_id | BIGINT | NOT NULL REFERENCES users(id) | |
| comment_id | BIGINT | NOT NULL REFERENCES comments(id) ON DELETE CASCADE | |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_comment_likes_user_comment` UNIQUE (user_id, comment_id)

**Indexes:**
- `comment_likes_pkey`（auto）on id
- `uq_comment_likes_user_comment`（auto, UNIQUE constraint）on (user_id, comment_id)

**Foreign keys:**
- `user_id` → `users(id)`（NO ACTION）
- `comment_id` → `comments(id)` ON DELETE CASCADE

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

**Indexes:**
- `file_metadata_pkey`（auto）on id
- `idx_file_metadata_uploader_id`（V8）on uploader_id

**Foreign keys:** 無（uploader_id 為 UUID，邏輯上對應 users.uuid，但無 FK constraint）

---

### user_bookmarks

> V14 新增。使用者收藏文章（純 flag，user × article 1:1）。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_id | BIGINT | NOT NULL REFERENCES users(id) | |
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Indexes:**
- `user_bookmarks_pkey`（auto）on id
- `uq_user_bookmarks_user_article`（UNIQUE constraint）on (user_id, article_id)

**Foreign keys:**
- `user_id` → `users(id)`（NO ACTION）
- `article_id` → `articles(id)`（ON DELETE CASCADE）

---

### user_highlights

> V14 新增。使用者對文章段落劃線，附私人顏色標記與 note。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 對外暴露的 ID |
| user_id | BIGINT | NOT NULL REFERENCES users(id) | |
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | |
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
- `idx_user_highlights_article_for_user`（V14）on (user_id, article_id)
- `idx_user_highlights_user_recent`（V14）on (user_id, created_at DESC)

**Foreign keys:**
- `user_id` → `users(id)`（NO ACTION）
- `article_id` → `articles(id)`（ON DELETE CASCADE）

---

### user_reading_progress

> V14 新增。使用者對文章的閱讀進度（0.000–1.000 NUMERIC）。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_id | BIGINT | NOT NULL REFERENCES users(id) | |
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | |
| progress | NUMERIC(4,3) | NOT NULL | 0.000–1.000；CHECK constraint 驗證範圍 |
| last_heading_anchor | VARCHAR(255) | NULL | 最後停留的標題 anchor（如 `#section-2`） |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_user_reading_progress_user_article`: UNIQUE (user_id, article_id)
- `chk_user_reading_progress_range`: `progress >= 0 AND progress <= 1`

**Indexes:**
- `user_reading_progress_pkey`（auto）on id
- `uq_user_reading_progress_user_article`（UNIQUE constraint）on (user_id, article_id)
- `idx_user_reading_progress_user_recent`（V14）on (user_id, updated_at DESC)

**Foreign keys:**
- `user_id` → `users(id)`（NO ACTION）
- `article_id` → `articles(id)`（ON DELETE CASCADE）

---

### article_versions

> V16 新增。文章版本快照，用於 draft history / versioning 功能。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| uuid | UUID | NOT NULL UNIQUE DEFAULT uuid_generate_v4() | 對外公開識別碼 |
| article_id | BIGINT | NOT NULL REFERENCES articles(id) ON DELETE CASCADE | |
| author_id | BIGINT | NOT NULL REFERENCES users(id) | |
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
- `idx_article_versions_article_created`（V16）on (article_id, created_at DESC)
- `idx_article_versions_article_type`（V16）on (article_id, type)

**Foreign keys:**
- `article_id` → `articles(id)` ON DELETE CASCADE
- `author_id` → `users(id)`（NO ACTION）

---

### user_preferences

> V16 新增。使用者通用 K-V 偏好設定表（可擴展到任意模組）。

| Column | Type | Constraints | Notes |
|--------|------|-------------|-------|
| id | BIGSERIAL | PRIMARY KEY | |
| user_id | BIGINT | NOT NULL REFERENCES users(id) ON DELETE CASCADE | |
| pref_key | VARCHAR(100) | NOT NULL | 偏好鍵（如 `draft.auto_save_interval`） |
| pref_value | TEXT | NOT NULL | 偏好值（序列化為字串） |
| created_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |
| updated_at | TIMESTAMP | NOT NULL DEFAULT CURRENT_TIMESTAMP | |

**Constraints:**
- `uq_user_preferences_user_key` UNIQUE (user_id, pref_key)

**Indexes:**
- `user_preferences_pkey`（auto）on id
- `uq_user_preferences_user_key`（auto, UNIQUE constraint）on (user_id, pref_key)

**Foreign keys:**
- `user_id` → `users(id)` ON DELETE CASCADE

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
| Naming: B-tree index | `idx_{table}_{purpose}`（例 `idx_comments_article_top_level`）；複合索引用語意名而非欄位串接 |
