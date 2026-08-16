-- V20__add_file_metadata_article_uuid.sql
-- 目的：檔案存取控制（草稿圖片不公開）的地基——記錄「這個檔案綁定到哪篇文章」。
--   article_uuid：對應 articles.uuid（無 FK，見下方說明）。
--     未綁定（NULL）= 私有，僅上傳者與 ADMIN 可讀，屬 fail-safe 設計：
--     新文章尚未儲存時上傳的圖片，不會因為預設值而意外外洩。
--   idx_file_metadata_article_uuid：授權判斷（GET /files/{id}/content）與文章
--     儲存時「重新綁定→解除舊綁定」都會以 article_uuid 查詢，故建索引。
-- 注意：刻意不建 FK constraint。file 模組與 article 模組屬跨模組邊界，
--       兩模組僅能透過 Service/Facade 介面互動（ai-docs/architecture.md），
--       不可在資料庫層耦合；此取捨與 articles.cover_image_url 採用純字串
--       （非 FK）記錄跨模組參照的既有慣例一致。

ALTER TABLE file_metadata ADD COLUMN article_uuid UUID;

CREATE INDEX idx_file_metadata_article_uuid ON file_metadata (article_uuid);
