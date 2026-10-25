-- 跨模組識別全面改為 UUID：reading 模組的按讚與收藏（設計 docs/plans/2026-10-24-cross-module-uuid-design.md §4、§5 第 2 列）
--
-- 目標：user_article_likes／user_bookmarks 不再有指向其他模組（users、articles）的 Long 欄位與 FK。
--   user_article_likes.user_id / article_id → user_uuid / article_uuid
--   user_bookmarks.user_id / article_id     → user_uuid / article_uuid
-- 不做雙寫（無正式環境，Yuan 2026-10-24 確認），故以單一 migration 完成 expand 與 contract。
--
-- 刪除連動：移除 article_id → articles(id) ON DELETE CASCADE 之後，「文章刪除 → 按讚／收藏刪除」改由
-- ReadingArticleDeletedConsumer（事件）＋兩個 OrphanReconciliationJob（每日校正）負責。
--
-- 範圍邊界：user_highlights、user_reading_progress 不在本次遷移內（M2-c 處理），本檔不碰它們。
-- 新的 uuid 欄位一律不建 FK（跨模組不得有資料庫 FK）。
--
-- 舊欄位 DROP 時 PostgreSQL 會連帶移除：各自的 FK、舊 UNIQUE (user_id, article_id)。
-- UNIQUE 在 (user_uuid, article_uuid) 上以同名重建（INSERT ... ON CONFLICT (user_uuid, article_uuid) 依賴它）。
-- 另建 article_uuid 單欄索引：刪除連動與校正都以 article_uuid 查詢，而 UNIQUE 以 user_uuid 開頭，無法支援。

-- ───────────────────────── user_article_likes ─────────────────────────

ALTER TABLE user_article_likes ADD COLUMN user_uuid UUID;
ALTER TABLE user_article_likes ADD COLUMN article_uuid UUID;

UPDATE user_article_likes l
SET user_uuid = u.uuid
FROM users u
WHERE u.id = l.user_id;

UPDATE user_article_likes l
SET article_uuid = a.uuid
FROM articles a
WHERE a.id = l.article_id;

-- 孤兒保護：user_id／article_id 為 NOT NULL 且（至 V25 為止）有 FK，理論上每列都回填得到。
-- 仍然檢查：找不到使用者或文章的按讚一律中止遷移（整個 migration 在同一交易內回滾），絕不刪除資料。
DO $$
DECLARE
    orphan_users    BIGINT;
    orphan_articles BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_users FROM user_article_likes WHERE user_uuid IS NULL;
    SELECT COUNT(*) INTO orphan_articles FROM user_article_likes WHERE article_uuid IS NULL;
    RAISE NOTICE 'V26: user_article_likes 找不到對應使用者的筆數 = %、找不到對應文章的筆數 = %', orphan_users, orphan_articles;
    IF orphan_users > 0 THEN
        RAISE EXCEPTION 'V26 中止：user_article_likes 有 % 筆找不到對應使用者（user_id 無對應的 users 列），請先人工處理後再遷移', orphan_users;
    END IF;
    IF orphan_articles > 0 THEN
        RAISE EXCEPTION 'V26 中止：user_article_likes 有 % 筆找不到對應文章（article_id 無對應的 articles 列），請先人工處理後再遷移', orphan_articles;
    END IF;
END $$;

ALTER TABLE user_article_likes ALTER COLUMN user_uuid SET NOT NULL;
ALTER TABLE user_article_likes ALTER COLUMN article_uuid SET NOT NULL;

ALTER TABLE user_article_likes DROP COLUMN user_id;
ALTER TABLE user_article_likes DROP COLUMN article_id;

ALTER TABLE user_article_likes
    ADD CONSTRAINT uq_user_article_likes_user_article UNIQUE (user_uuid, article_uuid);

CREATE INDEX idx_user_article_likes_article_uuid
    ON user_article_likes (article_uuid);

-- ───────────────────────── user_bookmarks ─────────────────────────

ALTER TABLE user_bookmarks ADD COLUMN user_uuid UUID;
ALTER TABLE user_bookmarks ADD COLUMN article_uuid UUID;

UPDATE user_bookmarks b
SET user_uuid = u.uuid
FROM users u
WHERE u.id = b.user_id;

UPDATE user_bookmarks b
SET article_uuid = a.uuid
FROM articles a
WHERE a.id = b.article_id;

DO $$
DECLARE
    orphan_users    BIGINT;
    orphan_articles BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_users FROM user_bookmarks WHERE user_uuid IS NULL;
    SELECT COUNT(*) INTO orphan_articles FROM user_bookmarks WHERE article_uuid IS NULL;
    RAISE NOTICE 'V26: user_bookmarks 找不到對應使用者的筆數 = %、找不到對應文章的筆數 = %', orphan_users, orphan_articles;
    IF orphan_users > 0 THEN
        RAISE EXCEPTION 'V26 中止：user_bookmarks 有 % 筆找不到對應使用者（user_id 無對應的 users 列），請先人工處理後再遷移', orphan_users;
    END IF;
    IF orphan_articles > 0 THEN
        RAISE EXCEPTION 'V26 中止：user_bookmarks 有 % 筆找不到對應文章（article_id 無對應的 articles 列），請先人工處理後再遷移', orphan_articles;
    END IF;
END $$;

ALTER TABLE user_bookmarks ALTER COLUMN user_uuid SET NOT NULL;
ALTER TABLE user_bookmarks ALTER COLUMN article_uuid SET NOT NULL;

ALTER TABLE user_bookmarks DROP COLUMN user_id;
ALTER TABLE user_bookmarks DROP COLUMN article_id;

ALTER TABLE user_bookmarks
    ADD CONSTRAINT uq_user_bookmarks_user_article UNIQUE (user_uuid, article_uuid);

CREATE INDEX idx_user_bookmarks_article_uuid
    ON user_bookmarks (article_uuid);
