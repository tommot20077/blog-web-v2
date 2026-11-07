package dowob.xyz.blog.module.series.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.SeriesNavigation;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.infrastructure.event.SeriesDeletedEvent;
import dowob.xyz.blog.infrastructure.event.SeriesEventContract;
import dowob.xyz.blog.module.article.consumer.ArticleSeriesDeletedConsumer;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 模組整合測試：Article ↔ Series。
 *
 * <p>驗證以下端對端流程：</p>
 * <ol>
 *     <li>POST /series + 加文章 → GET /articles/{uuid} 回應含 seriesNav</li>
 *     <li>系列篇數為 article facade 的即時計數：文章刪除／下架後立即反映，不依賴 MQ consumer（DATA-10）</li>
 *     <li>DELETE /series/{uuid} → SeriesDeletedEvent → article consumer 把 articles.series_uuid 清空</li>
 * </ol>
 *
 * <p>SeriesTestApplication 已掃 article 模組（ArticleController 可用），
 * 但未掃 reading 模組，故 liked/bookmarked/progress 均為 null 或 false，這在此 IT 中 OK。</p>
 *
 * <p>定位：模組內整合測試（真 DB），跨模組 facade 以 mock 取代；跨模組接縫由 blog-start 的 E2E 覆蓋。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("模組整合測試 — Article ↔ Series")
class SeriesModuleIT {

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

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private SeriesRepository seriesRepo;
    @Autowired private ArticleRepository articleRepo;
    @Autowired private ArticleSeriesDeletedConsumer articleSeriesDeletedConsumer;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SeriesFacade seriesFacade;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;

    private static final Long AUTHOR_ID = 1L;

    @BeforeEach
    void setUp() {
        // 清理前次測試資料（先刪 processed_events，再解綁 series_uuid，最後刪 series/article）
        jdbcTemplate.execute("DELETE FROM processed_events");
        articleRepo.findAll().forEach(a -> {
            a.setSeriesUuid(null);
            a.setSeriesPosition(null);
            articleRepo.save(a);
        });
        seriesRepo.deleteAll();
        articleRepo.deleteAll();
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

    /**
     * 建立模擬認證的 RequestPostProcessor。
     */
    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(new AuthUser(SeededUsers.uuidOf(userId), role), null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    /** 直接透過 repository 建立一篇 PUBLISHED 測試文章。 */
    private Article createPublishedArticle(Long authorId) {
        return createArticle(authorId, ArticleStatus.PUBLISHED);
    }

    /** 直接透過 repository 建立一篇指定狀態的測試文章（給 nav 查詢的 DRAFT 跳過測試用）。 */
    private Article createArticle(Long authorId, ArticleStatus status) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorUuid(SeededUsers.uuidOf(authorId));
        article.setTitle("Series IT Article " + UUID.randomUUID());
        article.setSlug("series-it-" + UUID.randomUUID());
        article.setContent("test content");
        article.setContentHtml("<p>test content</p>");
        article.setStatus(status);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    /** 直接透過 repository 將文章加入 series 指定位置（繞過 API，因 API 僅允許 PUBLISHED 文章加入）。 */
    private Article assignToSeries(Article article, UUID seriesUuid, int position) {
        article.setSeriesUuid(seriesUuid);
        article.setSeriesPosition(position);
        return articleRepo.save(article);
    }

    /** 透過 repository 建立一個 Series（不入庫 via API）。 */
    private Series createSeries(String title, String slug, Long authorId) {
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle(title);
        series.setSlug(slug);
        series.setDescription("IT description");
        series.setAuthorUuid(SeededUsers.uuidOf(authorId));
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        return seriesRepo.save(series);
    }

    // ─── Test A: seriesNav 傳遞 ──────────────────────────────────────────────

    @Test
    @DisplayName("POST /series + add article → GET /articles/{uuid} 回應含 seriesNav")
    void seriesNav_propagatesAfterAddingArticle() throws Exception {
        // 1. 透過 API 建立 series
        Map<String, Object> seriesPayload = Map.of(
                "title", "Spring Boot 系列",
                "slug", "spring-boot-series-nav",
                "description", "IT 測試系列"
        );
        String seriesRespJson = mockMvc.perform(post("/api/v1/series")
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(seriesPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andReturn().getResponse().getContentAsString();

        // 解析 series uuid
        String seriesUuidStr = objectMapper.readTree(seriesRespJson).at("/data/uuid").asText();
        UUID seriesUuid = UUID.fromString(seriesUuidStr);

        // 2. 建立文章
        Article article = createPublishedArticle(AUTHOR_ID);
        UUID articleUuid = article.getUuid();

        // 3. PUT 將文章加入 series（position = 1）
        Map<String, Object> addPayload = Map.of("position", 1);
        mockMvc.perform(put("/api/v1/series/{seriesUuid}/articles/{articleUuid}", seriesUuid, articleUuid)
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        // 4. GET /articles/{uuid} 驗證 seriesNav 存在且正確
        mockMvc.perform(get("/api/v1/articles/{uuid}", articleUuid)
                        .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.seriesNav").exists())
                .andExpect(jsonPath("$.data.seriesNav.seriesUuid").value(seriesUuid.toString()))
                .andExpect(jsonPath("$.data.seriesNav.seriesTitle").value("Spring Boot 系列"))
                .andExpect(jsonPath("$.data.seriesNav.position").value(1));
    }

    // ─── B6：篇數即時計數 ────────────────────────────────────────────────────

    @Test
    @DisplayName("B6：系列含 2 篇已發布、1 篇草稿、1 篇下架 → 更新與詳情的 articleCount 都是 2；刪除／下架後立即反映，不必等 MQ")
    void articleCount_isLivePublishedCount_reflectsDeleteAndArchiveImmediately() throws Exception {
        Series series = createSeries("Live Count Series", "live-count-series-it", AUTHOR_ID);
        Article published1 = assignToSeries(createPublishedArticle(AUTHOR_ID), series.getUuid(), 1);
        Article published2 = assignToSeries(createPublishedArticle(AUTHOR_ID), series.getUuid(), 2);
        assignToSeries(createArticle(AUTHOR_ID, ArticleStatus.DRAFT), series.getUuid(), 3);
        assignToSeries(createArticle(AUTHOR_ID, ArticleStatus.ARCHIVED), series.getUuid(), 4);

        assertThat(updateSeriesArticleCount(series)).isEqualTo(2);
        assertThat(detailArticleCount(series.getSlug())).isEqualTo(2);

        /* 刪除一篇已發布文章：沒有任何 MQ consumer 介入，兩個端點立即少 1 */
        mockMvc.perform(delete("/api/v1/articles/{uuid}", published1.getUuid())
                        .with(asUser(AUTHOR_ID, Role.ADMIN)))
                .andExpect(status().isOk());
        assertThat(updateSeriesArticleCount(series)).isEqualTo(1);
        assertThat(detailArticleCount(series.getSlug())).isEqualTo(1);

        /* 下架另一篇：已發布數歸 0 */
        jdbcTemplate.update("UPDATE articles SET status = 'ARCHIVED' WHERE uuid = ?", published2.getUuid());
        assertThat(updateSeriesArticleCount(series)).isZero();
        assertThat(detailArticleCount(series.getSlug())).isZero();
    }

    private int updateSeriesArticleCount(Series series) throws Exception {
        String body = mockMvc.perform(put("/api/v1/series/{uuid}", series.getUuid())
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("description", "d" + UUID.randomUUID()))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/articleCount").asInt();
    }

    private int detailArticleCount(String slug) throws Exception {
        String body = mockMvc.perform(get("/api/v1/series/{slug}", slug))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).at("/data/articleCount").asInt();
    }

    // ─── Test C: DELETE series → SeriesDeletedEvent → article consumer 清空 articles.series_uuid ──

    @Test
    @DisplayName("DELETE /series/{uuid} → 發出 SeriesDeletedEvent；article consumer 處理後 articles.series_uuid 清空、文章留存")
    void deleteSeries_unlinksArticlesViaEventConsumer() throws Exception {
        // 1. 建立 series + 文章
        Series series = createSeries("Unlink Series", "unlink-series-it", AUTHOR_ID);
        Article article = createPublishedArticle(AUTHOR_ID);
        UUID articleUuid = article.getUuid();

        // 2. 將文章加入 series（直接寫 DB）
        article.setSeriesUuid(series.getUuid());
        article.setSeriesPosition(1);
        articleRepo.save(article);

        // 確認文章已關聯
        Article linked = articleRepo.findById(article.getId()).orElseThrow();
        assertThat(linked.getSeriesUuid()).isEqualTo(series.getUuid());

        // 3. DELETE /api/v1/series/{uuid}（series 所有人刪除）
        mockMvc.perform(delete("/api/v1/series/{uuid}", series.getUuid())
                        .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        // 4. series 已不存在
        assertThat(seriesRepo.findByUuid(series.getUuid())).isEmpty();

        // 5. V32 起沒有 FK：事件還沒被消費前，文章仍帶著（已不存在的）series_uuid——這是最終一致的中間狀態
        Article beforeConsume = articleRepo.findById(article.getId()).orElseThrow();
        assertThat(beforeConsume.getSeriesUuid()).isEqualTo(series.getUuid());

        // 6. 中間狀態下對外的 seriesNav 仍然是 null（SeriesFacade 查無 series 即無導覽），不會洩漏已刪除的系列
        mockMvc.perform(get("/api/v1/articles/{uuid}", articleUuid)
                        .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seriesNav").doesNotExist());

        // 7. 把 series 實際發出的事件交給 article consumer（IT 不真起 broker，直接呼叫）
        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(rabbitTemplate).convertAndSend(
                eq(SeriesEventContract.EXCHANGE), eq(SeriesEventContract.ROUTING_KEY_DELETED), published.capture());
        SeriesDeletedEvent event = (SeriesDeletedEvent) published.getValue();
        assertThat(event.seriesUuid()).isEqualTo(series.getUuid());
        articleSeriesDeletedConsumer.onSeriesDeleted(event);

        // 8. 文章的 series_uuid／series_position 已清空，文章本身仍在
        Article unlinked = articleRepo.findById(article.getId()).orElseThrow();
        assertThat(unlinked.getSeriesUuid()).isNull();
        assertThat(unlinked.getSeriesPosition()).isNull();

        // 9. 確認 GET /articles/{uuid} 回應中 seriesNav 為 null（無 series 關聯）
        mockMvc.perform(get("/api/v1/articles/{uuid}", articleUuid)
                        .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.seriesNav").doesNotExist());
    }

    // ─── Test F: nav 查詢對真實 DB 執行，prev/next 跳過 DRAFT，且 uuid 欄位對應正確 ──

    /**
     * 驗證 Task 3 新走的 {@code ArticleFacade.findPrevPublishedInSeries} /
     * {@code findNextPublishedInSeries} 對真實 Postgres 執行的行為。
     *
     * <p><b>為什麼需要這個 IT（Ruling F2）</b>：這兩條查詢是全案唯一沒有 IT 覆蓋、
     * 且唯一回傳「含 UUID 欄位」record 的新 SQL。MyBatis 對 record 建構子採<b>位置對應</b>
     * （見 {@code ArticleMapper} 上的 Ruling F1 註解），SELECT 欄位順序一旦與
     * {@code ArticleNavRef(uuid, title, slug)} 的元件順序不一致，不會編譯失敗、也不會拋例外，
     * 只會把欄位值悄悄塞進錯的欄位——純 Mockito 單元測試（{@code SeriesFacadeImplTest}）
     * 完全不執行 SQL，抓不到這種錯誤，只有跑真實 DB 的 IT 才能驗證。</p>
     *
     * <p><b>資料佈局</b>：series 內 4 篇文章依 series_position 排列——
     * pubFirst(1, PUBLISHED) → draftMiddle(2, DRAFT) → pubMiddle(3, PUBLISHED) → pubLast(4, PUBLISHED)。
     * DRAFT 卡在 pubFirst 與 pubMiddle 之間，故兩個方向的「跳過 DRAFT」都被覆蓋到：</p>
     * <ul>
     *     <li>pubFirst 的 next：直接下一位是 DRAFT，必須跳過取到 pubMiddle。</li>
     *     <li>pubMiddle 的 prev：直接上一位是 DRAFT，必須跳過取到 pubFirst。</li>
     *     <li>pubMiddle 的 next／pubLast 的 prev：兩篇皆 PUBLISHED，驗證正常相鄰情形不受影響。</li>
     *     <li>邊界：pubFirst 無 prev、pubLast 無 next。</li>
     * </ul>
     */
    @Test
    @DisplayName("series 導覽（prev/next）對真實 DB 執行：跳過 DRAFT，且 uuid 對應到正確文章")
    void getSeriesNavigation_skipsDraft_andMapsUuidPositionallyCorrect() {
        // 1. 建立 series + 4 篇文章（3 PUBLISHED + 1 DRAFT），直接寫 DB 決定位置
        //    （DRAFT 無法透過 addArticleToSeries API 加入，見 SeriesService#addArticleToSeries 的
        //    ARTICLE_NOT_PUBLISHED 守衛，故本測試全程繞過 API，直接操作 repository）
        Series series = createSeries("Nav Skip Draft Series", "nav-skip-draft-series-it", AUTHOR_ID);

        Article pubFirst = assignToSeries(createPublishedArticle(AUTHOR_ID), series.getUuid(), 1);
        Article draftMiddle = assignToSeries(createArticle(AUTHOR_ID, ArticleStatus.DRAFT), series.getUuid(), 2);
        Article pubMiddle = assignToSeries(createPublishedArticle(AUTHOR_ID), series.getUuid(), 3);
        Article pubLast = assignToSeries(createPublishedArticle(AUTHOR_ID), series.getUuid(), 4);

        // 2. pubFirst：首篇，prev 應為 empty；next 跳過 draftMiddle，直接拿到 pubMiddle
        SeriesNavigation firstNav = seriesFacade.getSeriesNavigation(pubFirst.getUuid()).orElseThrow();
        assertThat(firstNav.getPrev()).isNull();
        assertThat(firstNav.getNext()).isNotNull();
        assertThat(firstNav.getNext().getUuid()).isNotNull().isEqualTo(pubMiddle.getUuid());
        assertThat(firstNav.getNext().getTitle()).isEqualTo(pubMiddle.getTitle());
        assertThat(firstNav.getNext().getSlug()).isEqualTo(pubMiddle.getSlug());

        // 3. pubMiddle：中間篇，prev 跳過 draftMiddle 拿到 pubFirst；next 正常拿到 pubLast
        SeriesNavigation middleNav = seriesFacade.getSeriesNavigation(pubMiddle.getUuid()).orElseThrow();
        assertThat(middleNav.getPrev()).isNotNull();
        assertThat(middleNav.getPrev().getUuid()).isNotNull().isEqualTo(pubFirst.getUuid());
        assertThat(middleNav.getNext()).isNotNull();
        assertThat(middleNav.getNext().getUuid()).isNotNull().isEqualTo(pubLast.getUuid());

        // 4. pubLast：末篇，prev 正常拿到 pubMiddle；next 應為 empty
        SeriesNavigation lastNav = seriesFacade.getSeriesNavigation(pubLast.getUuid()).orElseThrow();
        assertThat(lastNav.getPrev()).isNotNull();
        assertThat(lastNav.getPrev().getUuid()).isNotNull().isEqualTo(pubMiddle.getUuid());
        assertThat(lastNav.getNext()).isNull();

        // 5. totalCount 三篇 PUBLISHED 皆一致為 3（DRAFT 不計入）
        assertThat(firstNav.getTotalCount()).isEqualTo(3);
        assertThat(middleNav.getTotalCount()).isEqualTo(3);
        assertThat(lastNav.getTotalCount()).isEqualTo(3);
    }
}
