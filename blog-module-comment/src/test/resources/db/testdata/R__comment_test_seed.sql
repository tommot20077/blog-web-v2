-- Seed test users for CommentControllerIT
-- user_id 1 (USER), 2 (USER - other), 3 (ADMIN)
INSERT INTO users (id, uuid, email, password_hash, nickname, username, role, status, email_verified)
VALUES
    (1, uuid_generate_v4(), 'user1@test.com', '$2a$10$dummy', 'User1', 'user1', 'USER', 'ACTIVE', TRUE),
    (2, uuid_generate_v4(), 'user2@test.com', '$2a$10$dummy', 'User2', 'user2', 'USER', 'ACTIVE', TRUE),
    (3, uuid_generate_v4(), 'admin@test.com',  '$2a$10$dummy', 'Admin', 'admin', 'ADMIN', 'ACTIVE', TRUE)
ON CONFLICT (id) DO NOTHING;
