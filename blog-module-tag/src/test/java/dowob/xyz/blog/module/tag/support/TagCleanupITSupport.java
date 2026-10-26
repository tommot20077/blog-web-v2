package dowob.xyz.blog.module.tag.support;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.tag.TestTagApplication;
import org.junit.jupiter.api.AfterEach;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

/**
 * tag 模組「文章刪除連動」、「孤兒校正排程」與「併發建立標籤」整合測試的共用基底。
 *
 * <p>以<b>單例</b> Postgres／Redis 容器（static 區塊啟動、不隨測試類別停止，由 Testcontainers 的 Ryuk 收尾）：
 * 多個 IT 類別共用同一個 Spring context 快取時，若容器逐類別重啟，快取中的 context 會指向已關閉的容器。</p>
 *
 * <p>{@code article_tags.article_id} 自 V33 起沒有指向 {@code articles} 的 FK，因此測試可以直接寫入
 * 「指向不存在文章」的列（孤兒），不需要 article 模組。文章存在性（{@link ArticleFacade}）以 Mock 取代，
 * 各測試自行決定哪些文章 UUID「存在」。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestTagApplication.class)
@ActiveProfiles("test")
public abstract class TagCleanupITSupport {

    /** 單例 Postgres 容器 */
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    /** 單例 Redis 容器 */
    protected static final com.redis.testcontainers.RedisContainer REDIS =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    /**
     * 動態注入容器連線設定。
     *
     * @param registry Spring 動態屬性源
     */
    @DynamicPropertySource
    static void configureContainers(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired protected JdbcTemplate jdbc;

    /** 避免啟動時找不到 RabbitMQ 連線 Bean */
    @MockitoBean protected ConnectionFactory connectionFactory;
    /** JwtAuthenticationFilter 依賴，避免需要 user 模組實作 */
    @MockitoBean protected UserAuthService userAuthService;
    /** 避免依賴 user 模組 */
    @MockitoBean protected UserFacade userFacade;
    /** 孤兒校正排程的文章存在性查詢（owner 在 article 模組，本模組測試不載入） */
    @MockitoBean protected ArticleFacade articleFacade;

    /** 每個測試結束後清空 tag 相關表。 */
    @AfterEach
    void cleanUpTagData() {
        jdbc.update("DELETE FROM user_tag_follows");
        jdbc.update("DELETE FROM article_tags");
        jdbc.update("DELETE FROM tags");
    }

    /**
     * 直接以 SQL 寫入一個標籤。
     *
     * @param name       標籤名稱
     * @param usageCount 使用計數
     * @return 標籤 UUID
     */
    protected UUID insertTag(String name, int usageCount) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tags (id, name, slug, usage_count, created_at) VALUES (?, ?, ?, ?, NOW())",
                id, name, name, usageCount);
        return id;
    }

    /**
     * 直接以 SQL 寫入一筆文章-標籤關聯（文章 UUID 可以是不存在的）。
     *
     * @param articleUuid 文章 UUID
     * @param tagId       標籤 UUID
     */
    protected void insertArticleTag(UUID articleUuid, UUID tagId) {
        jdbc.update("INSERT INTO article_tags (article_id, tag_id) VALUES (?, ?)", articleUuid, tagId);
    }

    /**
     * 計算某篇文章的標籤關聯筆數。
     *
     * @param articleUuid 文章 UUID
     * @return 關聯筆數
     */
    protected int countArticleTags(UUID articleUuid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM article_tags WHERE article_id = ?", Integer.class, articleUuid);
    }

    /**
     * 讀取標籤的使用計數。
     *
     * @param tagId 標籤 UUID
     * @return usage_count
     */
    protected int usageCountOf(UUID tagId) {
        return jdbc.queryForObject("SELECT usage_count FROM tags WHERE id = ?", Integer.class, tagId);
    }

    /**
     * 計算 tags 表的總列數。
     *
     * @return 列數
     */
    protected int countTags() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class);
    }
}
