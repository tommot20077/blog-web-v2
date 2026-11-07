package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleEngagementCounts;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleTestApplication;
import dowob.xyz.blog.module.article.service.ViewCountService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * {@link ArticleFacade#getEngagementCounts} 對真實 PostgreSQL 與 Redis 的整合測試（B4）。
 *
 * <p>驗證與詳情頁同源：瀏覽數 = DB 的 {@code view_count} + Redis 暫存增量（與
 * {@link ViewCountService#getViewCount} 逐篇比對一致），按讚數 = DB 的 {@code like_count}；
 * 以及超過 {@link BatchedQuery#BATCH_SIZE} 時切批後結果仍完整。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleFacade.getEngagementCounts 整合測試")
class ArticleFacadeEngagementCountsIT {

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

    private static final UUID SEEDED_AUTHOR_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    @Autowired private ArticleFacade articleFacade;
    @Autowired private ViewCountService viewCountService;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private StringRedisTemplate stringRedisTemplate;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private SeriesFacade seriesFacade;

    private final List<UUID> inserted = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        inserted.forEach(uuid -> {
            jdbcTemplate.update("DELETE FROM articles WHERE uuid = ?", uuid);
            stringRedisTemplate.delete("article:views:" + uuid);
        });
        inserted.clear();
    }

    private UUID insertArticle(int viewCount, int likeCount) {
        UUID uuid = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, view_count, like_count) "
                        + "VALUES (?, ?, ?, ?, ?, 'PUBLISHED', ?, ?)",
                uuid, SEEDED_AUTHOR_UUID, "t-" + uuid, "slug-" + uuid, "內容", viewCount, likeCount);
        inserted.add(uuid);
        return uuid;
    }

    @Test
    @DisplayName("viewCount = DB 值 + Redis 增量、likeCount = DB 值，且與詳情頁的 getViewCount 一致")
    void countsMatchDetailPageSource() {
        UUID withDelta = insertArticle(100, 7);
        UUID withoutDelta = insertArticle(40, 0);
        stringRedisTemplate.opsForValue().set("article:views:" + withDelta, "5");

        Map<UUID, ArticleEngagementCounts> result =
                articleFacade.getEngagementCounts(List.of(withDelta, withoutDelta));

        assertThat(result.get(withDelta)).isEqualTo(new ArticleEngagementCounts(105L, 7L));
        assertThat(result.get(withoutDelta)).isEqualTo(new ArticleEngagementCounts(40L, 0L));
        assertThat(result.get(withDelta).viewCount()).isEqualTo(viewCountService.getViewCount(withDelta));
        assertThat(result.get(withoutDelta).viewCount()).isEqualTo(viewCountService.getViewCount(withoutDelta));
    }

    @Test
    @DisplayName("不存在的文章不回傳；超過 BATCH_SIZE 切批後仍完整")
    void missingAbsent_largeInputComplete() {
        UUID exists = insertArticle(1, 1);
        List<UUID> input = new ArrayList<>();
        input.add(exists);
        input.add(UUID.randomUUID());
        for (int i = 0; i < BatchedQuery.BATCH_SIZE + 5; i++) {
            input.add(UUID.randomUUID());
        }

        Map<UUID, ArticleEngagementCounts> result = articleFacade.getEngagementCounts(input);

        assertThat(result).containsOnlyKeys(exists);
    }
}
