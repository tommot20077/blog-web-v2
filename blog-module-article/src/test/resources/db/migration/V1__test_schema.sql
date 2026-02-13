-- 文章模組整合測試 Schema
-- 僅包含文章測試所需的最小資料表定義

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- 用戶表（文章外鍵依賴）
CREATE TABLE users
(
    id            BIGSERIAL PRIMARY KEY,
    uuid          UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname      VARCHAR(50)  NOT NULL,
    avatar_url    VARCHAR(512),
    bio           TEXT,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status        VARCHAR(20)  NOT NULL,
    email_verified BOOLEAN     NOT NULL DEFAULT FALSE,
    token_version VARCHAR(10),
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 文章表
CREATE TABLE articles
(
    id              BIGSERIAL PRIMARY KEY,
    uuid            UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    author_id       BIGINT       NOT NULL REFERENCES users (id),
    title           VARCHAR(200) NOT NULL,
    slug            VARCHAR(250) NOT NULL UNIQUE,
    summary         VARCHAR(500),
    content_md      TEXT         NOT NULL,
    cover_image_url VARCHAR(512),
    status          VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    view_count      BIGINT       NOT NULL DEFAULT 0,
    like_count      BIGINT       NOT NULL DEFAULT 0,
    comment_count   INTEGER      NOT NULL DEFAULT 0,
    published_at    TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_articles_uuid ON articles (uuid);
CREATE INDEX idx_articles_author_id ON articles (author_id);
CREATE INDEX idx_articles_status ON articles (status);
CREATE INDEX idx_articles_created_at ON articles (created_at DESC);

-- 測試用預設作者
INSERT INTO users (id, uuid, email, password_hash, nickname, role, status, email_verified)
VALUES (1, uuid_generate_v4(), 'author@test.com', '$2a$10$dummy', 'TestAuthor', 'AUTHOR', 'ACTIVE', TRUE);
