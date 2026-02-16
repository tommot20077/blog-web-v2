CREATE TABLE file_metadata (
    id            UUID PRIMARY KEY,
    original_name VARCHAR(255)  NOT NULL,
    storage_path  VARCHAR(512)  NOT NULL,
    content_type  VARCHAR(100)  NOT NULL,
    size          BIGINT        NOT NULL,
    width         INT,
    height        INT,
    usage_type    VARCHAR(50)   NOT NULL,
    has_thumbnail BOOLEAN       NOT NULL DEFAULT false,
    uploader_id   UUID          NOT NULL,
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW()
);
