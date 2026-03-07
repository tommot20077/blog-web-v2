-- V2: 補充 articles 表效能索引
-- articles 表已在 V1 建立，此處僅新增查詢效能索引

CREATE INDEX IF NOT EXISTS idx_articles_uuid ON articles (uuid);
CREATE INDEX IF NOT EXISTS idx_articles_author_id ON articles (author_id);
CREATE INDEX IF NOT EXISTS idx_articles_status ON articles (status);
CREATE INDEX IF NOT EXISTS idx_articles_created_at ON articles (created_at DESC);
