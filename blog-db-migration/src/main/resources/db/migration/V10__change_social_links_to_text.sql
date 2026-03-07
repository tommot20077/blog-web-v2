-- Change social_links from JSONB to TEXT for Spring Data JDBC compatibility.
-- Spring Data JDBC binds String fields as VARCHAR; PostgreSQL rejects VARCHAR→JSONB assignment.
-- TEXT accepts any string (including valid JSON) without type coercion issues.
ALTER TABLE users ALTER COLUMN social_links TYPE TEXT USING social_links::TEXT;
