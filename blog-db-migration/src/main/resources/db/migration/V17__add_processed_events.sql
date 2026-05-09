-- V17__add_processed_events.sql
-- 目的：MQ event 冪等記錄表，所有 consumer 共用 IdempotencyService 對此表做 dedup。

CREATE TABLE processed_events (
    id            BIGSERIAL    PRIMARY KEY,
    event_id      UUID         NOT NULL,
    consumer_name VARCHAR(100) NOT NULL,
    processed_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_processed_events_event_consumer UNIQUE (event_id, consumer_name)
);

CREATE INDEX idx_processed_events_processed_at
    ON processed_events(processed_at);
