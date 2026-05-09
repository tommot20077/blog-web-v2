package dowob.xyz.blog.e2e.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * E2E 測試間資料清理工具
 * <p>
 * 由於 RabbitMQ consumer 在獨立 thread 中執行 DB 操作，
 * {@code @Transactional} rollback 無法覆蓋，因此需要手動清理。
 * </p>
 */
@Component
public class DatabaseCleaner {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RedisConnectionFactory redisConnectionFactory;

    /**
     * 清除所有測試資料（按外鍵順序刪除）
     */
    public void cleanAll() {
        // 冪等 dedup 表（無 FK 依賴，最先清）
        jdbcTemplate.execute("DELETE FROM processed_events");

        // 關聯 / 使用者狀態表（先刪，無外鍵衝突）
        jdbcTemplate.execute("DELETE FROM article_tags");
        jdbcTemplate.execute("DELETE FROM article_categories");
        jdbcTemplate.execute("DELETE FROM user_article_likes");
        jdbcTemplate.execute("DELETE FROM comment_likes");
        jdbcTemplate.execute("DELETE FROM user_tag_follows");
        jdbcTemplate.execute("DELETE FROM user_bookmarks");
        jdbcTemplate.execute("DELETE FROM user_highlights");
        jdbcTemplate.execute("DELETE FROM user_reading_progress");
        jdbcTemplate.execute("DELETE FROM comments");

        // V16 新增表（article_id/user_id FK ON DELETE CASCADE，但顯式刪更直觀）
        jdbcTemplate.execute("DELETE FROM article_versions");
        jdbcTemplate.execute("DELETE FROM user_preferences");

        // 實體表（articles 在 series 之前刪：series_id ON DELETE SET NULL，順序不衝突）
        jdbcTemplate.execute("DELETE FROM file_metadata");
        jdbcTemplate.execute("DELETE FROM verification_tokens");
        jdbcTemplate.execute("DELETE FROM articles");
        jdbcTemplate.execute("DELETE FROM series");
        jdbcTemplate.execute("DELETE FROM tags");
        jdbcTemplate.execute("DELETE FROM categories");
        jdbcTemplate.execute("DELETE FROM users");

        // Redis 全清
        redisConnectionFactory.getConnection().serverCommands().flushAll();
    }
}
