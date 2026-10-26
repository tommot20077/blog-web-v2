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

/**
 * {@link ArticleFacade} series UUID 版方法對真實 PostgreSQL 的整合測試（M4-a 建立，M6-b 改為直查 {@code series_uuid}）。
 *
 * <p>M6-b 之前本測試把 UUID 版與 Long 版的結果對照（parity）；Long 版隨 {@code articles.series_id} 一併移除後，
 * 改以<b>明確的預期值</b>斷言（含 series_position 順序、count 只含 &gt; 0、prev／next 的邊界與位置落差、
 * 未知 series、寫入行為）。放在 series 模組，因為 {@link SeriesTestApplication} 同時載入 article 與 series 的
 * 真實實作（{@link SeriesFacade#findIdsByUuids} 的真 SQL 也在這裡驗證）。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleFacade series UUID 版整合測試")
class ArticleSeriesUuidFacadeIT {

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
        a4 = createArticle(seriesA.getUuid(), 4, ArticleStatus.PUBLISHED);
        a9 = createArticle(seriesA.getUuid(), 9, ArticleStatus.PUBLISHED);
        a1 = createArticle(seriesA.getUuid(), 1, ArticleStatus.PUBLISHED);
        a2Draft = createArticle(seriesA.getUuid(), 2, ArticleStatus.DRAFT);
        createArticle(seriesB.getUuid(), 1, ArticleStatus.DRAFT);
        createArticle(seriesD.getUuid(), 1, ArticleStatus.PUBLISHED);
        createArticle(seriesD.getUuid(), 2, ArticleStatus.PUBLISHED);
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

    private Article createArticle(UUID seriesUuid, Integer position, ArticleStatus status) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorUuid(SeededUsers.uuidOf(AUTHOR_1));
        article.setTitle("Parity IT " + UUID.randomUUID());
        article.setSlug("parity-it-" + UUID.randomUUID());
        article.setContent("content");
        article.setContentHtml("<p>content</p>");
        article.setStatus(status);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setSeriesUuid(seriesUuid);
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
    @DisplayName("findBySeriesUuidOrderByPosition → 含 DRAFT、依位置排序 1,2,4,9，每篇都帶出 seriesUuid")
    void findBySeriesUuid_returnsAllStatusesInPositionOrder() {
        List<ArticleData> byUuid = articleFacade.findBySeriesUuidOrderByPosition(seriesA.getUuid());

        assertThat(byUuid).extracting(ArticleData::seriesPosition).containsExactly(1, 2, 4, 9);
        assertThat(byUuid).extracting(ArticleData::id)
                .containsExactly(a1.getId(), a2Draft.getId(), a4.getId(), a9.getId());
        assertThat(byUuid).extracting(ArticleData::seriesUuid).containsOnly(seriesA.getUuid());
        assertThat(byUuid).extracting(ArticleData::status)
                .containsExactly("PUBLISHED", "DRAFT", "PUBLISHED", "PUBLISHED");
    }

    @Test
    @DisplayName("findBySeriesUuidOrderByPosition → 空 series、未知 series 與 null 皆為空清單")
    void findBySeriesUuid_emptyUnknownAndNull_areEmpty() {
        assertThat(articleFacade.findBySeriesUuidOrderByPosition(seriesC.getUuid())).isEmpty();
        assertThat(articleFacade.findBySeriesUuidOrderByPosition(UUID.randomUUID())).isEmpty();
        assertThat(articleFacade.findBySeriesUuidOrderByPosition(null)).isEmpty();
    }

    // ─── countPublishedBySeriesUuids ───

    @Test
    @DisplayName("countPublishedBySeriesUuids → A=3、D=2 各自配對正確；B（僅 DRAFT）、C（空）、未知皆不出現")
    void countPublished_onlyPositive_pairedWithCorrectUuid() {
        Map<UUID, Integer> byUuid = articleFacade.countPublishedBySeriesUuids(List.of(
                seriesA.getUuid(), seriesB.getUuid(), seriesC.getUuid(), seriesD.getUuid(), UUID.randomUUID()));

        assertThat(byUuid).isEqualTo(Map.of(seriesA.getUuid(), 3, seriesD.getUuid(), 2));
    }

    @Test
    @DisplayName("countPublishedBySeriesUuids → 空輸入與 null 回空 Map")
    void countPublished_emptyInput_returnsEmpty() {
        assertThat(articleFacade.countPublishedBySeriesUuids(List.of())).isEmpty();
        assertThat(articleFacade.countPublishedBySeriesUuids(null)).isEmpty();
    }

    // ─── prev / next ───

    @Test
    @DisplayName("findPrev/NextPublishedInSeries → 位置 0..10 逐一符合預期（含首尾邊界、DRAFT 被跳過、位置落差）")
    void prevNext_atEveryPosition_matchExpected() {
        // 已發布的位置 → 文章 uuid（位置 2 是 DRAFT，必須被跳過）
        Map<Integer, UUID> published = Map.of(1, a1.getUuid(), 4, a4.getUuid(), 9, a9.getUuid());
        for (int position = 0; position <= 10; position++) {
            final int current = position;
            UUID expectedPrev = published.entrySet().stream()
                    .filter(e -> e.getKey() < current)
                    .max(Map.Entry.comparingByKey()).map(Map.Entry::getValue).orElse(null);
            UUID expectedNext = published.entrySet().stream()
                    .filter(e -> e.getKey() > current)
                    .min(Map.Entry.comparingByKey()).map(Map.Entry::getValue).orElse(null);

            assertThat(articleFacade.findPrevPublishedInSeries(seriesA.getUuid(), position))
                    .as("prev @%d", position).map(ArticleNavRef::uuid).isEqualTo(java.util.Optional.ofNullable(expectedPrev));
            assertThat(articleFacade.findNextPublishedInSeries(seriesA.getUuid(), position))
                    .as("next @%d", position).map(ArticleNavRef::uuid).isEqualTo(java.util.Optional.ofNullable(expectedNext));
        }
        // 明確的預期值（防止預期值的推導與實作同時錯）：第一篇（1）沒有 prev；DRAFT(2) 被跳過所以 1 的 next 是 4；最後一篇（9）沒有 next
        assertThat(articleFacade.findPrevPublishedInSeries(seriesA.getUuid(), 1)).isEmpty();
        assertThat(articleFacade.findNextPublishedInSeries(seriesA.getUuid(), 1))
                .map(ArticleNavRef::uuid).contains(a4.getUuid());
        assertThat(articleFacade.findPrevPublishedInSeries(seriesA.getUuid(), 4))
                .map(ArticleNavRef::uuid).contains(a1.getUuid());
        assertThat(articleFacade.findNextPublishedInSeries(seriesA.getUuid(), 9)).isEmpty();
    }

    @Test
    @DisplayName("findPrev/NextPublishedInSeries → 未知 series 與 null 皆為 empty")
    void prevNext_unknownSeriesAndNull_areEmpty() {
        UUID unknown = UUID.randomUUID();
        assertThat(articleFacade.findPrevPublishedInSeries(unknown, 5)).isEmpty();
        assertThat(articleFacade.findNextPublishedInSeries(unknown, 5)).isEmpty();
        assertThat(articleFacade.findPrevPublishedInSeries((UUID) null, 5)).isEmpty();
        assertThat(articleFacade.findNextPublishedInSeries((UUID) null, 5)).isEmpty();
    }

    // ─── updateSeriesAssignment ───

    @Test
    @DisplayName("updateSeriesAssignment(UUID…) → 加入 series 與移出 series，seriesUuid／position 正確寫入與清空")
    void updateSeriesAssignment_addAndRemove() {
        Article article = createArticle(null, null, ArticleStatus.PUBLISHED);

        articleFacade.updateSeriesAssignment(article.getUuid(), seriesC.getUuid(), 3);

        ArticleData afterAdd = articleFacade.findById(article.getId()).orElseThrow();
        assertThat(afterAdd.seriesUuid()).isEqualTo(seriesC.getUuid());
        assertThat(afterAdd.seriesPosition()).isEqualTo(3);

        articleFacade.updateSeriesAssignment(article.getUuid(), null, null);

        ArticleData afterRemove = articleFacade.findById(article.getId()).orElseThrow();
        assertThat(afterRemove.seriesUuid()).isNull();
        assertThat(afterRemove.seriesPosition()).isNull();
    }

    @Test
    @DisplayName("updateSeriesAssignment(UUID…) → 文章不存在為 no-op（即使 series 也不存在）")
    void updateSeriesAssignment_unknownArticle_isNoOp() {
        long before = articleRepo.count();

        articleFacade.updateSeriesAssignment(UUID.randomUUID(), seriesC.getUuid(), 1);
        articleFacade.updateSeriesAssignment(UUID.randomUUID(), UUID.randomUUID(), 1);

        assertThat(articleRepo.count()).isEqualTo(before);
        assertThat(articleFacade.findBySeriesUuidOrderByPosition(seriesC.getUuid())).isEmpty();
    }

    @Test
    @DisplayName("updateSeriesAssignment(UUID…) → article 不檢查 series 是否存在（M6-b 起沒有 FK、也不依賴 series），"
            + "存在性由呼叫端 SeriesService 負責；不存在的 series uuid 照寫，之後由刪除事件或每日校正清空")
    void updateSeriesAssignment_unknownSeries_isWrittenAsIs() {
        Article target = createArticle(seriesA.getUuid(), 20, ArticleStatus.PUBLISHED);
        UUID ghostSeries = UUID.randomUUID();

        articleFacade.updateSeriesAssignment(target.getUuid(), ghostSeries, 1);

        ArticleData written = articleFacade.findById(target.getId()).orElseThrow();
        assertThat(written.seriesUuid()).isEqualTo(ghostSeries);
        assertThat(written.seriesPosition()).isEqualTo(1);
    }
}
