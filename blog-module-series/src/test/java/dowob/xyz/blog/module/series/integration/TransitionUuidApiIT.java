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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 過渡 API 整合測試（設計 §3.1）：在真實 DB 與<b>真實的</b> ArticleFacade / SeriesFacade bean 上，
 * 驗證 {@code ArticleData.authorUuid / seriesUuid} 與資料表一致、{@code SeriesFacade} 的 uuid ↔ id 轉換正確，
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
@DisplayName("過渡 API 整合測試 — ArticleData UUID 欄位與 SeriesFacade 轉換")
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
            a.setSeriesId(null);
            a.setSeriesPosition(null);
            articleRepo.save(a);
        });
        seriesRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private UUID userUuid(Long userId) {
        return jdbcTemplate.queryForObject("SELECT uuid FROM users WHERE id = ?", UUID.class, userId);
    }

    private Article createArticle(Long authorId, Long seriesId, Integer position) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle("Transition IT " + UUID.randomUUID());
        article.setSlug("transition-it-" + UUID.randomUUID());
        article.setContent("content");
        article.setContentHtml("<p>content</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setSeriesId(seriesId);
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
        series.setAuthorId(authorId);
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
    @DisplayName("ArticleFacade.findById / findByUuid → authorUuid 等於 users.uuid、seriesUuid 等於 series.uuid")
    void articleFacadeSingle_uuidFieldsMatchTables() {
        Series series = createSeries("it-single", AUTHOR_1);
        Article article = createArticle(AUTHOR_1, series.getId(), 1);

        ArticleData byId = articleFacade.findById(article.getId()).orElseThrow();
        ArticleData byUuid = articleFacade.findByUuid(article.getUuid()).orElseThrow();

        assertThat(byId.authorUuid()).isEqualTo(userUuid(AUTHOR_1));
        assertThat(byId.seriesUuid()).isEqualTo(series.getUuid());
        assertThat(byUuid).isEqualTo(byId);
    }

    @Test
    @DisplayName("ArticleFacade.findByIds → 多作者多系列的批次結果逐篇正確；無系列者 seriesUuid 為 null")
    void articleFacadeBatch_uuidFieldsMatchTables() {
        Series s1 = createSeries("it-batch-1", AUTHOR_1);
        Series s2 = createSeries("it-batch-2", AUTHOR_2);
        Article a1 = createArticle(AUTHOR_1, s1.getId(), 1);
        Article a2 = createArticle(AUTHOR_2, s2.getId(), 1);
        Article a3 = createArticle(AUTHOR_2, null, null);

        List<ArticleData> result = articleFacade.findByIds(List.of(a1.getId(), a2.getId(), a3.getId()));

        Map<Long, ArticleData> byId = result.stream()
                .collect(java.util.stream.Collectors.toMap(ArticleData::id, d -> d));
        assertThat(byId.get(a1.getId()).authorUuid()).isEqualTo(userUuid(AUTHOR_1));
        assertThat(byId.get(a1.getId()).seriesUuid()).isEqualTo(s1.getUuid());
        assertThat(byId.get(a2.getId()).authorUuid()).isEqualTo(userUuid(AUTHOR_2));
        assertThat(byId.get(a2.getId()).seriesUuid()).isEqualTo(s2.getUuid());
        assertThat(byId.get(a3.getId()).authorUuid()).isEqualTo(userUuid(AUTHOR_2));
        assertThat(byId.get(a3.getId()).seriesUuid()).isNull();
    }

    @Test
    @DisplayName("ArticleFacade.findBySeriesIdOrderByPosition → 每篇 seriesUuid 等於該 series.uuid")
    void articleFacadeBySeries_seriesUuidMatches() {
        Series series = createSeries("it-by-series", AUTHOR_1);
        createArticle(AUTHOR_1, series.getId(), 1);
        createArticle(AUTHOR_1, series.getId(), 2);

        List<ArticleData> result = articleFacade.findBySeriesIdOrderByPosition(series.getId());

        assertThat(result).hasSize(2);
        assertThat(result).extracting(ArticleData::seriesUuid).containsOnly(series.getUuid());
        assertThat(result).extracting(ArticleData::authorUuid).containsOnly(userUuid(AUTHOR_1));
    }

    @Test
    @DisplayName("ArticleLookupFacade.findByUuid（file 模組用的最小依賴 bean）→ 同樣帶 authorUuid / seriesUuid")
    void articleLookupFacade_uuidFieldsMatchTables() {
        Series series = createSeries("it-lookup", AUTHOR_1);
        Article article = createArticle(AUTHOR_1, series.getId(), 1);

        ArticleData data = articleLookupFacade.findByUuid(article.getUuid()).orElseThrow();

        assertThat(data.authorUuid()).isEqualTo(userUuid(AUTHOR_1));
        assertThat(data.seriesUuid()).isEqualTo(series.getUuid());
    }

    @Test
    @DisplayName("ArticleFacade.findContentById → authorUuid 等於 users.uuid")
    void articleFacadeContent_authorUuidMatches() {
        Article article = createArticle(AUTHOR_2, null, null);

        ArticleContentData data = articleFacade.findContentById(article.getId()).orElseThrow();

        assertThat(data.authorUuid()).isEqualTo(userUuid(AUTHOR_2));
    }

    @Test
    @DisplayName("SeriesFacade.findIdByUuid → 存在回傳主鍵；不存在回傳 empty")
    void seriesFacadeFindIdByUuid() {
        Series series = createSeries("it-find-id", AUTHOR_1);

        assertThat(seriesFacade.findIdByUuid(series.getUuid())).contains(series.getId());
        assertThat(seriesFacade.findIdByUuid(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("SeriesFacade.findUuidsByIds → 回傳存在者的 id → uuid；不存在者不出現")
    void seriesFacadeFindUuidsByIds() {
        Series s1 = createSeries("it-uuids-1", AUTHOR_1);
        Series s2 = createSeries("it-uuids-2", AUTHOR_2);

        Map<Long, UUID> result = seriesFacade.findUuidsByIds(List.of(s1.getId(), s2.getId(), Long.MAX_VALUE));

        assertThat(result).containsOnlyKeys(s1.getId(), s2.getId());
        assertThat(result.get(s1.getId())).isEqualTo(s1.getUuid());
        assertThat(result.get(s2.getId())).isEqualTo(s2.getUuid());
    }
}
