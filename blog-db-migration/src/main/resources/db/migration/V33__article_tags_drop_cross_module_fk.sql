-- 跨模組識別全面改為 UUID：tag 模組（設計 docs/plans/2026-10-24-cross-module-uuid-design.md §4、§5 第 7 列，M7-tag）
--
-- article_tags.article_id 本身已是 UUID（V9），但仍有 FK → articles(uuid) ON DELETE CASCADE——
-- 這是最後一條跨模組 FK。本 migration 移除它；模組之間不得有資料庫 FK。
-- 欄位名稱不改（避免牽動 article 模組的多處 SQL），也不新增任何 FK。
--
-- 刪除連動：原本由 FK 的 ON DELETE CASCADE 在文章被刪除時刪除其 article_tags 列；FK 拿掉後改由
-- tag 模組訂閱 ArticleDeletedEvent（queue tag.article-deleted，TagArticleDeletedConsumer）刪除，
-- 並以每日孤兒校正排程（ArticleTagOrphanReconciliationJob，03:59）兜底。刪除語意不變：
-- 文章刪除 → 其 article_tags 列刪除；tags 本身與 tags.usage_count 不動（原 CASCADE 亦不動，計數重算另案 DATA-01）。
--
-- 保留：article_tags.tag_id → tags(id) 是模組內 FK，不動；PK (article_id, tag_id) 不動。
--
-- PERF-07：兩張表的 PK 皆以另一欄前導（article_tags 以 article_id、user_tag_follows 以 user_id），
-- 依 tag_id 查詢（標籤文章數、刪除標籤前的 FK 檢查、標籤追蹤者）無法用到 PK，補上 tag_id 索引。

ALTER TABLE article_tags DROP CONSTRAINT article_tags_article_id_fkey;

CREATE INDEX idx_article_tags_tag_id ON article_tags (tag_id);

CREATE INDEX idx_user_tag_follows_tag_id ON user_tag_follows (tag_id);
