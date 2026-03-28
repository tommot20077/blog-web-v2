-- D-1: article_likes 和 comments 的 article_id / user_id 補 NOT NULL 約束
-- 這些欄位在 V1__init_schema.sql 中遺漏了 NOT NULL，允許插入沒有對應文章或用戶的孤立記錄

ALTER TABLE article_likes ALTER COLUMN article_id SET NOT NULL;
ALTER TABLE article_likes ALTER COLUMN user_id SET NOT NULL;

ALTER TABLE comments ALTER COLUMN article_id SET NOT NULL;
ALTER TABLE comments ALTER COLUMN user_id SET NOT NULL;
