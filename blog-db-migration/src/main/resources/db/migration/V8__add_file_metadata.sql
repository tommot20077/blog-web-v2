CREATE TABLE IF NOT EXISTS file_metadata (
    id            UUID         PRIMARY KEY,
    original_name VARCHAR(255) NOT NULL,
    storage_path  VARCHAR(512) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    size          BIGINT       NOT NULL,
    width         INTEGER,
    height        INTEGER,
    usage_type    VARCHAR(50)  NOT NULL,
    has_thumbnail BOOLEAN      NOT NULL DEFAULT FALSE,
    uploader_id   UUID         NOT NULL,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_file_metadata_uploader_id ON file_metadata (uploader_id);
