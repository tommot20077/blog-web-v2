package dowob.xyz.blog.module.version.support;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.version.config.VersionTestApplication;
import org.junit.jupiter.api.AfterEach;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * version 模組「文章刪除連動」與「孤兒校正排程」整合測試的共用基底。
 *
 * <p>以<b>單例</b> Postgres／Redis 容器（static 區塊啟動、不隨測試類別停止，由 Testcontainers 的 Ryuk 收尾）：
 * 多個 IT 類別共用同一個 Spring context 快取時，若容器逐類別重啟，快取中的 context 會指向已關閉的容器。</p>
 *
 * <p>提供建立文章、直接以 SQL 寫入版本快照（含「指向不存在文章」的孤兒列，V28 起 {@code article_versions}
 * 沒有指向 {@code articles} 的 FK）與計數的輔助方法；每個測試結束後清空 version 相關表與 articles。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = VersionTestApplication.class)
@ActiveProfiles("test")
public abstract class VersionCleanupITSupport {

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
    @MockitoBean private SeriesFacade seriesFacade;
    /** article 模組的 ArticleFileBinder 依賴 FileFacade，其實作在 blog-module-file（未被本測試 scan） */
    @MockitoBean private FileFacade fileFacade;

    @Autowired protected ArticleRepository articleRepo;
    @Autowired protected JdbcTemplate jdbc;

    /** 清空本測試建立的資料（version 相關表先於 articles）。 */
    @AfterEach
    void cleanUpVersionCleanupData() {
        jdbc.update("DELETE FROM article_versions");
        jdbc.update("DELETE FROM user_preferences");
        articleRepo.deleteAll();
    }

    /**
     * 建立一篇文章（作者為 seed 的作者 1）。
     *
     * @param status 文章狀態
     * @return 已儲存的文章
     */
    protected Article createArticle(ArticleStatus status) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(SeededVersionUsers.USER1_ID);
        article.setTitle("version-cleanup-it");
        article.setSlug("version-cleanup-it-" + UUID.randomUUID());
        article.setContent("test");
        article.setContentHtml("<p>test</p>");
        article.setStatus(status);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    /**
     * 直接以 SQL 寫入一筆版本快照（不經 service）。文章 UUID 可以是不存在的（用來製造孤兒）。
     *
     * @param articleUuid 文章公開 UUID
     * @param type        快照類型（AUTO／MANUAL／PUBLISHED）
     */
    protected void insertVersion(UUID articleUuid, String type) {
        jdbc.update("INSERT INTO article_versions (uuid, article_uuid, author_uuid, type, title, slug, content, status) "
                        + "VALUES (?, ?, ?, ?, 't', 's', 'c', 'DRAFT')",
                UUID.randomUUID(), articleUuid, SeededVersionUsers.USER1_UUID, type);
    }

    /**
     * 計算某文章 UUID 底下的版本快照數。
     *
     * @param articleUuid 文章 UUID
     * @return 快照列數
     */
    protected int countVersions(UUID articleUuid) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM article_versions WHERE article_uuid = ?",
                Integer.class, articleUuid);
        return n == null ? 0 : n;
    }

    /**
     * 計算 user_preferences 的總列數。
     *
     * @return 偏好列數
     */
    protected int countPreferences() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM user_preferences", Integer.class);
        return n == null ? 0 : n;
    }
}
