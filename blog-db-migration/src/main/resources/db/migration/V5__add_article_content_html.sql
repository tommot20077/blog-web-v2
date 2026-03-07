-- 新增文章 HTML 預渲染欄位
-- 儲存由 Markdown 轉換的 HTML 內容，避免前端重複渲染

ALTER TABLE articles ADD COLUMN content_html TEXT;
