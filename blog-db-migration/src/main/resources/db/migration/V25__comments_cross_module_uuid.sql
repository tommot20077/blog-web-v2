-- 跨模組識別全面改為 UUID：comment 模組（設計 docs/plans/2026-10-24-cross-module-uuid-design.md §4、§5 第 1 列）
--
-- 目標：comments／comment_likes 不再有指向其他模組（users、articles）的 Long 欄位與 FK。
--   comments.article_id        → DROP（V24 已加 article_uuid 並回填，改以 article_uuid 為唯一識別）
--   comments.user_id           → user_uuid
--   comment_likes.user_id      → user_uuid
-- 不做雙寫（無正式環境，Yuan 2026-10-24 確認），故以單一 migration 完成 expand 與 contract。
--
-- 刪除連動：移除 comments_article_uuid_fkey 之後，「文章刪除 → 留言刪除」改由
-- CommentArticleDeletedConsumer（事件）＋CommentOrphanReconciliationJob（每日校正）負責。
--
-- 模組內部關聯不動：comments.parent_id → comments(id)、comment_likes.comment_id → comments(id) ON DELETE CASCADE。
-- 新的 uuid 欄位一律不建 FK（跨模組不得有資料庫 FK）。

-- ───────────────────────── comments ─────────────────────────

ALTER TABLE comments ADD COLUMN user_uuid UUID;

UPDATE comments c
SET user_uuid = u.uuid
FROM users u
WHERE u.id = c.user_id;

-- 孤兒保護：user_id 為 NOT NULL 且（至 V24 為止）有 FK → users(id)，理論上每列都回填得到。
-- 仍然檢查：找不到使用者的留言一律中止遷移（整個 migration 在同一交易內回滾），絕不刪除留言。
DO $$
DECLARE
    orphan_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_count FROM comments WHERE user_uuid IS NULL;
    RAISE NOTICE 'V25: comments 找不到對應使用者的筆數 = %', orphan_count;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION 'V25 中止：comments 有 % 筆找不到對應使用者（user_id 無對應的 users 列），請先人工處理後再遷移', orphan_count;
    END IF;
END $$;

ALTER TABLE comments ALTER COLUMN user_uuid SET NOT NULL;

-- 先明確移除 V24 的 FK（comments.article_uuid → articles(uuid)），再移除舊 Long 欄位。
-- DROP COLUMN 會連帶移除：article_id → articles(id) 的 FK、idx_comments_article_top_level（不重建：
-- V24 的 idx_comments_article_uuid_top_level 已是等價索引）、user_id → users(id) 的 FK、
-- idx_comments_user_created（下面以 user_uuid 重建，名稱不變）。
ALTER TABLE comments DROP CONSTRAINT comments_article_uuid_fkey;
ALTER TABLE comments DROP COLUMN article_id;
ALTER TABLE comments DROP COLUMN user_id;

CREATE INDEX idx_comments_user_created
    ON comments (user_uuid, created_at DESC);

-- ───────────────────────── comment_likes ─────────────────────────

ALTER TABLE comment_likes ADD COLUMN user_uuid UUID;

UPDATE comment_likes cl
SET user_uuid = u.uuid
FROM users u
WHERE u.id = cl.user_id;

DO $$
DECLARE
    orphan_count BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_count FROM comment_likes WHERE user_uuid IS NULL;
    RAISE NOTICE 'V25: comment_likes 找不到對應使用者的筆數 = %', orphan_count;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION 'V25 中止：comment_likes 有 % 筆找不到對應使用者（user_id 無對應的 users 列），請先人工處理後再遷移', orphan_count;
    END IF;
END $$;

ALTER TABLE comment_likes ALTER COLUMN user_uuid SET NOT NULL;

-- 舊 UNIQUE (user_id, comment_id) 與 user_id → users(id) 的 FK 隨欄位一併移除；
-- UNIQUE 在 user_uuid 上以同名重建（INSERT ... ON CONFLICT (user_uuid, comment_id) 依賴它）。
ALTER TABLE comment_likes DROP COLUMN user_id;

ALTER TABLE comment_likes
    ADD CONSTRAINT uq_comment_likes_user_comment UNIQUE (user_uuid, comment_id);
