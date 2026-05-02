-- V15__add_article_series_and_rename_likes.sql

-- ─────────────────────────────────────────────
-- Part 1: 新建 series 表
-- ─────────────────────────────────────────────
CREATE TABLE series (
    id              BIGSERIAL    PRIMARY KEY,
    uuid            UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    title           VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL UNIQUE,
    description     TEXT         NULL,
    cover_image_url VARCHAR(512) NULL,
    author_id       BIGINT       NOT NULL REFERENCES users(id),
    article_count   INTEGER      NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_series_author ON series(author_id);

-- ─────────────────────────────────────────────
-- Part 2: articles 表加 series 關聯
-- ─────────────────────────────────────────────
ALTER TABLE articles ADD COLUMN series_id       BIGINT  NULL REFERENCES series(id) ON DELETE SET NULL;
ALTER TABLE articles ADD COLUMN series_position INTEGER NULL;

CREATE INDEX idx_articles_series_position
    ON articles(series_id, series_position)
    WHERE series_id IS NOT NULL;

-- ─────────────────────────────────────────────
-- Part 3: article_likes 改名 user_article_likes
-- ─────────────────────────────────────────────
ALTER TABLE article_likes RENAME TO user_article_likes;

ALTER TABLE user_article_likes
    RENAME CONSTRAINT uq_article_likes_user_article
    TO uq_user_article_likes_user_article;
