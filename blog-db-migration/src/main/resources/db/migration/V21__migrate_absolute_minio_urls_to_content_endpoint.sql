-- V21__migrate_absolute_minio_urls_to_content_endpoint.sql
--
-- 目的：把既有文章內文裡的「MinIO 絕對網址」改寫成相對的內容代理路徑，並回填檔案綁定。
--
-- 背景（V20 / 檔案存取控制）：
--   V20 之後上傳的檔案，url 一律回傳相對路徑 /api/v1/files/{id}/content，讀取時由
--   FileService#canRead 判斷授權後 302 導向 MinIO 短效簽名網址（bucket 維持私有）。
--   但 V20 之前寫入的內文存的是「{minio.endpoint}/{bucket}/{storage_path}」這種絕對網址，
--   它們：
--     (1) 不符合 ArticleFileBinder 掃描用的 /api/v1/files/{uuid}/content 樣式，
--         因此永遠不會被綁定到文章，article_uuid 恆為 NULL；
--     (2) 完全繞過 canRead——bucket 私有時圖片直接壞掉，bucket 若曾公開則等於
--         整套存取控制對舊內容無效。
--   兩種結果都不可接受，故以本 migration 一次性收斂。
--
-- 做法：
--   1. 逐一走訪 file_metadata，把內文中「任何以該檔案 storage_path 結尾的 http(s) 絕對網址」
--      改寫成 /api/v1/files/{file_id}/content。用 storage_path 當錨點而非硬編 endpoint，
--      因為 dev / prod 的 MinIO endpoint 不同，且歷史資料可能跨過不只一個 endpoint。
--   2. 依改寫後的相對路徑回填 file_metadata.article_uuid，讓這些圖恢復「隨文章公開狀態
--      決定可讀性」的正常行為。回填遵守與 FileServiceImpl#bindToArticle 相同的擁有權
--      不變量：只在「檔案上傳者 == 文章作者」時才綁定，避免經由舊內容注入他人檔案。
--
-- 冪等性：改寫的來源樣式（絕對網址）與目標樣式（相對路徑）不重疊，重跑不會二次改寫；
--         回填只處理 article_uuid IS NULL 的列。
--
-- 注意：content_html 一併改寫，讓已發布文章不必等作者重新儲存就能正常顯示；
--       下次儲存時 ArticleMarkdownRenderer 會由 content_md 重新產生，兩者仍會一致。

DO $$
DECLARE
    f RECORD;
    -- 逃逸 storage_path 內的正則特殊字元（實務上只有副檔名的 "."，仍一併處理以策安全）
    escaped_path TEXT;
    url_pattern  TEXT;
BEGIN
    FOR f IN SELECT id, storage_path FROM file_metadata WHERE storage_path IS NOT NULL LOOP
        escaped_path := regexp_replace(f.storage_path, '([.^$*+?()\[\]{}|\\])', '\\\1', 'g');
        -- 比對到 storage_path 為止即停，不吞掉後面的 markdown 語法字元（) " ' < > 空白）
        url_pattern := 'https?://[^\s"''<>()]*/' || escaped_path;

        UPDATE articles
        SET content_md = regexp_replace(content_md, url_pattern,
                                        '/api/v1/files/' || f.id || '/content', 'g'),
            content_html = CASE
                               WHEN content_html IS NULL THEN NULL
                               ELSE regexp_replace(content_html, url_pattern,
                                                   '/api/v1/files/' || f.id || '/content', 'g')
                           END
        WHERE content_md ~ url_pattern
           OR (content_html IS NOT NULL AND content_html ~ url_pattern);
    END LOOP;
END $$;

-- 回填綁定：只綁「檔案上傳者 == 文章作者」的組合（同 FileServiceImpl#bindToArticle 的不變量）
UPDATE file_metadata f
SET article_uuid = a.uuid
FROM articles a
         JOIN users u ON u.id = a.author_id
WHERE f.article_uuid IS NULL
  AND f.uploader_id = u.uuid
  AND a.content_md LIKE '%/api/v1/files/' || f.id || '/content%';
