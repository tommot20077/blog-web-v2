CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    uuid UUID NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS articles (
    id UUID PRIMARY KEY,
    uuid UUID NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL
);

CREATE TABLE tags (
    id           UUID PRIMARY KEY,
    name         VARCHAR(100)  NOT NULL UNIQUE,
    slug         VARCHAR(120)  NOT NULL UNIQUE,
    color        VARCHAR(20),
    icon         VARCHAR(100),
    description  TEXT,
    parent_id    UUID REFERENCES tags(id),
    usage_count  INT           NOT NULL DEFAULT 0,
    created_at   TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE TABLE article_tags (
    article_id UUID NOT NULL,
    tag_id     UUID NOT NULL REFERENCES tags(id),
    PRIMARY KEY (article_id, tag_id)
);

CREATE TABLE user_tag_follows (
    user_id UUID NOT NULL,
    tag_id  UUID NOT NULL REFERENCES tags(id),
    PRIMARY KEY (user_id, tag_id)
);
