-- V14__add_reading_interactions.sql

-- ─────────────────────────────────────────────
-- 1. user_bookmarks（純 flag）
-- ─────────────────────────────────────────────
CREATE TABLE user_bookmarks (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL REFERENCES users(id),
    article_id BIGINT    NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_bookmarks_user_article UNIQUE (user_id, article_id)
);

-- ─────────────────────────────────────────────
-- 2. user_highlights
-- ─────────────────────────────────────────────
CREATE TABLE user_highlights (
    id         BIGSERIAL    PRIMARY KEY,
    uuid       UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    user_id    BIGINT       NOT NULL REFERENCES users(id),
    article_id BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    snippet    TEXT         NOT NULL,
    prefix     VARCHAR(64)  NOT NULL DEFAULT '',
    suffix     VARCHAR(64)  NOT NULL DEFAULT '',
    color      VARCHAR(7)   NOT NULL DEFAULT '#FFEB3B',
    note       TEXT         NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_user_highlights_color CHECK (color ~ '^#[0-9A-Fa-f]{6}$')
);

CREATE INDEX idx_user_highlights_article_for_user
    ON user_highlights(user_id, article_id);

CREATE INDEX idx_user_highlights_user_recent
    ON user_highlights(user_id, created_at DESC);

-- ─────────────────────────────────────────────
-- 3. user_reading_progress
-- ─────────────────────────────────────────────
CREATE TABLE user_reading_progress (
    id                  BIGSERIAL    PRIMARY KEY,
    user_id             BIGINT       NOT NULL REFERENCES users(id),
    article_id          BIGINT       NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    progress            NUMERIC(4,3) NOT NULL,
    last_heading_anchor VARCHAR(255) NULL,
    updated_at          TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_user_reading_progress_user_article UNIQUE (user_id, article_id),
    CONSTRAINT chk_user_reading_progress_range CHECK (progress >= 0 AND progress <= 1)
);

CREATE INDEX idx_user_reading_progress_user_recent
    ON user_reading_progress(user_id, updated_at DESC);
