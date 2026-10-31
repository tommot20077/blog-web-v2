package dowob.xyz.blog.module.series.integration;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleContentData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.series.config.SeriesTestApplication;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 整合測試：在真實 DB 與<b>真實的</b> ArticleFacade / SeriesFacade bean 上，
 * 驗證 {@code ArticleData.authorUuid / seriesUuid} 與資料表一致，
 * 並以 Spring context 能啟動證明 {@code SeriesFacadeImpl → ArticleFacade → … → SeriesFacade} 沒有 bean 循環。
 *
 * <p>本類別放在 series 模組，因為 {@link SeriesTestApplication} 同時掃描 article 與 series 模組，
 * 是唯一同時具備兩個真實 facade 實作的測試環境。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleData UUID 欄位整合測試")
class TransitionUuidApiIT {

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
    @Autowired private ArticleLookupFacade articleLookupFacade;
    @Autowired private SeriesFacade seriesFacade;
    @Autowired private ArticleRepository articleRepo;
    @Autowired private SeriesRepository seriesRepo;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private FileFacade fileFacade;

    /** series 測試種子使用者（R__series_test_seed.sql） */
    private static final Long AUTHOR_1 = 1L;
    private static final Long AUTHOR_2 = 2L;

    @BeforeEach
    void setUp() {
        cleanUp();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("DELETE FROM processed_events");
        articleRepo.findAll().forEach(a -> {
            a.setSeriesUuid(null);
            a.setSeriesPosition(null);
            articleRepo.save(a);
        });
        seriesRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private UUID userUuid(Long userId) {
        return jdbcTemplate.queryForObject("SELECT uuid FROM users WHERE id = ?", UUID.class, userId);
    }

    private Article createArticle(Long authorId, UUID seriesUuid, Integer position) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorUuid(userUuid(authorId));
        article.setTitle("Transition IT " + UUID.randomUUID());
        article.setSlug("transition-it-" + UUID.randomUUID());
        article.setContent("content");
        article.setContentHtml("<p>content</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setSeriesUuid(seriesUuid);
        article.setSeriesPosition(position);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    private Series createSeries(String slug, Long authorId) {
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("T " + slug);
        series.setSlug(slug);
        series.setDescription("d");
        series.setAuthorUuid(userUuid(authorId));
        series.setArticleCount(0);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        return seriesRepo.save(series);
    }

    @Test
    @DisplayName("Spring context 啟動成功 → ArticleFacade 與 SeriesFacade 皆為真實 bean（無 bean 循環）")
    void contextStarts_bothRealFacadesWired() {
        assertThat(articleFacade).isNotNull();
        assertThat(seriesFacade).isNotNull();
        assertThat(articleFacade.getClass().getSimpleName()).isEqualTo("ArticleFacadeImpl");
        assertThat(seriesFacade.getClass().getSimpleName()).isEqualTo("SeriesFacadeImpl");
    }

    @Test
    @DisplayName("ArticleFacade.findByUuid → authorUuid 等於 users.uuid、seriesUuid 等於 series.uuid")
    void articleFacadeSingle_uuidFieldsMatchTables() {
        Series series = createSeries("it-single", AUTHOR_1);
        Article article = createArticle(AUTHOR_1, series.getUuid(), 1);

        ArticleData byUuid = articleFacade.findByUuid(article.getUuid()).orElseThrow();

        assertThat(byUuid.uuid()).isEqualTo(article.getUuid());
        assertThat(byUuid.authorUuid()).isEqualTo(userUuid(AUTHOR_1));
        assertThat(byUuid.seriesUuid()).isEqualTo(series.getUuid());
    }

    @Test
    @DisplayName("ArticleFacade.findByUuid → 多作者多系列逐篇正確；無系列者 seriesUuid 為 null")
    void articleFacadeMultiple_uuidFieldsMatchTables() {
        Series s1 = createSeries("it-batch-1", AUTHOR_1);
        Series s2 = createSeries("it-batch-2", AUTHOR_2);
        Article a1 = createArticle(AUTHOR_1, s1.getUuid(), 1);
        Article a2 = createArticle(AUTHOR_2, s2.getUuid(), 1);
        Article a3 = createArticle(AUTHOR_2, null, null);

        Map<UUID, ArticleData> byUuid = Stream.of(a1, a2, a3)
                .map(a -> articleFacade.findByUuid(a.getUuid()).orElseThrow())
                .collect(Collectors.toMap(ArticleData::uuid, d -> d));
        assertThat(byUuid.get(a1.getUuid()).authorUuid()).isEqualTo(userUuid(AUTHOR_1));
        assertThat(byUuid.get(a1.getUuid()).seriesUuid()).isEqualTo(s1.getUuid());
        assertThat(byUuid.get(a2.getUuid()).authorUuid()).isEqualTo(userUuid(AUTHOR_2));
        assertThat(byUuid.get(a2.getUuid()).seriesUuid()).isEqualTo(s2.getUuid());
        assertThat(byUuid.get(a3.getUuid()).authorUuid()).isEqualTo(userUuid(AUTHOR_2));
        assertThat(byUuid.get(a3.getUuid()).seriesUuid()).isNull();
    }

    @Test
    @DisplayName("ArticleFacade.findBySeriesUuidOrderByPosition → 每篇 seriesUuid 等於該 series.uuid")
    void articleFacadeBySeries_seriesUuidMatches() {
        Series series = createSeries("it-by-series", AUTHOR_1);
        createArticle(AUTHOR_1, series.getUuid(), 1);
        createArticle(AUTHOR_1, series.getUuid(), 2);

        List<ArticleData> result = articleFacade.findBySeriesUuidOrderByPosition(series.getUuid());

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ArticleData::seriesUuid).containsOnly(series.getUuid());
        assertThat(result).extracting(ArticleData::authorUuid).containsOnly(userUuid(AUTHOR_1));
    }

    @Test
    @DisplayName("ArticleLookupFacade.findByUuid（file 模組用的最小依賴 bean）→ 同樣帶 authorUuid / seriesUuid")
    void articleLookupFacade_uuidFieldsMatchTables() {
        Series series = createSeries("it-lookup", AUTHOR_1);
        Article article = createArticle(AUTHOR_1, series.getUuid(), 1);

        ArticleData data = articleLookupFacade.findByUuid(article.getUuid()).orElseThrow();

        assertThat(data.authorUuid()).isEqualTo(userUuid(AUTHOR_1));
        assertThat(data.seriesUuid()).isEqualTo(series.getUuid());
    }

    @Test
    @DisplayName("ArticleFacade.findContentByUuid → authorUuid 等於 users.uuid")
    void articleFacadeContent_authorUuidMatches() {
        Article article = createArticle(AUTHOR_2, null, null);

        ArticleContentData data = articleFacade.findContentByUuid(article.getUuid()).orElseThrow();

        assertThat(data.authorUuid()).isEqualTo(userUuid(AUTHOR_2));
    }
}
