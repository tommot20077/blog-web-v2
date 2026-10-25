package dowob.xyz.blog.module.reading.support;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.reading.config.ReadingTestApplication;
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
 * reading 各表（按讚、收藏、劃線、閱讀進度）的刪除連動、校正排程與併發整合測試的共用基底。
 *
 * <p>以<b>單例</b> Postgres／Redis 容器（static 區塊啟動、不隨測試類別停止，由 Testcontainers 的 Ryuk 收尾）：
 * 多個 IT 類別共用同一個 Spring context 快取時，若容器逐類別重啟，快取中的 context 會指向已關閉的容器。</p>
 *
 * <p>提供建立文章、直接以 SQL 寫入按讚／收藏／劃線／閱讀進度（含「指向不存在文章」的孤兒列）與計數的輔助方法；
 * 每個測試結束後清空 reading 相關表與 articles。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ReadingTestApplication.class)
@ActiveProfiles("test")
public abstract class ReadingCleanupITSupport {

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
    /** article 模組的 ArticleFileBinder 依賴 FileFacade，其實作在 blog-module-file（未被本測試 scan） */
    @MockitoBean private FileFacade fileFacade;

    @Autowired protected ArticleRepository articleRepo;
    @Autowired protected JdbcTemplate jdbc;

    /** seed 資料中的使用者公開 UUID（固定值，見 {@link SeededReadingUsers}） */
    protected static final UUID USER_UUID = SeededReadingUsers.USER_UUID;

    /** seed 資料中的另一位使用者公開 UUID */
    protected static final UUID OTHER_USER_UUID = SeededReadingUsers.OTHER_USER_UUID;

    /** 清空本測試建立的資料（reading 相關表先於 articles）。 */
    @AfterEach
    void cleanUpReadingCleanupData() {
        jdbc.update("DELETE FROM user_article_likes");
        jdbc.update("DELETE FROM user_bookmarks");
        jdbc.update("DELETE FROM user_highlights");
        jdbc.update("DELETE FROM user_reading_progress");
        articleRepo.deleteAll();
    }

    /**
     * 建立一篇文章（作者為 seed 的一般使用者）。
     *
     * @param status 文章狀態
     * @return 已儲存的文章
     */
    protected Article createArticle(ArticleStatus status) {
        return createArticle(SeededReadingUsers.USER_ID, status);
    }

    /**
     * 建立一篇指定作者的文章。
     *
     * @param authorId 作者主鍵（article 模組尚未改為 UUID，仍以主鍵寫入）
     * @param status   文章狀態
     * @return 已儲存的文章
     */
    protected Article createArticle(Long authorId, ArticleStatus status) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle("reading-cleanup-it");
        article.setSlug("reading-cleanup-it-" + UUID.randomUUID());
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
     * 直接以 SQL 寫入一筆按讚（不經 service，不動 like_count）。
     *
     * <p>V26 起 {@code user_article_likes} 沒有指向 {@code articles} 的 FK，
     * 文章 UUID 可以是不存在的（用來製造孤兒）。</p>
     *
     * @param userUuid    按讚者公開 UUID
     * @param articleUuid 文章公開 UUID
     */
    protected void insertLike(UUID userUuid, UUID articleUuid) {
        jdbc.update("INSERT INTO user_article_likes (user_uuid, article_uuid) VALUES (?, ?)", userUuid, articleUuid);
    }

    /**
     * 直接以 SQL 寫入一筆收藏（不經 service）。
     *
     * @param userUuid    收藏者公開 UUID
     * @param articleUuid 文章公開 UUID
     */
    protected void insertBookmark(UUID userUuid, UUID articleUuid) {
        jdbc.update("INSERT INTO user_bookmarks (user_uuid, article_uuid) VALUES (?, ?)", userUuid, articleUuid);
    }

    /**
     * 直接以 SQL 寫入一筆劃線（不經 service）。V27 起 {@code user_highlights} 沒有指向 users／articles 的 FK。
     *
     * @param userUuid    劃線者公開 UUID
     * @param articleUuid 文章公開 UUID
     */
    protected void insertHighlight(UUID userUuid, UUID articleUuid) {
        jdbc.update("INSERT INTO user_highlights (user_uuid, article_uuid, snippet) VALUES (?, ?, 'snippet')",
                userUuid, articleUuid);
    }

    /**
     * 直接以 SQL 寫入一筆閱讀進度（不經 service）。同一組 (user, article) 只能有一列（UNIQUE）。
     *
     * @param userUuid    讀者公開 UUID
     * @param articleUuid 文章公開 UUID
     */
    protected void insertProgress(UUID userUuid, UUID articleUuid) {
        jdbc.update("INSERT INTO user_reading_progress (user_uuid, article_uuid, progress) VALUES (?, ?, 0.5)",
                userUuid, articleUuid);
    }

    /**
     * 計算某文章 UUID 底下的按讚數。
     *
     * @param articleUuid 文章 UUID
     * @return 按讚列數
     */
    protected int countLikes(UUID articleUuid) {
        return count("SELECT COUNT(*) FROM user_article_likes WHERE article_uuid = ?", articleUuid);
    }

    /**
     * 計算某文章 UUID 底下的收藏數。
     *
     * @param articleUuid 文章 UUID
     * @return 收藏列數
     */
    protected int countBookmarks(UUID articleUuid) {
        return count("SELECT COUNT(*) FROM user_bookmarks WHERE article_uuid = ?", articleUuid);
    }

    /**
     * 計算某文章 UUID 底下的劃線數。
     *
     * @param articleUuid 文章 UUID
     * @return 劃線列數
     */
    protected int countHighlights(UUID articleUuid) {
        return count("SELECT COUNT(*) FROM user_highlights WHERE article_uuid = ?", articleUuid);
    }

    /**
     * 計算某文章 UUID 底下的閱讀進度列數。
     *
     * @param articleUuid 文章 UUID
     * @return 閱讀進度列數
     */
    protected int countProgress(UUID articleUuid) {
        return count("SELECT COUNT(*) FROM user_reading_progress WHERE article_uuid = ?", articleUuid);
    }

    /**
     * 讀取文章目前的 like_count。
     *
     * @param articleUuid 文章 UUID
     * @return like_count
     */
    protected int likeCountOf(UUID articleUuid) {
        return count("SELECT like_count FROM articles WHERE uuid = ?", articleUuid);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
