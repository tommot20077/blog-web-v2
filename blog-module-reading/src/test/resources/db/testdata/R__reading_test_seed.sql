-- Reading IT user seed
-- uuid 固定為 00000000-0000-0000-0000-0000000000aN（N 為主鍵）：收藏列表以 AuthUser.uuid() 與 users.uuid 比對可見性，
-- IT 需以固定值建立登入身分（見 SeededReadingUsers）。與 comment 模組 seed 的固定值刻意不同。
INSERT INTO users (id, uuid, email, password_hash, nickname, username, role, status, email_verified, created_at, updated_at)
VALUES
    (1, '00000000-0000-0000-0000-0000000000a1', 'user1@reading-test.com', 'hash', 'User1', 'reading-user1', 'USER', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '00000000-0000-0000-0000-0000000000a2', 'user2@reading-test.com', 'hash', 'User2', 'reading-user2', 'USER', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '00000000-0000-0000-0000-0000000000a3', 'admin@reading-test.com', 'hash', 'Admin', 'reading-admin', 'ADMIN', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval('users_id_seq', GREATEST(3, (SELECT MAX(id) FROM users)));
