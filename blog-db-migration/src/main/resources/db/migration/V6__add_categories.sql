-- 分類主表
CREATE TABLE categories
(
    id          BIGSERIAL PRIMARY KEY,
    uuid        UUID         NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    name        VARCHAR(50)  NOT NULL UNIQUE,
    slug        VARCHAR(60)  NOT NULL UNIQUE,
    description VARCHAR(200),
    sort_order  INT          NOT NULL        DEFAULT 0,
    created_at  TIMESTAMP    NOT NULL        DEFAULT CURRENT_TIMESTAMP
);

-- 文章-分類多對多
CREATE TABLE article_categories
(
    article_id  BIGINT REFERENCES articles (id) ON DELETE CASCADE,
    category_id BIGINT REFERENCES categories (id) ON DELETE CASCADE,
    PRIMARY KEY (article_id, category_id)
);

-- idx_article_categories_article_id 已由複合主鍵 (article_id, category_id) 的首欄自動涵蓋，無需重複建立
CREATE INDEX idx_article_categories_category_id ON article_categories (category_id);
