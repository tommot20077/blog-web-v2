INSERT INTO users (id, uuid, email, password_hash, nickname, role, status, email_verified)
VALUES (1, uuid_generate_v4(), 'author@test.com', '$2a$10$dummy', 'TestAuthor', 'AUTHOR', 'ACTIVE', TRUE)
ON CONFLICT (id) DO NOTHING;
