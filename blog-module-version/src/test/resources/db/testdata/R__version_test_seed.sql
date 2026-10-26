-- version 模組 IT 的固定使用者。uuid 固定為 00000000-0000-0000-0000-0000000000bN（N 為主鍵）：
-- 作者／admin 比對改以 uuid 進行，IT 需以同一組 uuid 建立 AuthUser（見 SeededVersionUsers）。
-- 與 comment（...00N）、reading（...0000aN）的固定值刻意不同。
INSERT INTO users (id, uuid, email, password_hash, nickname, username, role, status, email_verified, created_at, updated_at)
VALUES
    (1, '00000000-0000-0000-0000-0000000000b1', 'user1@version-test.com', 'hash', 'User1', 'version-user1', 'AUTHOR', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '00000000-0000-0000-0000-0000000000b2', 'user2@version-test.com', 'hash', 'User2', 'version-user2', 'AUTHOR', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '00000000-0000-0000-0000-0000000000b3', 'admin@version-test.com', 'hash', 'Admin', 'version-admin', 'ADMIN', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval('users_id_seq', GREATEST(3, (SELECT MAX(id) FROM users)));
