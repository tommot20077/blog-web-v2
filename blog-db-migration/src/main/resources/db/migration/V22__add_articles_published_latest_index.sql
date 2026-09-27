-- V22__add_articles_published_latest_index.sql
-- 目的：公開文章列表改為伺服器端排序後（SEC-04 前置），預設排序
--   ORDER BY published_at DESC NULLS LAST, id DESC 需要一個能直接依序讀取的索引。
--   idx_articles_published_latest：partial index，只涵蓋 status = 'PUBLISHED' 的列——
--     公開列表的 WHERE 必帶此條件，草稿／待審／駁回的文章不需要進這個索引。
--     欄位順序與 NULLS LAST 須與 ArticleMapper.PUBLISHED_CRITERIA_ORDER_BY 的
--     latest 分支完全一致，planner 才能省掉排序步驟。
--   附帶效益：findings PERF-08 指出 published_at 無索引，而 TrendingRefreshJob 的
--     findPublishedAfter（status = 'PUBLISHED' AND published_at >= ?）每 30 分鐘跑 3 次，
--     本索引同樣可供其範圍掃描使用。
-- 刻意不建：view_count / comment_count 的排序索引（sort=popular / commented）。
--   view_count 由 ViewCountFlushJob 每 5 分鐘批次回寫、comment_count 每則留言更新一次；
--   一旦被索引，這些 UPDATE 全部失去 HOT update 資格，寫入放大與索引膨脹的代價
--   高於部落格規模（已發布文章數千篇以內）下 top-N 排序的成本。量級改變時再評估。

CREATE INDEX idx_articles_published_latest
    ON articles (published_at DESC NULLS LAST, id DESC)
    WHERE status = 'PUBLISHED';
