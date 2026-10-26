-- 跨模組識別全面改為 UUID：article 模組的系列維度（設計 docs/plans/2026-10-24-cross-module-uuid-design.md §4、§5 第 6 列，M6-b）
--
-- 目標：articles 表不再有指向其他模組（series）的 Long 欄位與 FK。
--   articles.series_id（→ series(id)，ON DELETE SET NULL） → series_uuid
-- 不做雙寫（無正式環境，Yuan 2026-10-24 確認），故以單一 migration 完成 expand 與 contract。
-- 至此 M6 完成：articles 沒有任何跨模組 FK（作者維度已於 V31 處理）。
--
-- series_uuid 維持可 NULL（文章不一定屬於系列），且不建 FK（跨模組不得有資料庫 FK）。
-- series_position 是 article 自己的欄位，不動。
--
-- 刪除連動：原本由 FK 的 ON DELETE SET NULL 在 series 被刪除時把 articles.series_id 設為 NULL；
-- FK 拿掉後改由 article 模組訂閱 series 的 SeriesDeletedEvent 清空 series_uuid／series_position，
-- 並以每日孤兒校正排程（ArticleSeriesOrphanReconciliationJob）兜底。刪除語意不變：文章留存、歸屬清空。
--
-- 舊欄位 DROP 時 PostgreSQL 會連帶移除其 FK（articles_series_id_fkey）與含該欄位的索引
-- idx_articles_series_position (series_id, series_position) WHERE series_id IS NOT NULL；
-- 以同名在 (series_uuid, series_position) 上重建 partial index。

ALTER TABLE articles ADD COLUMN series_uuid UUID;

UPDATE articles a
SET series_uuid = s.uuid
FROM series s
WHERE s.id = a.series_id;

-- 孤兒保護：series_id 非 NULL 且（至 V31 為止）有 FK，理論上每個有系列的列都回填得到。
-- 仍然檢查：series_id 非 NULL 卻找不到系列的文章一律中止遷移（整個 migration 在同一交易內回滾），絕不刪除資料。
-- series_id 為 NULL 者（沒有系列的文章）本來就維持 NULL，不算孤兒。
DO $$
DECLARE
    orphan_articles BIGINT;
BEGIN
    SELECT COUNT(*) INTO orphan_articles FROM articles WHERE series_id IS NOT NULL AND series_uuid IS NULL;
    RAISE NOTICE 'V32: articles 找不到對應系列的筆數 = %', orphan_articles;
    IF orphan_articles > 0 THEN
        RAISE EXCEPTION 'V32 中止：articles 有 % 筆找不到對應系列（series_id 非 NULL 卻無對應的 series 列），請先人工處理後再遷移', orphan_articles;
    END IF;
END $$;

ALTER TABLE articles DROP COLUMN series_id;

CREATE INDEX idx_articles_series_position
    ON articles (series_uuid, series_position)
    WHERE series_uuid IS NOT NULL;
