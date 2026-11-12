-- ARCH-30 第 2 段 P1（Expand）：comments 以 articles.uuid 識別文章
-- 計畫：ai-docs/backlog/2026-09-28-arch30-uuid-currency-plan.md §3
--
-- 其他模組以 articles.id（Long 內部主鍵）識別文章，這是跨模組耦合的真正載體。
-- 本 migration 只「加」不「減」：新增 article_uuid 並回填，舊的 article_id 欄位、FK、索引全部保留，
-- 留待 P3 切換讀寫、P4 再移除。
--
-- 刪除語意不變：新 FK 同為 ON DELETE CASCADE（與 V9 article_tags → articles(uuid) 同做法），
-- 刪除文章時留言經由新舊任一 FK 一併刪除。

ALTER TABLE comments ADD COLUMN article_uuid UUID;

-- article_id 為 NOT NULL 且有 FK → articles(id)，故每一列都找得到對應文章，回填後不會殘留 NULL
UPDATE comments c
SET article_uuid = a.uuid
FROM articles a
WHERE a.id = c.article_id;

ALTER TABLE comments ALTER COLUMN article_uuid SET NOT NULL;

ALTER TABLE comments
    ADD CONSTRAINT comments_article_uuid_fkey
        FOREIGN KEY (article_uuid) REFERENCES articles (uuid) ON DELETE CASCADE;

-- 對應 V13 的 idx_comments_article_top_level（列出文章的頂層留言），供 P3 改以 article_uuid 查詢
CREATE INDEX idx_comments_article_uuid_top_level
    ON comments (article_uuid, created_at DESC)
    WHERE parent_id IS NULL;

-- 涵蓋所有留言（含回覆）：上面的 partial index 只含頂層列，無法支援
--   1. 刪除文章時 FK CASCADE 找出該文所有留言（PostgreSQL 不會自動替 FK 的參照端建索引）
--   2. CommentMapper.countByArticle 的含回覆計數
-- 舊的 article_id 同樣缺這個索引（既有問題）；P1～P3 新舊 FK 並存期間若新欄位也缺，刪文章要全表掃兩次。
CREATE INDEX idx_comments_article_uuid
    ON comments (article_uuid);
