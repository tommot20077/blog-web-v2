-- Remove V1 BIGSERIAL-based tables
DROP TABLE IF EXISTS article_tags;
DROP TABLE IF EXISTS tags;

-- Recreate tags with UUID PK (matches Tag model)
CREATE TABLE tags (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    slug        VARCHAR(120) NOT NULL UNIQUE,
    color       VARCHAR(20),
    icon        VARCHAR(100),
    description TEXT,
    parent_id   UUID REFERENCES tags (id),
    usage_count INTEGER      NOT NULL DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Recreate article_tags with UUID FKs
CREATE TABLE article_tags (
    article_id UUID NOT NULL REFERENCES articles (uuid) ON DELETE CASCADE,
    tag_id     UUID NOT NULL REFERENCES tags (id),
    PRIMARY KEY (article_id, tag_id)
);

-- Create user_tag_follows
CREATE TABLE IF NOT EXISTS user_tag_follows (
    user_id UUID NOT NULL,
    tag_id  UUID NOT NULL REFERENCES tags (id),
    PRIMARY KEY (user_id, tag_id)
);
