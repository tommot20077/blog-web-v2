-- Series IT user seed
-- uuid 固定為 00000000-0000-0000-0000-0000000000cN（N 為主鍵）：series.author_uuid 與授權比對都以 AuthUser.uuid() 進行，
-- IT 以固定值建立 AuthUser、寫入 series 並斷言（見 SeededUsers）；不得與 comment（…00N）、reading（…0a?）、version（…0b?）重複。
INSERT INTO users (id, uuid, email, password_hash, nickname, username, role, status, email_verified, created_at, updated_at)
VALUES
    (1, '00000000-0000-0000-0000-0000000000c1', 'user1@series-test.com', 'hash', 'User1', 'series-user1', 'AUTHOR', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, '00000000-0000-0000-0000-0000000000c2', 'user2@series-test.com', 'hash', 'User2', 'series-user2', 'AUTHOR', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, '00000000-0000-0000-0000-0000000000c3', 'admin@series-test.com', 'hash', 'Admin', 'series-admin', 'ADMIN', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval('users_id_seq', GREATEST(3, (SELECT MAX(id) FROM users)));
