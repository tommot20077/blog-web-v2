-- 跨模組識別全面改為 UUID：article 模組的作者維度（設計 docs/plans/2026-10-24-cross-module-uuid-design.md §4、§5 第 6 列，M6-a）
--
-- 目標：articles 表不再有指向其他模組（users）的 Long 欄位與 FK。
--   articles.author_id（→ users(id)，NO ACTION） → author_uuid
-- 不做雙寫（無正式環境，Yuan 2026-10-24 確認），故以單一 migration 完成 expand 與 contract。
--
-- 刪除連動：使用者只做軟刪除，author_id → users(id) 的 NO ACTION FK 從未觸發，拿掉後行為不變，
-- 故不新增連動（使用者匿名化另案 DATA-02）。
--
-- 範圍邊界：articles.series_id → series(id) ON DELETE SET NULL 是系列維度，到 M6-b 才改為 series_uuid，
-- 本檔不碰（刪除 series 時文章脫離系列的行為不變）。
-- 新的 uuid 欄位不建 FK（跨模組不得有資料庫 FK）。
--
-- 舊欄位 DROP 時 PostgreSQL 會連帶移除其 FK（articles_author_id_fkey）與含該欄位的索引
-- idx_articles_author_id (author_id)；以同名在 author_uuid 上重建。

ALTER TABLE articles ADD COLUMN author_uuid UUID;

UPDATE articles a
SET author_uuid = u.uuid
FROM users u
WHERE u.id = a.author_id;

-- 孤兒保護：author_id 為 NOT NULL 且（至 V30 為止）有 FK，理論上每列都回填得到。
-- 仍然檢查：找不到作者的文章一律中止遷移（整個 migration 在同一交易內回滾），絕不刪除資料。
DO $$
DECLARE
    orphan_authors BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_authors FROM articles WHERE author_uuid IS NULL;
    RAISE NOTICE 'V31: articles 找不到對應作者的筆數 = %', orphan_authors;
    IF orphan_authors > 0 THEN
        RAISE EXCEPTION 'V31 中止：articles 有 % 筆找不到對應作者（author_id 無對應的 users 列），請先人工處理後再遷移', orphan_authors;
    END IF;
END $$;

ALTER TABLE articles ALTER COLUMN author_uuid SET NOT NULL;

ALTER TABLE articles DROP COLUMN author_id;

CREATE INDEX idx_articles_author_id ON articles (author_uuid);
