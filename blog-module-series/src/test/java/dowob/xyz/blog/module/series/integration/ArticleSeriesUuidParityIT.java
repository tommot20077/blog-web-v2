package dowob.xyz.blog.module.series.integration;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleNavRef;
import dowob.xyz.blog.infrastructure.persistence.BatchedQuery;
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
import org.springframework.dao.DataIntegrityViolationException;
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
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * M4-a：{@link ArticleFacade} series UUID 版方法與 Long 版的結果對照整合測試（真 PostgreSQL、真實 facade）。
 *
 * <p>以同一批資料分別呼叫 UUID 版與 Long 版，斷言結果相同（含 series_position 順序、
 * count 只含 &gt; 0、prev／next 的邊界與位置落差、未知 series、寫入行為）。
 * 放在 series 模組，因為 {@link SeriesTestApplication} 同時載入 article 與 series 的真實 facade 實作
 * （UUID 版需要 {@link SeriesFacade} 的真實轉換）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleFacade series UUID 版 vs Long 版對照整合測試")
class ArticleSeriesUuidParityIT {

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

    /** 有 PUBLISHED(1)、DRAFT(2)、PUBLISHED(4)、PUBLISHED(9) 的 series（刻意亂序寫入、位置有落差） */
    private Series seriesA;
    /** 只有 DRAFT 文章的 series（count = 0） */
    private Series seriesB;
    /** 沒有任何文章的 series */
    private Series seriesC;
    /** 有 2 篇 PUBLISHED 的 series（與 A 的 count 不同，用來驗證 count 配對到正確的 uuid） */
    private Series seriesD;
    private Article a1;
    private Article a2Draft;
    private Article a4;
    private Article a9;

    @BeforeEach
    void setUp() {
        cleanUp();
        seriesA = createSeries("parity-a");
        seriesB = createSeries("parity-b");
        seriesC = createSeries("parity-c");
        seriesD = createSeries("parity-d");
        a4 = createArticle(seriesA.getId(), 4, ArticleStatus.PUBLISHED);
        a9 = createArticle(seriesA.getId(), 9, ArticleStatus.PUBLISHED);
        a1 = createArticle(seriesA.getId(), 1, ArticleStatus.PUBLISHED);
        a2Draft = createArticle(seriesA.getId(), 2, ArticleStatus.DRAFT);
        createArticle(seriesB.getId(), 1, ArticleStatus.DRAFT);
        createArticle(seriesD.getId(), 1, ArticleStatus.PUBLISHED);
        createArticle(seriesD.getId(), 2, ArticleStatus.PUBLISHED);
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

    private Article createArticle(Long seriesId, Integer position, ArticleStatus status) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(AUTHOR_1);
        article.setTitle("Parity IT " + UUID.randomUUID());
        article.setSlug("parity-it-" + UUID.randomUUID());
        article.setContent("content");
        article.setContentHtml("<p>content</p>");
        article.setStatus(status);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setSeriesId(seriesId);
        article.setSeriesPosition(position);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    private Series createSeries(String slug) {
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("T " + slug);
        series.setSlug(slug);
        series.setDescription("d");
        series.setAuthorUuid(SeededUsers.uuidOf(AUTHOR_1));
        series.setArticleCount(0);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        return seriesRepo.save(series);
    }

    // ─── SeriesFacade.findIdsByUuids（真 SQL）───

    @Test
    @DisplayName("SeriesFacade.findIdsByUuids → 回傳存在者的 uuid → id；未知 uuid 不出現；空輸入回空 Map")
    void findIdsByUuids_returnsExistingOnly() {
        Map<UUID, Long> result = seriesFacade.findIdsByUuids(
                List.of(seriesA.getUuid(), seriesC.getUuid(), UUID.randomUUID()));

        assertThat(result).containsOnlyKeys(seriesA.getUuid(), seriesC.getUuid());
        assertThat(result.get(seriesA.getUuid())).isEqualTo(seriesA.getId());
        assertThat(result.get(seriesC.getUuid())).isEqualTo(seriesC.getId());
        assertThat(seriesFacade.findIdsByUuids(List.of())).isEmpty();
        assertThat(seriesFacade.findIdsByUuids(null)).isEmpty();
    }

    @Test
    @DisplayName("SeriesFacade.findIdsByUuids → 輸入超過批次大小時切批，存在者全數回傳")
    void findIdsByUuids_overBatchSize_returnsAllExisting() {
        List<UUID> input = new ArrayList<>();
        for (int i = 0; i < BatchedQuery.BATCH_SIZE * 2 + 50; i++) {
            input.add(UUID.randomUUID());
        }
        input.set(0, seriesA.getUuid());
        input.set(BatchedQuery.BATCH_SIZE + 5, seriesB.getUuid());
        input.set(input.size() - 1, seriesC.getUuid());

        Map<UUID, Long> result = seriesFacade.findIdsByUuids(input);

        assertThat(result).containsOnlyKeys(seriesA.getUuid(), seriesB.getUuid(), seriesC.getUuid());
        assertThat(result.get(seriesB.getUuid())).isEqualTo(seriesB.getId());
    }

    // ─── findBySeriesUuidOrderByPosition ───

    @Test
    @DisplayName("findBySeriesUuidOrderByPosition → 與 Long 版完全相同（含順序與 DRAFT），位置為 1,2,4,9")
    void findBySeriesUuid_matchesLongVersion() {
        List<ArticleData> byUuid = articleFacade.findBySeriesUuidOrderByPosition(seriesA.getUuid());
        List<ArticleData> byId = articleFacade.findBySeriesIdOrderByPosition(seriesA.getId());

        assertThat(byUuid).isEqualTo(byId);
        assertThat(byUuid).extracting(ArticleData::seriesPosition).containsExactly(1, 2, 4, 9);
        assertThat(byUuid).extracting(ArticleData::id)
                .containsExactly(a1.getId(), a2Draft.getId(), a4.getId(), a9.getId());
    }

    @Test
    @DisplayName("findBySeriesUuidOrderByPosition → 空 series 與未知 series 皆為空清單，與 Long 版一致")
    void findBySeriesUuid_emptyAndUnknown_matchLongVersion() {
        assertThat(articleFacade.findBySeriesUuidOrderByPosition(seriesC.getUuid()))
                .isEqualTo(articleFacade.findBySeriesIdOrderByPosition(seriesC.getId()))
                .isEmpty();
        assertThat(articleFacade.findBySeriesUuidOrderByPosition(UUID.randomUUID()))
                .isEqualTo(articleFacade.findBySeriesIdOrderByPosition(Long.MAX_VALUE))
                .isEmpty();
        assertThat(articleFacade.findBySeriesUuidOrderByPosition(null)).isEmpty();
    }

    // ─── countPublishedBySeriesUuids ───

    @Test
    @DisplayName("countPublishedBySeriesUuids → A=3、D=2 各自配對正確，且與 Long 版相同；B（僅 DRAFT）、C（空）、未知皆不出現")
    void countPublished_matchesLongVersion_onlyPositive() {
        Map<UUID, Integer> byUuid = articleFacade.countPublishedBySeriesUuids(List.of(
                seriesA.getUuid(), seriesB.getUuid(), seriesC.getUuid(), seriesD.getUuid(), UUID.randomUUID()));
        Map<Long, Integer> byId = articleFacade.countPublishedBySeriesIds(List.of(
                seriesA.getId(), seriesB.getId(), seriesC.getId(), seriesD.getId(), Long.MAX_VALUE));

        assertThat(byId).isEqualTo(Map.of(seriesA.getId(), 3, seriesD.getId(), 2));
        assertThat(byUuid).isEqualTo(Map.of(seriesA.getUuid(), 3, seriesD.getUuid(), 2));
    }

    @Test
    @DisplayName("countPublishedBySeriesUuids → 空輸入回空 Map，與 Long 版一致")
    void countPublished_emptyInput_matchesLongVersion() {
        assertThat(articleFacade.countPublishedBySeriesUuids(List.of()))
                .isEqualTo(articleFacade.countPublishedBySeriesIds(List.of()))
                .isEmpty();
        assertThat(articleFacade.countPublishedBySeriesUuids(null)).isEmpty();
    }

    // ─── prev / next ───

    @Test
    @DisplayName("findPrev/NextPublishedInSeries → 位置 0..10 逐一與 Long 版相同（含首尾邊界、DRAFT 被跳過、位置落差）")
    void prevNext_matchLongVersionAtEveryPosition() {
        for (int position = 0; position <= 10; position++) {
            assertThat(articleFacade.findPrevPublishedInSeries(seriesA.getUuid(), position))
                    .as("prev @%d", position)
                    .isEqualTo(articleFacade.findPrevPublishedInSeries(seriesA.getId(), position));
            assertThat(articleFacade.findNextPublishedInSeries(seriesA.getUuid(), position))
                    .as("next @%d", position)
                    .isEqualTo(articleFacade.findNextPublishedInSeries(seriesA.getId(), position));
        }
        // 明確的預期值（防止兩邊同時錯）：第一篇（1）沒有 prev；DRAFT(2) 被跳過所以 1 的 next 是 4；最後一篇（9）沒有 next
        assertThat(articleFacade.findPrevPublishedInSeries(seriesA.getUuid(), 1)).isEmpty();
        assertThat(articleFacade.findNextPublishedInSeries(seriesA.getUuid(), 1))
                .map(ArticleNavRef::uuid).contains(a4.getUuid());
        assertThat(articleFacade.findPrevPublishedInSeries(seriesA.getUuid(), 4))
                .map(ArticleNavRef::uuid).contains(a1.getUuid());
        assertThat(articleFacade.findNextPublishedInSeries(seriesA.getUuid(), 9)).isEmpty();
    }

    @Test
    @DisplayName("findPrev/NextPublishedInSeries → 未知 series 與 null 皆為 empty，與 Long 版一致")
    void prevNext_unknownSeries_matchLongVersion() {
        UUID unknown = UUID.randomUUID();
        assertThat(articleFacade.findPrevPublishedInSeries(unknown, 5))
                .isEqualTo(articleFacade.findPrevPublishedInSeries(Long.MAX_VALUE, 5))
                .isEmpty();
        assertThat(articleFacade.findNextPublishedInSeries(unknown, 5))
                .isEqualTo(articleFacade.findNextPublishedInSeries(Long.MAX_VALUE, 5))
                .isEmpty();
        assertThat(articleFacade.findPrevPublishedInSeries((UUID) null, 5)).isEmpty();
        assertThat(articleFacade.findNextPublishedInSeries((UUID) null, 5)).isEmpty();
    }

    // ─── updateSeriesAssignment ───

    @Test
    @DisplayName("updateSeriesAssignment(UUID…) → 加入 series 與移出 series，結果與 Long 版對同形資料一致")
    void updateSeriesAssignment_addAndRemove_matchLongVersion() {
        Article byUuidArticle = createArticle(null, null, ArticleStatus.PUBLISHED);
        Article byIdArticle = createArticle(null, null, ArticleStatus.PUBLISHED);

        articleFacade.updateSeriesAssignment(byUuidArticle.getUuid(), seriesC.getUuid(), 3);
        articleFacade.updateSeriesAssignment(byIdArticle.getId(), seriesC.getId(), 3);

        ArticleData afterUuid = articleFacade.findById(byUuidArticle.getId()).orElseThrow();
        ArticleData afterId = articleFacade.findById(byIdArticle.getId()).orElseThrow();
        assertThat(afterUuid.seriesId()).isEqualTo(seriesC.getId()).isEqualTo(afterId.seriesId());
        assertThat(afterUuid.seriesUuid()).isEqualTo(seriesC.getUuid()).isEqualTo(afterId.seriesUuid());
        assertThat(afterUuid.seriesPosition()).isEqualTo(3).isEqualTo(afterId.seriesPosition());

        articleFacade.updateSeriesAssignment(byUuidArticle.getUuid(), null, null);
        articleFacade.updateSeriesAssignment(byIdArticle.getId(), null, null);

        ArticleData removedUuid = articleFacade.findById(byUuidArticle.getId()).orElseThrow();
        ArticleData removedId = articleFacade.findById(byIdArticle.getId()).orElseThrow();
        assertThat(removedUuid.seriesId()).isNull();
        assertThat(removedUuid.seriesUuid()).isNull();
        assertThat(removedUuid.seriesPosition()).isNull();
        assertThat(removedId.seriesId()).isNull();
        assertThat(removedId.seriesPosition()).isNull();
    }

    @Test
    @DisplayName("updateSeriesAssignment(UUID…) → 文章不存在為 no-op（即使 series 也不存在），與 Long 版一致")
    void updateSeriesAssignment_unknownArticle_isNoOpLikeLongVersion() {
        long before = articleRepo.count();

        articleFacade.updateSeriesAssignment(UUID.randomUUID(), seriesC.getUuid(), 1);
        articleFacade.updateSeriesAssignment(UUID.randomUUID(), UUID.randomUUID(), 1);
        articleFacade.updateSeriesAssignment(Long.MAX_VALUE, Long.MAX_VALUE, 1);

        assertThat(articleRepo.count()).isEqualTo(before);
        assertThat(articleFacade.findBySeriesUuidOrderByPosition(seriesC.getUuid())).isEmpty();
    }

    @Test
    @DisplayName("updateSeriesAssignment(UUID…) → series 不存在時於寫入前以 IllegalArgumentException 拒絕；"
            + "Long 版則被 FK 拒絕（Spring Data JDBC 包成 DbActionExecutionException，根因為 DataIntegrityViolationException）；兩者皆不改動文章")
    void updateSeriesAssignment_unknownSeries_isRejectedAndNothingChanges() {
        Article target = createArticle(seriesA.getId(), 20, ArticleStatus.PUBLISHED);

        assertThatThrownBy(() -> articleFacade.updateSeriesAssignment(target.getId(), Long.MAX_VALUE, 1))
                .hasCauseInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> articleFacade.updateSeriesAssignment(target.getUuid(), UUID.randomUUID(), 1))
                .isInstanceOf(IllegalArgumentException.class);

        ArticleData unchanged = articleFacade.findById(target.getId()).orElseThrow();
        assertThat(unchanged.seriesId()).isEqualTo(seriesA.getId());
        assertThat(unchanged.seriesPosition()).isEqualTo(20);
    }
}
