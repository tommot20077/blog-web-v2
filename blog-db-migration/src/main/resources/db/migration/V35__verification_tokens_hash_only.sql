-- ARCH-24 殘餘（W7-a／A1，Yuan 2026-11-07 決定）：verification_tokens 只存 SHA-256，不再存明文 token
--
-- 做法：token 由寄信 consumer 在寄信當下產生，DB 只存其 SHA-256（64 字元小寫 hex），信件內容才是明文。
-- 欄位 token 更名為 token_hash，讓「這個欄位存的是雜湊」寫進名字，避免日後有人誤把明文寫回去。
--
-- 既有資料：舊列存的是明文 token，更名後會被誤當成雜湊（永遠對不上任何輸入，等於無效 token 卻殘留明文）。
-- 全新開站沒有需要保留的資料；有殘留列時一併清掉（使用者重新註冊／忘記密碼／重寄即可取得新 token）。
-- 表已被 UNIQUE 約束與 PK 索引，索引隨欄位更名自動跟隨，這裡只重新命名自動產生的約束名稱使其語意一致。

DELETE FROM verification_tokens;

ALTER TABLE verification_tokens RENAME COLUMN token TO token_hash;
ALTER TABLE verification_tokens RENAME CONSTRAINT verification_tokens_token_key TO uq_verification_tokens_token_hash;
