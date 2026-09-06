-- Enable UUID extension
CREATE
EXTENSION IF NOT EXISTS "uuid-ossp";

-- Users Table
CREATE TABLE users
(
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    email          VARCHAR(255) NOT NULL UNIQUE,
    password_hash  VARCHAR(255) NOT NULL,
    nickname       VARCHAR(50)  NOT NULL,
    avatar_url     VARCHAR(512),
    bio            TEXT,
    role           VARCHAR(20)  NOT NULL DEFAULT 'USER',
    status         VARCHAR(20)  NOT NULL,
    email_verified BOOLEAN      NOT NULL DEFAULT FALSE,
    token_version  VARCHAR(10),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Verification Tokens Table
CREATE TABLE verification_tokens
(
    id BIGSERIAL PRIMARY KEY,
    user_id    BIGINT       NOT NULL REFERENCES users (id),
    token      VARCHAR(255) NOT NULL UNIQUE,
    type       VARCHAR(20)  NOT NULL,
    expires_at TIMESTAMP    NOT NULL,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Articles Table
CREATE TABLE articles
(
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
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

-- Tags Table
CREATE TABLE tags
(
    id BIGSERIAL PRIMARY KEY,
    name        VARCHAR(50) NOT NULL UNIQUE,
    slug        VARCHAR(60) NOT NULL UNIQUE,
    description VARCHAR(200),
    color       VARCHAR(7),
    usage_count INTEGER     NOT NULL DEFAULT 0,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Article Tags Relation
CREATE TABLE article_tags
(
    article_id BIGINT REFERENCES articles (id) ON DELETE CASCADE,
    tag_id     BIGINT REFERENCES tags (id) ON DELETE CASCADE,
    PRIMARY KEY (article_id, tag_id)
);

-- Article Likes
CREATE TABLE article_likes
(
    id BIGSERIAL PRIMARY KEY,
    article_id BIGINT REFERENCES articles (id) ON DELETE CASCADE,
    user_id    BIGINT REFERENCES users (id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (article_id, user_id)
);

-- Comments Table
CREATE TABLE comments
(
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    article_id BIGINT REFERENCES articles (id) ON DELETE CASCADE,
    user_id    BIGINT REFERENCES users (id),
    parent_id  BIGINT REFERENCES comments (id) ON DELETE CASCADE,
    content    TEXT        NOT NULL,
    status     VARCHAR(20) NOT NULL DEFAULT 'VISIBLE',
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Files Table
CREATE TABLE files
(
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    uploader_id    BIGINT       NOT NULL REFERENCES users (id),
    original_name  VARCHAR(255) NOT NULL,
    storage_key    VARCHAR(512) NOT NULL UNIQUE,
    content_type   VARCHAR(100) NOT NULL,
    size_bytes     BIGINT       NOT NULL,
    category       VARCHAR(20)  NOT NULL,
    reference_id   BIGINT,
    reference_type VARCHAR(50),
    metadata JSONB,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX idx_files_uploader ON files (uploader_id);
CREATE INDEX idx_files_reference ON files (reference_type, reference_id);
