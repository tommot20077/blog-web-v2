-- V16__add_article_versions_and_user_preferences.sql

-- ─────────────────────────────────────────────
-- Part 1: article_versions 表
-- ─────────────────────────────────────────────
CREATE TABLE article_versions (
    id              BIGSERIAL    PRIMARY KEY,
    uuid            UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    article_id      BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    author_id       BIGINT       NOT NULL REFERENCES users(id),
    type            VARCHAR(20)  NOT NULL CHECK (type IN ('AUTO','MANUAL','PUBLISHED')),
    title           VARCHAR(255) NOT NULL,
    slug            VARCHAR(255) NOT NULL,
    content         TEXT         NOT NULL,
    summary         VARCHAR(500) NULL,
    category_id     BIGINT       NULL,
    cover_image_url VARCHAR(512) NULL,
    status          VARCHAR(20)  NOT NULL,
    tags            UUID[]       NULL,
    note            VARCHAR(255) NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_article_versions_article_created
    ON article_versions(article_id, created_at DESC);
CREATE INDEX idx_article_versions_article_type
    ON article_versions(article_id, type);

-- ─────────────────────────────────────────────
-- Part 2: user_preferences 表
-- ─────────────────────────────────────────────
CREATE TABLE user_preferences (
    id         BIGSERIAL    PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    pref_key   VARCHAR(100) NOT NULL,
    pref_value TEXT         NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_preferences_user_key UNIQUE (user_id, pref_key)
);