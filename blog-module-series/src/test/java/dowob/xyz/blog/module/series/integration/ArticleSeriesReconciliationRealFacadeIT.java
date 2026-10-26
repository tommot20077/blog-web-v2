package dowob.xyz.blog.module.series.integration;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.reconciliation.OrphanReconciliationJob.Result;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.job.ArticleSeriesOrphanReconciliationJob;
import dowob.xyz.blog.module.series.config.SeriesTestApplication;
import dowob.xyz.blog.module.series.support.SeededUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * article 的 {@link ArticleSeriesOrphanReconciliationJob} 接<b>真實</b> {@code SeriesFacadeImpl}（不 mock）的整合測試。
 *
 * <p>article 模組自己的 IT 只能以 mock 的 SeriesFacade 回答存在性；這裡讓 series 的真實 facade 查真實的 series 表，
 * 確認：存在的系列成員不受影響、真正被刪除的系列成員被清空（文章留存）、孤兒系列數超過單次上限（20）時整次中止、
 * 零變更——避免存在性查詢誤判時一次清光大量歸屬。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("article 系列歸屬校正排程（真實 SeriesFacade）")
class ArticleSeriesReconciliationRealFacadeIT {

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

    @Autowired private ArticleSeriesOrphanReconciliationJob job;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private FileFacade fileFacade;

    @BeforeEach
    @AfterEach
    void cleanUp() {
        jdbc.execute("UPDATE articles SET series_uuid = NULL, series_position = NULL");
        jdbc.execute("DELETE FROM series");
        jdbc.execute("DELETE FROM articles");
        redisTemplate.delete(RedisKeyConstant.getReconciliationLockKey("article", "articles"));
        redisTemplate.delete(RedisKeyConstant.getReconciliationCursorKey("article", "articles"));
    }

    private UUID insertSeries(String slug) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO series (uuid, title, slug, author_uuid, article_count) VALUES (?, ?, ?, ?, 0)",
                uuid, "T-" + slug, slug, SeededUsers.USER1_UUID);
        return uuid;
    }

    private UUID insertArticle(UUID seriesUuid, Integer position) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, series_uuid, series_position) "
                        + "VALUES (?, ?, ?, ?, 'md', 'PUBLISHED', ?, ?)",
                uuid, SeededUsers.USER1_UUID, "t-" + uuid, "slug-" + uuid, seriesUuid, position);
        return uuid;
    }

    private UUID seriesUuidOf(UUID articleUuid) {
        return jdbc.queryForObject("SELECT series_uuid FROM articles WHERE uuid = ?", UUID.class, articleUuid);
    }

    @Test
    @DisplayName("真實 facade：仍存在的系列成員不受影響，已刪除系列的成員被清空歸屬，文章全部保留")
    void run_realFacade_clearsOnlyMembersOfDeletedSeries() {
        UUID alive1 = insertSeries("real-alive-1");
        UUID alive2 = insertSeries("real-alive-2");
        UUID gone = UUID.randomUUID();
        UUID aliveMember1 = insertArticle(alive1, 1);
        UUID aliveMember2 = insertArticle(alive2, 2);
        UUID orphan1 = insertArticle(gone, 1);
        UUID orphan2 = insertArticle(gone, 2);

        Result result = job.run();

        assertThat(result).isEqualTo(Result.COMPLETED);
        assertThat(seriesUuidOf(aliveMember1)).isEqualTo(alive1);
        assertThat(seriesUuidOf(aliveMember2)).isEqualTo(alive2);
        assertThat(seriesUuidOf(orphan1)).isNull();
        assertThat(seriesUuidOf(orphan2)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM articles", Integer.class)).isEqualTo(4);
    }

    @Test
    @DisplayName("真實 facade：孤兒系列超過單次上限（21 > 20）→ ABORTED、零變更")
    void run_realFacade_moreOrphanSeriesThanLimit_abortsWithoutChanging() {
        UUID alive1 = insertSeries("limit-alive-1");
        UUID alive2 = insertSeries("limit-alive-2");
        insertArticle(alive1, 1);
        insertArticle(alive2, 1);
        List<UUID> orphans = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            orphans.add(insertArticle(UUID.randomUUID(), i + 1));
        }

        Result result = job.run();

        assertThat(result).isEqualTo(Result.ABORTED);
        assertThat(orphans).allSatisfy(a -> assertThat(seriesUuidOf(a)).isNotNull());
    }
}
