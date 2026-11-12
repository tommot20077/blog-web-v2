-- V13__add_comment_and_comment_likes_schema.sql

-- 1. articles：統一 like_count 型別 (BIGINT → INTEGER)
-- 先檢查既有資料是否落在 INTEGER 範圍，避免線上資料超界導致 migration 中段失敗
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM articles
         WHERE like_count < -2147483648 OR like_count > 2147483647
    ) THEN
        RAISE EXCEPTION
            'Cannot convert articles.like_count from BIGINT to INTEGER: existing values out of INTEGER range';
    END IF;
END
$$;
ALTER TABLE articles ALTER COLUMN like_count TYPE INTEGER USING like_count::INTEGER;

-- 2. 清理重複索引（articles_uuid_key 已是 UNIQUE，idx_articles_uuid 多餘）
DROP INDEX IF EXISTS idx_articles_uuid;

-- 3. 改造既有 comments 表
ALTER TABLE comments DROP COLUMN status;

ALTER TABLE comments ADD COLUMN content_html    TEXT     NOT NULL DEFAULT '';
ALTER TABLE comments ADD COLUMN like_count      INTEGER  NOT NULL DEFAULT 0;
ALTER TABLE comments ADD COLUMN edited_at       TIMESTAMP NULL;
ALTER TABLE comments ADD COLUMN deleted_at      TIMESTAMP NULL;
ALTER TABLE comments ADD COLUMN deleted_by_role VARCHAR(20) NULL;

CREATE INDEX idx_comments_article_top_level
    ON comments(article_id, created_at DESC)
    WHERE parent_id IS NULL;

CREATE INDEX idx_comments_replies
    ON comments(parent_id, created_at)
    WHERE parent_id IS NOT NULL;

CREATE INDEX idx_comments_user_created
    ON comments(user_id, created_at DESC);

-- 4. article_likes：UNIQUE 順序 → (user_id, article_id)
ALTER TABLE article_likes
    DROP CONSTRAINT article_likes_article_id_user_id_key;

ALTER TABLE article_likes
    ADD CONSTRAINT uq_article_likes_user_article UNIQUE (user_id, article_id);

-- 5. 新建 comment_likes
CREATE TABLE comment_likes (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL REFERENCES users(id),
    comment_id BIGINT    NOT NULL REFERENCES comments(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_comment_likes_user_comment UNIQUE (user_id, comment_id)
);
