-- Seed test users for the comment module ITs
-- user_id 1 (USER), 2 (USER - other), 3 (ADMIN)
-- uuid 固定為 00000000-0000-0000-0000-00000000000N：IT 以固定值建立 AuthUser、寫入留言並斷言（見 SeededUsers）
INSERT INTO users (id, uuid, email, password_hash, nickname, username, role, status, email_verified)
VALUES
    (1, '00000000-0000-0000-0000-000000000001', 'user1@test.com', '$2a$10$dummy', 'User1', 'user1', 'USER', 'ACTIVE', TRUE),
    (2, '00000000-0000-0000-0000-000000000002', 'user2@test.com', '$2a$10$dummy', 'User2', 'user2', 'USER', 'ACTIVE', TRUE),
    (3, '00000000-0000-0000-0000-000000000003', 'admin@test.com',  '$2a$10$dummy', 'Admin', 'admin', 'ADMIN', 'ACTIVE', TRUE)
ON CONFLICT (id) DO NOTHING;
