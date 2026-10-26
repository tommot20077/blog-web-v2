-- 跨模組識別全面改為 UUID：version 模組的版本快照與使用者偏好（設計 docs/plans/2026-10-24-cross-module-uuid-design.md §4、§5 第 3 列，M3-b）
--
-- 目標：article_versions／user_preferences 不再有指向其他模組（users、articles）的 Long 欄位與 FK。
--   article_versions.article_id / author_id → article_uuid / author_uuid
--   user_preferences.user_id               → user_uuid
-- 不做雙寫（無正式環境，Yuan 2026-10-24 確認），故以單一 migration 完成 expand 與 contract。
--
-- 刪除連動：移除 article_id → articles(id) ON DELETE CASCADE 之後，「文章刪除 → 版本快照刪除」改由
-- VersionArticleDeletedConsumer（事件）＋ ArticleVersionOrphanReconciliationJob（每日校正）負責。
-- user_preferences.user_id → users(id) ON DELETE CASCADE 的連動不新增：使用者只做軟刪除，CASCADE 從未觸發，
-- 拿掉 FK 後行為不變（使用者匿名化另案 DATA-02）。
--
-- 範圍邊界：article_versions.category_id 是「快照時的分類 id」，非 FK、不在授權清單，本檔不碰。
-- 新的 uuid 欄位一律不建 FK（跨模組不得有資料庫 FK）。
--
-- 舊欄位 DROP 時 PostgreSQL 會連帶移除：各自的 FK，以及所有含該欄位的索引與 UNIQUE：
--   idx_article_versions_article_created (article_id, created_at DESC)
--   idx_article_versions_article_type    (article_id, type)
--   uq_user_preferences_user_key UNIQUE (user_id, pref_key)
-- 以上三個以同名在 uuid 欄位上重建（UNIQUE 是 UserPreferenceMapper.upsert 的
-- INSERT ... ON CONFLICT (user_uuid, pref_key) 的依據；兩個索引同時支援刪除連動與校正以 article_uuid 查詢）。

-- ───────────────────────── article_versions ─────────────────────────

ALTER TABLE article_versions ADD COLUMN article_uuid UUID;
ALTER TABLE article_versions ADD COLUMN author_uuid UUID;

UPDATE article_versions v
SET article_uuid = a.uuid
FROM articles a
WHERE a.id = v.article_id;

UPDATE article_versions v
SET author_uuid = u.uuid
FROM users u
WHERE u.id = v.author_id;

-- 孤兒保護：article_id／author_id 為 NOT NULL 且（至 V27 為止）有 FK，理論上每列都回填得到。
-- 仍然檢查：找不到文章或作者的快照一律中止遷移（整個 migration 在同一交易內回滾），絕不刪除資料。
DO $$
DECLARE
    orphan_articles BIGINT;
    orphan_authors  BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_articles FROM article_versions WHERE article_uuid IS NULL;
    SELECT COUNT(*) INTO orphan_authors FROM article_versions WHERE author_uuid IS NULL;
    RAISE NOTICE 'V28: article_versions 找不到對應文章的筆數 = %、找不到對應作者的筆數 = %', orphan_articles, orphan_authors;
    IF orphan_articles > 0 THEN
        RAISE EXCEPTION 'V28 中止：article_versions 有 % 筆找不到對應文章（article_id 無對應的 articles 列），請先人工處理後再遷移', orphan_articles;
    END IF;
    IF orphan_authors > 0 THEN
        RAISE EXCEPTION 'V28 中止：article_versions 有 % 筆找不到對應作者（author_id 無對應的 users 列），請先人工處理後再遷移', orphan_authors;
    END IF;
END $$;

ALTER TABLE article_versions ALTER COLUMN article_uuid SET NOT NULL;
ALTER TABLE article_versions ALTER COLUMN author_uuid SET NOT NULL;

ALTER TABLE article_versions DROP COLUMN article_id;
ALTER TABLE article_versions DROP COLUMN author_id;

CREATE INDEX idx_article_versions_article_created
    ON article_versions (article_uuid, created_at DESC);

CREATE INDEX idx_article_versions_article_type
    ON article_versions (article_uuid, type);

-- ───────────────────────── user_preferences ─────────────────────────

ALTER TABLE user_preferences ADD COLUMN user_uuid UUID;

UPDATE user_preferences p
SET user_uuid = u.uuid
FROM users u
WHERE u.id = p.user_id;

DO $$
DECLARE
    orphan_users BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_users FROM user_preferences WHERE user_uuid IS NULL;
    RAISE NOTICE 'V28: user_preferences 找不到對應使用者的筆數 = %', orphan_users;
    IF orphan_users > 0 THEN
        RAISE EXCEPTION 'V28 中止：user_preferences 有 % 筆找不到對應使用者（user_id 無對應的 users 列），請先人工處理後再遷移', orphan_users;
    END IF;
END $$;

ALTER TABLE user_preferences ALTER COLUMN user_uuid SET NOT NULL;

ALTER TABLE user_preferences DROP COLUMN user_id;

ALTER TABLE user_preferences
    ADD CONSTRAINT uq_user_preferences_user_key UNIQUE (user_uuid, pref_key);
