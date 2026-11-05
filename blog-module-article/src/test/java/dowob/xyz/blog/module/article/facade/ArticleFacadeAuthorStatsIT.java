package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.AuthorArticleStats;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleTestApplication;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公開作者頁的 {@link ArticleFacade} 方法整合測試：{@code countPublishedByAuthor}、{@code getPublishedStatsByAuthor}。
 *
 * <p>對真實 PostgreSQL 驗證 SQL 的三件事：① 只計 {@code PUBLISHED}（草稿、待審、退回、封存一律不計）；
 * ② 作者零篇時統計為 0 而非 null；③ 瀏覽數與按讚數只加總已發布文章。users 表完全不寫入——
 * 證明查詢不依賴 users（article 模組不得 JOIN users）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleFacade 公開作者頁統計整合測試")
class ArticleFacadeAuthorStatsIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.url", redis::getRedisURI);
    }

    @Autowired private ArticleFacade articleFacade;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private SeriesFacade seriesFacade;

    /** 本測試寫入的文章 uuid，供清理 */
    private final List<UUID> insertedArticles = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        insertedArticles.forEach(uuid -> jdbcTemplate.update("DELETE FROM articles WHERE uuid = ?", uuid));
        insertedArticles.clear();
    }

    private void insertArticle(UUID authorUuid, String status, long viewCount, int likeCount) {
        UUID uuid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, published_at, view_count, like_count) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW(), ?, ?)",
                uuid, authorUuid, "標題-" + uuid, "slug-" + uuid, "內容", status, viewCount, likeCount);
        insertedArticles.add(uuid);
    }

    @Test
    @DisplayName("getPublishedStatsByAuthor：只加總 PUBLISHED，草稿／待審／退回／封存的篇數、瀏覽、按讚都不計")
    void getPublishedStatsByAuthor_countsOnlyPublished() {
        UUID author = UUID.randomUUID();
        insertArticle(author, "PUBLISHED", 100, 5);
        insertArticle(author, "PUBLISHED", 20, 1);
        insertArticle(author, "DRAFT", 9000, 900);
        insertArticle(author, "PENDING_REVIEW", 9000, 900);
        insertArticle(author, "REJECTED", 9000, 900);
        insertArticle(author, "ARCHIVED", 9000, 900);

        AuthorArticleStats stats = articleFacade.getPublishedStatsByAuthor(author);

        assertThat(stats).isEqualTo(new AuthorArticleStats(2L, 120L, 6L));
    }

    @Test
    @DisplayName("getPublishedStatsByAuthor：只有草稿的作者與完全沒有文章的作者都回傳全 0，不是 null")
    void getPublishedStatsByAuthor_noPublishedArticles_returnsZeros() {
        UUID draftOnly = UUID.randomUUID();
        insertArticle(draftOnly, "DRAFT", 10, 1);

        assertThat(articleFacade.getPublishedStatsByAuthor(draftOnly)).isEqualTo(new AuthorArticleStats(0L, 0L, 0L));
        assertThat(articleFacade.getPublishedStatsByAuthor(UUID.randomUUID())).isEqualTo(new AuthorArticleStats(0L, 0L, 0L));
    }

    @Test
    @DisplayName("getPublishedStatsByAuthor：不混入其他作者的文章")
    void getPublishedStatsByAuthor_isolatedPerAuthor() {
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insertArticle(mine, "PUBLISHED", 7, 3);
        insertArticle(other, "PUBLISHED", 1000, 100);

        assertThat(articleFacade.getPublishedStatsByAuthor(mine)).isEqualTo(new AuthorArticleStats(1L, 7L, 3L));
    }

    @Test
    @DisplayName("countPublishedByAuthor：每位作者只計 PUBLISHED；只有草稿的作者不出現")
    void countPublishedByAuthor_countsOnlyPublishedAndOmitsDraftOnlyAuthors() {
        UUID twoPublished = UUID.randomUUID();
        UUID onePublishedPlusDraft = UUID.randomUUID();
        UUID draftOnly = UUID.randomUUID();
        insertArticle(twoPublished, "PUBLISHED", 0, 0);
        insertArticle(twoPublished, "PUBLISHED", 0, 0);
        insertArticle(onePublishedPlusDraft, "PUBLISHED", 0, 0);
        insertArticle(onePublishedPlusDraft, "DRAFT", 0, 0);
        insertArticle(draftOnly, "DRAFT", 0, 0);
        insertArticle(draftOnly, "ARCHIVED", 0, 0);

        Map<UUID, Long> result = articleFacade.countPublishedByAuthor();

        assertThat(result.get(twoPublished)).isEqualTo(2L);
        assertThat(result.get(onePublishedPlusDraft)).isEqualTo(1L);
        assertThat(result).doesNotContainKey(draftOnly);
    }
}
