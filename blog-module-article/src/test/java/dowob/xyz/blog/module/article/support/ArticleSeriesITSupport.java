package dowob.xyz.blog.module.article.support;

import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleTestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.reset;

/**
 * article 模組「系列歸屬」相關整合測試（M6-b：系列刪除連動、每日校正、{@code series_uuid} SQL）的共用基底。
 *
 * <p>以<b>單例</b> Postgres／Redis 容器（static 區塊啟動、不隨測試類別停止，由 Testcontainers 的 Ryuk 收尾）：
 * 多個 IT 類別共用同一個 Spring context 快取時，若容器逐類別重啟，快取中的 context 會指向已關閉的容器。</p>
 *
 * <p>文章直接以 SQL 寫入（含指向不存在系列的 {@code series_uuid}，V32 起沒有 FK），避免耦合 article 的建立流程
 * （事件、標籤、檔案綁定）；article 不依賴 series 模組，{@link SeriesFacade} 以 mock 取代，由各測試自行設定其行為。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class)
@ActiveProfiles("test")
public abstract class ArticleSeriesITSupport {

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
     * 註冊容器連線屬性。
     *
     * @param registry 動態屬性註冊表
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.url", REDIS::getRedisURI);
    }

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private FileFacade fileFacade;
    /** series 模組的 facade（article 不依賴 series 模組，以 mock 取代；各測試自行設定行為） */
    @MockitoBean protected SeriesFacade seriesFacade;

    @Autowired protected JdbcTemplate jdbc;

    /** 每個測試開始前清空資料並重置 mock。 */
    @BeforeEach
    void resetSeriesItState() {
        reset(seriesFacade);
        jdbc.update("DELETE FROM articles");
    }

    /** 每個測試結束後清空本測試建立的文章。 */
    @AfterEach
    void cleanUpSeriesItData() {
        jdbc.update("DELETE FROM articles");
    }

    /**
     * 以 SQL 寫入一篇文章（作者為隨機 uuid；V31 起 articles 沒有指向 users 的 FK）。
     *
     * @param seriesUuid 所屬系列公開 UUID；null 表示不屬於任何系列（此時位置也應為 null）
     * @param position   在系列內的位置
     * @param status     文章狀態
     * @return 文章公開 UUID
     */
    protected UUID insertArticle(UUID seriesUuid, Integer position, String status) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, series_uuid, series_position) "
                        + "VALUES (?, ?, ?, ?, '內容', ?, ?, ?)",
                uuid, UUID.randomUUID(), "t-" + uuid, "slug-" + uuid, status, seriesUuid, position);
        return uuid;
    }

    /**
     * 以 SQL 寫入一篇文章並固定其 {@code version} 與 {@code updated_at}（用來斷言某操作不動這兩個欄位）。
     *
     * @param seriesUuid 所屬系列公開 UUID
     * @param position   在系列內的位置
     * @param version    樂觀鎖版本
     * @param updatedAt  最後更新時間
     * @return 文章公開 UUID
     */
    protected UUID insertArticleWithAudit(UUID seriesUuid, Integer position, int version, LocalDateTime updatedAt) {
        UUID uuid = insertArticle(seriesUuid, position, "PUBLISHED");
        jdbc.update("UPDATE articles SET version = ?, updated_at = ? WHERE uuid = ?",
                version, Timestamp.valueOf(updatedAt), uuid);
        return uuid;
    }

    /**
     * 讀取文章目前的系列公開 UUID。
     *
     * @param articleUuid 文章公開 UUID
     * @return series_uuid；NULL 時為 null
     */
    protected UUID seriesUuidOf(UUID articleUuid) {
        return jdbc.queryForObject("SELECT series_uuid FROM articles WHERE uuid = ?", UUID.class, articleUuid);
    }

    /**
     * 讀取文章目前在系列內的位置。
     *
     * @param articleUuid 文章公開 UUID
     * @return series_position；NULL 時為 null
     */
    protected Integer positionOf(UUID articleUuid) {
        return jdbc.queryForObject("SELECT series_position FROM articles WHERE uuid = ?", Integer.class, articleUuid);
    }

    /**
     * 讀取文章的樂觀鎖版本。
     *
     * @param articleUuid 文章公開 UUID
     * @return version
     */
    protected int versionOf(UUID articleUuid) {
        return jdbc.queryForObject("SELECT version FROM articles WHERE uuid = ?", Integer.class, articleUuid);
    }

    /**
     * 讀取文章的最後更新時間。
     *
     * @param articleUuid 文章公開 UUID
     * @return updated_at
     */
    protected LocalDateTime updatedAtOf(UUID articleUuid) {
        return jdbc.queryForObject("SELECT updated_at FROM articles WHERE uuid = ?", LocalDateTime.class, articleUuid);
    }

    /**
     * 計算 articles 的總列數。
     *
     * @return 文章列數
     */
    protected int countArticles() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM articles", Integer.class);
        return n == null ? 0 : n;
    }
}
