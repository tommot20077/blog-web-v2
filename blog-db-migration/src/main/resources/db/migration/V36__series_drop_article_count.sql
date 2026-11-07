-- DATA-10（決策 B6，Yuan 2026-11-07）：移除 series.article_count 反正規化計數。
--
-- 問題：article_count 靠 MQ 事件增減（ArticleDeletedEvent consumer）與 service 內的 +1／-1 維護，
-- 事件遺失、重複或與文章狀態變化（下架、退回草稿）交錯時就會漂移，且語意含非公開文章，與對外口徑不一致。
-- 做法：篇數一律由 article facade（countPublishedBySeriesUuids）即時計數；程式端已移除所有讀寫該欄位的路徑，
-- 這裡把欄位本身刪掉，避免日後有人誤讀到已不維護的值。
--
-- 不可逆：欄位內的歷史值會遺失；它本來就是可由 articles 重算的衍生資料，無需保留。
-- 部署順序：此 migration 在新版本啟動時執行；滾動更新期間仍在線上的舊版 pod 若讀寫 article_count 會失敗，
-- 屬短暫視窗（目前無正式環境，單副本部署）。

ALTER TABLE series DROP COLUMN article_count;
