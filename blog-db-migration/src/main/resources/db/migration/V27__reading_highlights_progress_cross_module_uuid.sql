-- 跨模組識別全面改為 UUID：reading 模組的劃線與閱讀進度（設計 docs/plans/2026-10-24-cross-module-uuid-design.md §4、§5 第 2 列，M2-c）
--
-- 目標：user_highlights／user_reading_progress 不再有指向其他模組（users、articles）的 Long 欄位與 FK。
--   user_highlights.user_id / article_id       → user_uuid / article_uuid
--   user_reading_progress.user_id / article_id → user_uuid / article_uuid
-- 不做雙寫（無正式環境，Yuan 2026-10-24 確認），故以單一 migration 完成 expand 與 contract。
-- 與 V26（按讚與收藏）合起來，reading 模組四張表都不再有跨模組 Long 欄位與 FK。
--
-- 刪除連動：移除 article_id → articles(id) ON DELETE CASCADE 之後，「文章刪除 → 劃線／閱讀進度刪除」改由
-- ReadingArticleDeletedConsumer（事件）＋兩個 OrphanReconciliationJob（每日校正）負責。
-- 新的 uuid 欄位一律不建 FK（跨模組不得有資料庫 FK）。
--
-- 舊欄位 DROP 時 PostgreSQL 會連帶移除：各自的 FK，以及所有含該欄位的索引與 UNIQUE：
--   idx_user_highlights_article_for_user (user_id, article_id)
--   idx_user_highlights_user_recent      (user_id, created_at DESC)
--   uq_user_reading_progress_user_article UNIQUE (user_id, article_id)
--   idx_user_reading_progress_user_recent (user_id, updated_at DESC)
-- 以上四個以同名在 uuid 欄位上重建（UNIQUE 是 INSERT ... ON CONFLICT (user_uuid, article_uuid) 的依據）。
-- 重建後沒有任何索引以 article_uuid 開頭，故另建 article_uuid 單欄索引：刪除連動與校正都以 article_uuid 查詢。

-- ───────────────────────── user_highlights ─────────────────────────

ALTER TABLE user_highlights ADD COLUMN user_uuid UUID;
ALTER TABLE user_highlights ADD COLUMN article_uuid UUID;

UPDATE user_highlights h
SET user_uuid = u.uuid
FROM users u
WHERE u.id = h.user_id;

UPDATE user_highlights h
SET article_uuid = a.uuid
FROM articles a
WHERE a.id = h.article_id;

-- 孤兒保護：user_id／article_id 為 NOT NULL 且（至 V26 為止）有 FK，理論上每列都回填得到。
-- 仍然檢查：找不到使用者或文章的劃線一律中止遷移（整個 migration 在同一交易內回滾），絕不刪除資料。
DO $$
DECLARE
    orphan_users    BIGINT;
    orphan_articles BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_users FROM user_highlights WHERE user_uuid IS NULL;
    SELECT COUNT(*) INTO orphan_articles FROM user_highlights WHERE article_uuid IS NULL;
    RAISE NOTICE 'V27: user_highlights 找不到對應使用者的筆數 = %、找不到對應文章的筆數 = %', orphan_users, orphan_articles;
    IF orphan_users > 0 THEN
        RAISE EXCEPTION 'V27 中止：user_highlights 有 % 筆找不到對應使用者（user_id 無對應的 users 列），請先人工處理後再遷移', orphan_users;
    END IF;
    IF orphan_articles > 0 THEN
        RAISE EXCEPTION 'V27 中止：user_highlights 有 % 筆找不到對應文章（article_id 無對應的 articles 列），請先人工處理後再遷移', orphan_articles;
    END IF;
END $$;

ALTER TABLE user_highlights ALTER COLUMN user_uuid SET NOT NULL;
ALTER TABLE user_highlights ALTER COLUMN article_uuid SET NOT NULL;

ALTER TABLE user_highlights DROP COLUMN user_id;
ALTER TABLE user_highlights DROP COLUMN article_id;

CREATE INDEX idx_user_highlights_article_for_user
    ON user_highlights (user_uuid, article_uuid);

CREATE INDEX idx_user_highlights_user_recent
    ON user_highlights (user_uuid, created_at DESC);

CREATE INDEX idx_user_highlights_article_uuid
    ON user_highlights (article_uuid);

-- ───────────────────────── user_reading_progress ─────────────────────────

ALTER TABLE user_reading_progress ADD COLUMN user_uuid UUID;
ALTER TABLE user_reading_progress ADD COLUMN article_uuid UUID;

UPDATE user_reading_progress p
SET user_uuid = u.uuid
FROM users u
WHERE u.id = p.user_id;

UPDATE user_reading_progress p
SET article_uuid = a.uuid
FROM articles a
WHERE a.id = p.article_id;

DO $$
DECLARE
    orphan_users    BIGINT;
    orphan_articles BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_users FROM user_reading_progress WHERE user_uuid IS NULL;
    SELECT COUNT(*) INTO orphan_articles FROM user_reading_progress WHERE article_uuid IS NULL;
    RAISE NOTICE 'V27: user_reading_progress 找不到對應使用者的筆數 = %、找不到對應文章的筆數 = %', orphan_users, orphan_articles;
    IF orphan_users > 0 THEN
        RAISE EXCEPTION 'V27 中止：user_reading_progress 有 % 筆找不到對應使用者（user_id 無對應的 users 列），請先人工處理後再遷移', orphan_users;
    END IF;
    IF orphan_articles > 0 THEN
        RAISE EXCEPTION 'V27 中止：user_reading_progress 有 % 筆找不到對應文章（article_id 無對應的 articles 列），請先人工處理後再遷移', orphan_articles;
    END IF;
END $$;

ALTER TABLE user_reading_progress ALTER COLUMN user_uuid SET NOT NULL;
ALTER TABLE user_reading_progress ALTER COLUMN article_uuid SET NOT NULL;

ALTER TABLE user_reading_progress DROP COLUMN user_id;
ALTER TABLE user_reading_progress DROP COLUMN article_id;

ALTER TABLE user_reading_progress
    ADD CONSTRAINT uq_user_reading_progress_user_article UNIQUE (user_uuid, article_uuid);

CREATE INDEX idx_user_reading_progress_user_recent
    ON user_reading_progress (user_uuid, updated_at DESC);

CREATE INDEX idx_user_reading_progress_article_uuid
    ON user_reading_progress (article_uuid);
