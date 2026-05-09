INSERT INTO users (id, uuid, email, password_hash, nickname, username, role, status, email_verified, created_at, updated_at)
VALUES
    (1, gen_random_uuid(), 'user1@version-test.com', 'hash', 'User1', 'version-user1', 'AUTHOR', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (2, gen_random_uuid(), 'user2@version-test.com', 'hash', 'User2', 'version-user2', 'AUTHOR', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    (3, gen_random_uuid(), 'admin@version-test.com', 'hash', 'Admin', 'version-admin', 'ADMIN', 'ACTIVE', true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON CONFLICT (id) DO NOTHING;

SELECT setval('users_id_seq', GREATEST(3, (SELECT MAX(id) FROM users)));
