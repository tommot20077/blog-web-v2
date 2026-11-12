-- V18__add_user_location_and_notification_prefs.sql
-- 目的：補齊設定頁面的後端持久化欄位。
--   location：使用者所在地（自由文字）。
--   notification_*：5 個通知偏好開關，預設全開（NOT NULL DEFAULT TRUE）。
-- 注意：Spring Data JDBC 對未設值欄位送明確 NULL，故 Java model 端 5 個 boolean
--       必須宣告為 = true，避免新使用者 insert 送 NULL 違反 NOT NULL 約束。

ALTER TABLE users ADD COLUMN location VARCHAR(100);
ALTER TABLE users ADD COLUMN notification_comment    BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notification_like       BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notification_review     BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notification_follow     BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN notification_newsletter BOOLEAN NOT NULL DEFAULT TRUE;
