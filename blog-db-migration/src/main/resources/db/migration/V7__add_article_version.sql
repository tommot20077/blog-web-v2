-- -----------------------------------------------------------------------------
-- Description: 新增 articles.version 欄位以支援樂觀鎖 (Optimistic Locking)
-- Created At: 2026-02-28
-- -----------------------------------------------------------------------------

-- 新增 version 欄位，預設為 1 (第一版)
ALTER TABLE articles ADD COLUMN version BIGINT NOT NULL DEFAULT 1;

-- 說明:
-- Spring Data JDBC 或 JPA @Version 將會透過此欄位來處理樂觀鎖
-- 每次更新記錄時，version 值會自動 +1，若並發更新時版本號不匹配，
-- 將會拋出 OptimisticLockingFailureException。
