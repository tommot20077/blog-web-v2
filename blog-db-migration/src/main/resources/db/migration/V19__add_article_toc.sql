-- V19__add_article_toc.sql
-- 目的：新增文章章節導覽（TOC）持久化欄位。
--   toc：文章渲染時由 ArticleMarkdownRenderer 自 h2/h3 標題抽出的章節導覽，
--        以 JSON 字串儲存（同 content_html 生命週期，於 create/update 重算）。
--   採 TEXT 而非 JSONB：本專案已於 V10（users.social_links）確認 JSONB 與
--        Spring Data JDBC 不相容，全專案無 @MappedCollection，故沿用 TEXT + JSON 慣例。
-- 注意：nullable、無 DEFAULT；既有 16 篇測試文章不回填（見設計文件 §7 範圍外），
--       create/update 兩條路徑須顯式 set，避免 Spring Data JDBC 對未設值欄位送 NULL。

ALTER TABLE articles ADD COLUMN toc TEXT;
