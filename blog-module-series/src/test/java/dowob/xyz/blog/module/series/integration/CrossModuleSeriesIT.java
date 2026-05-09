package dowob.xyz.blog.module.series.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.series.config.SeriesTestApplication;
import dowob.xyz.blog.module.series.consumer.SeriesArticleDeletedConsumer;
import dowob.xyz.blog.module.series.model.Series;
import dowob.xyz.blog.module.series.repository.SeriesRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨模組整合測試：Article ↔ Series。
 *
 * <p>驗證以下端對端流程：</p>
 * <ol>
 *     <li>POST /series + 加文章 → GET /articles/{uuid} 回應含 seriesNav</li>
 *     <li>DELETE /articles/{uuid} → series.article_count 連動 -1</li>
 *     <li>DELETE /series/{uuid} → articles.series_id 設為 NULL</li>
 * </ol>
 *
 * <p>SeriesTestApplication 已掃 article 模組（ArticleController 可用），
 * 但未掃 reading 模組，故 liked/bookmarked/progress 均為 null 或 false，這在此 IT 中 OK。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("跨模組整合測試 — Article ↔ Series")
class CrossModuleSeriesIT {

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
    @Autowired private SeriesArticleDeletedConsumer seriesArticleDeletedConsumer;
    @Autowired private JdbcTemplate jdbcTemplate;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;

    private static final Long AUTHOR_ID = 1L;

    @BeforeEach
    void setUp() {
        // 清理前次測試資料（先刪 processed_events，再解綁 series_id，最後刪 series/article）
        jdbcTemplate.execute("DELETE FROM processed_events");
        articleRepo.findAll().forEach(a -> {
            a.setSeriesId(null);
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
            a.setSeriesId(null);
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
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    /**
     * 模擬 consumer 收到 ArticleDeletedEvent（IT 不真起 RabbitMQ broker，直接呼叫 consumer）。
     *
     * <p>Channel 用 Mockito mock，deliveryTag 固定為 1L，僅讓 basicAck/basicNack 不拋例外。</p>
     */
    private void simulateConsumerProcessing(UUID eventId, Long articleId, UUID articleUuid,
                                            Long authorId, Long seriesId) {
        try {
            ArticleDeletedEvent event = new ArticleDeletedEvent(
                eventId, articleId, articleUuid, authorId,
                seriesId, List.of(), List.of(), Instant.now()
            );
            Channel mockChannel = Mockito.mock(Channel.class);
            seriesArticleDeletedConsumer.onArticleDeleted(event, mockChannel, 1L);
        } catch (Exception e) {
            throw new RuntimeException("simulateConsumerProcessing 失敗", e);
        }
    }

    /** 直接透過 repository 建立一篇 PUBLISHED 測試文章。 */
    private Article createPublishedArticle(Long authorId) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle("Series IT Article " + UUID.randomUUID());
        article.setSlug("series-it-" + UUID.randomUUID());
        article.setContent("test content");
        article.setContentHtml("<p>test content</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    /** 透過 repository 建立一個 Series（不入庫 via API）。 */
    private Series createSeries(String title, String slug, Long authorId) {
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle(title);
        series.setSlug(slug);
        series.setDescription("IT description");
        series.setAuthorId(authorId);
        series.setArticleCount(0);
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

    // ─── Test B: article_count 連動 ──────────────────────────────────────────

    @Test
    @DisplayName("DELETE article → series.article_count 連動 -1（透過 MQ event）")
    void deleteArticle_decrementsSeriesCount() throws Exception {
        // 1. 建立 series + 文章
        Series series = createSeries("Count Series", "count-series-it", AUTHOR_ID);
        Article article = createPublishedArticle(AUTHOR_ID);
        UUID articleUuid = article.getUuid();
        Long articleId = article.getId();

        // 2. 透過 API 將文章加入 series（會觸發 incrementArticleCount）
        Map<String, Object> addPayload = Map.of("position", 1);
        mockMvc.perform(put("/api/v1/series/{seriesUuid}/articles/{articleUuid}",
                        series.getUuid(), articleUuid)
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addPayload)))
                .andExpect(status().isOk());

        // 驗證 article_count = 1
        Series afterAdd = seriesRepo.findByUuid(series.getUuid()).orElseThrow();
        assertThat(afterAdd.getArticleCount()).isEqualTo(1);

        // 3. DELETE /api/v1/articles/{uuid}（ADMIN 可刪任意文章）
        //    IT 不真起 RabbitMQ broker，ArticleServiceImpl 只 mock publish，下面手動呼叫 consumer
        mockMvc.perform(delete("/api/v1/articles/{uuid}", articleUuid)
                        .with(asUser(AUTHOR_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        // 4. 模擬 consumer 收到 ArticleDeletedEvent（直接呼叫，繞過 broker）
        simulateConsumerProcessing(UUID.randomUUID(), articleId, articleUuid,
                AUTHOR_ID, series.getId());

        // 5. 重查 series，article_count 應為 0
        Series afterDelete = seriesRepo.findByUuid(series.getUuid()).orElseThrow();
        assertThat(afterDelete.getArticleCount()).isEqualTo(0);
    }

    // ─── Test D: 重送冪等驗證 ────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE article 同事件重送 → article_count 不再扣（冪等驗證）")
    void deleteArticle_replayedEvent_skipsDecrement() {
        // 1. 建立 series + 文章，直接寫 DB 設定 article_count = 1
        Series series = createSeries("Idempotent Series", "idempotent-series-it", AUTHOR_ID);
        Article article = createPublishedArticle(AUTHOR_ID);
        article.setSeriesId(series.getId());
        article.setSeriesPosition(1);
        articleRepo.save(article);
        seriesRepo.findByUuid(series.getUuid()).ifPresent(s -> {
            // 直接更新 article_count 為 1
            jdbcTemplate.update("UPDATE series SET article_count = 1 WHERE id = ?", s.getId());
        });

        // 2. 構造一個固定 eventId 的事件（重送 2 次）
        UUID eventId = UUID.randomUUID();
        ArticleDeletedEvent event = new ArticleDeletedEvent(
            eventId, article.getId(), article.getUuid(), AUTHOR_ID,
            series.getId(), List.of(), List.of(), Instant.now()
        );

        // 3. 第一次處理 → article_count 從 1 變 0
        Channel mockChannel = Mockito.mock(Channel.class);
        seriesArticleDeletedConsumer.onArticleDeleted(event, mockChannel, 1L);
        Series afterFirst = seriesRepo.findByUuid(series.getUuid()).orElseThrow();
        assertThat(afterFirst.getArticleCount()).isEqualTo(0);

        // 4. 第二次處理（同一 event，模擬重送）
        seriesArticleDeletedConsumer.onArticleDeleted(event, mockChannel, 2L);

        // 5. article_count 仍是 0（沒被扣到 -1，dedup 生效）
        Series afterReplay = seriesRepo.findByUuid(series.getUuid()).orElseThrow();
        assertThat(afterReplay.getArticleCount()).isEqualTo(0);

        // 6. processed_events 只有 1 筆對應 (eventId, "series.article-deleted")
        long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE event_id = ? AND consumer_name = ?",
            Long.class, eventId, SeriesArticleDeletedConsumer.CONSUMER_NAME
        );
        assertThat(count).isEqualTo(1);
    }

    // ─── Test E: article 不在 series no-op ──────────────────────────────────

    @Test
    @DisplayName("Article 不在 series 被刪 → consumer no-op，不寫 processed_events")
    void deleteArticle_notInSeries_noOp() {
        // 1. 構造 seriesId == null 的事件
        ArticleDeletedEvent event = new ArticleDeletedEvent(
            UUID.randomUUID(), 100L, UUID.randomUUID(), AUTHOR_ID,
            null, List.of(), List.of(), Instant.now()
        );
        Channel mockChannel = Mockito.mock(Channel.class);
        seriesArticleDeletedConsumer.onArticleDeleted(event, mockChannel, 1L);

        // 2. 無 processed_events 記錄（早返不寫 dedup）
        long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM processed_events WHERE consumer_name = ?",
            Long.class, SeriesArticleDeletedConsumer.CONSUMER_NAME
        );
        assertThat(count).isEqualTo(0);
    }

    // ─── Test C: DELETE series → articles.series_id NULL ────────────────────

    @Test
    @DisplayName("DELETE /series/{uuid} → articles.series_id 設為 NULL")
    void deleteSeries_unlinksArticles() throws Exception {
        // 1. 建立 series + 文章
        Series series = createSeries("Unlink Series", "unlink-series-it", AUTHOR_ID);
        Article article = createPublishedArticle(AUTHOR_ID);
        UUID articleUuid = article.getUuid();

        // 2. 將文章加入 series（直接寫 DB，避免 article_count 影響 Test C 邏輯）
        article.setSeriesId(series.getId());
        article.setSeriesPosition(1);
        articleRepo.save(article);

        // 確認文章已關聯
        Article linked = articleRepo.findById(article.getId()).orElseThrow();
        assertThat(linked.getSeriesId()).isEqualTo(series.getId());

        // 3. DELETE /api/v1/series/{uuid}（series 所有人刪除）
        mockMvc.perform(delete("/api/v1/series/{uuid}", series.getUuid())
                        .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        // 4. series 已不存在
        assertThat(seriesRepo.findByUuid(series.getUuid())).isEmpty();

        // 5. 文章的 series_id 應已被 DB ON DELETE SET NULL 設為 NULL
        Article unlinked = articleRepo.findById(article.getId()).orElseThrow();
        assertThat(unlinked.getSeriesId()).isNull();

        // 6. 確認 GET /articles/{uuid} 回應中 seriesNav 為 null（無 series 關聯）
        mockMvc.perform(get("/api/v1/articles/{uuid}", articleUuid)
                        .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.seriesNav").doesNotExist());
    }
}
