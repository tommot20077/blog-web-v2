package dowob.xyz.blog.module.series.integration;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code ArticleFacade} 條件式 series 指派／移出（M4-b，RACE-14）對真實 PostgreSQL 的整合測試。
 *
 * <p>本類別放在 series 模組，因為 {@link SeriesTestApplication} 同時掃描 article 與 series，
 * 是唯一同時具備真實 {@code ArticleFacade} 與 {@code SeriesFacade} 的環境（比照 {@code ArticleSeriesUuidParityIT}）。
 * 單元測試（mock mapper）無法證明 SQL 的 {@code WHERE series_id IS NULL} 在併發下真的只讓一個寫入生效，
 * 只有真實 DB 能。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleFacade 條件式 series 指派／移出整合測試")
class ArticleSeriesConditionalAssignmentIT {

    /** 併發案例重複的輪數 */
    private static final int ROUNDS = 25;

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
    @Autowired private ArticleRepository articleRepo;
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
        jdbc.execute("UPDATE articles SET series_id = NULL, series_position = NULL");
        jdbc.execute("DELETE FROM series");
        jdbc.execute("DELETE FROM articles");
    }

    /** 以 JDBC 直接建立 series（不依賴 series 實體的欄位名稱，使本測試在 M4-b 前後都能編譯）。 */
    private UUID insertSeries(String slug) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO series (uuid, title, slug, author_id, article_count) VALUES (?, ?, ?, ?, 0)",
                uuid, "T-" + slug, slug, SeededUsers.USER1_ID);
        return uuid;
    }

    private Article insertArticle() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(SeededUsers.USER1_ID);
        article.setTitle("Cond Article " + UUID.randomUUID());
        article.setSlug("cond-article-" + UUID.randomUUID());
        article.setContent("content");
        article.setContentHtml("<p>content</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    private Long seriesIdOf(UUID articleUuid) {
        return jdbc.queryForObject("SELECT series_id FROM articles WHERE uuid = ?", Long.class, articleUuid);
    }

    private Integer positionOf(UUID articleUuid) {
        return jdbc.queryForObject("SELECT series_position FROM articles WHERE uuid = ?", Integer.class, articleUuid);
    }

    private long versionOf(UUID articleUuid) {
        return jdbc.queryForObject("SELECT version FROM articles WHERE uuid = ?", Long.class, articleUuid);
    }

    private Long idOfSeries(UUID seriesUuid) {
        return jdbc.queryForObject("SELECT id FROM series WHERE uuid = ?", Long.class, seriesUuid);
    }

    // ─── assignToSeriesIfUnassigned ────────────────────────────────────────

    @Test
    @DisplayName("文章不屬於任何 series → 回傳 true，寫入 series 與位置，並遞增樂觀鎖 version")
    void assign_whenUnassigned_returnsTrueAndWrites() {
        UUID series = insertSeries("cond-assign");
        Article article = insertArticle();
        long versionBefore = versionOf(article.getUuid());

        boolean assigned = articleFacade.assignToSeriesIfUnassigned(article.getUuid(), series, 4);

        assertThat(assigned).isTrue();
        assertThat(seriesIdOf(article.getUuid())).isEqualTo(idOfSeries(series));
        assertThat(positionOf(article.getUuid())).isEqualTo(4);
        assertThat(versionOf(article.getUuid())).as("與實體 save 一致：遞增 version").isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName("文章已屬於同一個 series → 回傳 false，位置與 version 都不動（呼叫端改走只更新位置）")
    void assign_whenAlreadyInSameSeries_returnsFalseAndLeavesRowUntouched() {
        UUID series = insertSeries("cond-same");
        Article article = insertArticle();
        assertThat(articleFacade.assignToSeriesIfUnassigned(article.getUuid(), series, 1)).isTrue();
        long versionAfterFirst = versionOf(article.getUuid());

        boolean second = articleFacade.assignToSeriesIfUnassigned(article.getUuid(), series, 9);

        assertThat(second).isFalse();
        assertThat(positionOf(article.getUuid())).isEqualTo(1);
        assertThat(versionOf(article.getUuid())).isEqualTo(versionAfterFirst);
    }

    @Test
    @DisplayName("文章已屬於另一個 series → 回傳 false，歸屬不被覆寫")
    void assign_whenInOtherSeries_returnsFalseAndKeepsOriginalMembership() {
        UUID first = insertSeries("cond-first");
        UUID other = insertSeries("cond-other");
        Article article = insertArticle();
        assertThat(articleFacade.assignToSeriesIfUnassigned(article.getUuid(), first, 1)).isTrue();

        boolean assigned = articleFacade.assignToSeriesIfUnassigned(article.getUuid(), other, 2);

        assertThat(assigned).isFalse();
        assertThat(seriesIdOf(article.getUuid())).isEqualTo(idOfSeries(first));
        assertThat(positionOf(article.getUuid())).isEqualTo(1);
    }

    @Test
    @DisplayName("文章不存在 → 回傳 false；series 不存在 → IllegalArgumentException 且文章不被改動")
    void assign_unknownArticle_false_unknownSeries_throws() {
        UUID series = insertSeries("cond-unknown");
        Article article = insertArticle();

        assertThat(articleFacade.assignToSeriesIfUnassigned(UUID.randomUUID(), series, 1)).isFalse();
        assertThatThrownBy(() -> articleFacade.assignToSeriesIfUnassigned(article.getUuid(), UUID.randomUUID(), 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(seriesIdOf(article.getUuid())).isNull();
    }

    @Test
    @DisplayName("兩條執行緒同時把同一篇文章指派到同一 series（重複多輪）→ 恰好一個得到 true")
    void assign_concurrently_exactlyOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID series = insertSeries("cond-race-assign-" + round);
            Article article = insertArticle();

            List<Boolean> results = runConcurrently(i ->
                    articleFacade.assignToSeriesIfUnassigned(article.getUuid(), series, i + 1));

            assertThat(results).as("第 %d 輪", round).containsExactlyInAnyOrder(true, false);
            assertThat(seriesIdOf(article.getUuid())).as("第 %d 輪", round).isEqualTo(idOfSeries(series));
        }
    }

    // ─── removeFromSeriesIfAssigned ────────────────────────────────────────

    @Test
    @DisplayName("文章屬於指定 series → 回傳 true，series 與位置清空，version 遞增")
    void remove_whenAssigned_returnsTrueAndClears() {
        UUID series = insertSeries("cond-remove");
        Article article = insertArticle();
        assertThat(articleFacade.assignToSeriesIfUnassigned(article.getUuid(), series, 2)).isTrue();
        long versionBefore = versionOf(article.getUuid());

        boolean removed = articleFacade.removeFromSeriesIfAssigned(article.getUuid(), series);

        assertThat(removed).isTrue();
        assertThat(seriesIdOf(article.getUuid())).isNull();
        assertThat(positionOf(article.getUuid())).isNull();
        assertThat(versionOf(article.getUuid())).isEqualTo(versionBefore + 1);
    }

    @Test
    @DisplayName("文章屬於另一個 series、未歸屬、文章不存在或 series 不存在 → 都回傳 false 且不改動")
    void remove_whenNotInThatSeries_returnsFalseAndLeavesRowUntouched() {
        UUID member = insertSeries("cond-member");
        UUID other = insertSeries("cond-not-member");
        Article inMember = insertArticle();
        Article free = insertArticle();
        assertThat(articleFacade.assignToSeriesIfUnassigned(inMember.getUuid(), member, 1)).isTrue();

        assertThat(articleFacade.removeFromSeriesIfAssigned(inMember.getUuid(), other)).isFalse();
        assertThat(articleFacade.removeFromSeriesIfAssigned(free.getUuid(), member)).isFalse();
        assertThat(articleFacade.removeFromSeriesIfAssigned(UUID.randomUUID(), member)).isFalse();
        assertThat(articleFacade.removeFromSeriesIfAssigned(inMember.getUuid(), UUID.randomUUID())).isFalse();

        assertThat(seriesIdOf(inMember.getUuid())).isEqualTo(idOfSeries(member));
        assertThat(positionOf(inMember.getUuid())).isEqualTo(1);
    }

    @Test
    @DisplayName("兩條執行緒同時把同一篇文章移出同一 series（重複多輪）→ 恰好一個得到 true")
    void remove_concurrently_exactlyOneWinner() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            UUID series = insertSeries("cond-race-remove-" + round);
            Article article = insertArticle();
            assertThat(articleFacade.assignToSeriesIfUnassigned(article.getUuid(), series, 1)).isTrue();

            List<Boolean> results = runConcurrently(i ->
                    articleFacade.removeFromSeriesIfAssigned(article.getUuid(), series));

            assertThat(results).as("第 %d 輪", round).containsExactlyInAnyOrder(true, false);
            assertThat(seriesIdOf(article.getUuid())).as("第 %d 輪", round).isNull();
        }
    }

    /** 讓兩個執行緒同時執行 action（各傳入自己的 index），回傳兩者的結果。 */
    private List<Boolean> runConcurrently(java.util.function.IntPredicate action) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            List<Future<Boolean>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return action.test(index);
                }));
            }
            List<Boolean> results = new ArrayList<>();
            for (Future<Boolean> f : futures) {
                results.add(f.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
