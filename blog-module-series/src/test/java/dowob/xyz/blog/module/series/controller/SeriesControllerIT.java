package dowob.xyz.blog.module.series.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * SeriesController 整合測試。
 *
 * <p>使用 Testcontainers 啟動 PostgreSQL 與 Redis，
 * 透過 MockMvc 驗證 Series API 的完整 HTTP 流程與資料庫副作用。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = SeriesTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("SeriesController 整合測試")
class SeriesControllerIT {

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

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;

    private static final Long USER1_ID = 1L;
    private static final Long USER2_ID = 2L;
    private static final Long ADMIN_ID = 3L;

    @BeforeEach
    void setup() {
        // 清理前次測試資料
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
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    /** 建立一篇測試文章（PUBLISHED 狀態）。 */
    private Article createPublishedArticle(Long authorId) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle("Test Article " + UUID.randomUUID());
        article.setSlug("test-article-" + UUID.randomUUID());
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

    /** 建立一篇指定狀態的文章並關聯到 series（供公開可見性測試用）。 */
    private Article createArticleInSeries(Long authorId, ArticleStatus status, Long seriesId, int position) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle("Test Article " + UUID.randomUUID());
        article.setSlug("test-article-" + UUID.randomUUID());
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

    /** 建立一個測試 Series（不入庫，供 POST 請求用）。 */
    private Map<String, Object> createSeriesPayload(String title, String slug) {
        return Map.of("title", title, "slug", slug, "description", "Test description");
    }

    @Test
    @DisplayName("POST /series - 200 + series 建立成功")
    void post_validRequest_returns200() throws Exception {
        Map<String, Object> payload = createSeriesPayload("Spring Boot Series", "spring-boot-series");

        mockMvc.perform(post("/api/v1/series")
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.slug").value("spring-boot-series"))
                // 此端點原本直接回 Series entity，內部 Long ID 外洩（architecture.md：對外只出 UUID）
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.authorId").doesNotExist());

        assertThat(seriesRepo.findBySlug("spring-boot-series")).isPresent();
    }

    @Test
    @DisplayName("POST /series - 400 slug 格式不符 ^[a-z0-9-]+$")
    void post_invalidSlugFormat_returns400() throws Exception {
        Map<String, Object> payload = createSeriesPayload("Invalid Slug", "Invalid_Slug!");

        mockMvc.perform(post("/api/v1/series")
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /series - 400 S0104 slug 重複")
    void post_duplicateSlug_returnsS0104() throws Exception {
        Map<String, Object> payload = createSeriesPayload("Series 1", "dup-slug");

        mockMvc.perform(post("/api/v1/series")
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/series")
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S0104"));
    }

    @Test
    @DisplayName("POST /series - 401 未登入")
    void post_unauthenticated_returns401() throws Exception {
        Map<String, Object> payload = createSeriesPayload("Unauth Series", "unauth-series");

        mockMvc.perform(post("/api/v1/series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT /series/{uuid} - owner 更新成功 200")
    void put_byOwner_returns200() throws Exception {
        // 先建立 series
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("Original");
        series.setSlug("original-slug");
        series.setDescription("desc");
        series.setAuthorId(USER1_ID);
        series.setArticleCount(0);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        Series saved = seriesRepo.save(series);

        Map<String, Object> updatePayload = Map.of("title", "Updated Title");

        mockMvc.perform(put("/api/v1/series/{uuid}", saved.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.title").value("Updated Title"))
                // 此端點原本直接回 Series entity，內部 Long ID 外洩（architecture.md：對外只出 UUID）
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.authorId").doesNotExist());
    }

    @Test
    @DisplayName("PUT /series/{uuid} - 非 owner 更新 → 400 S0102")
    void put_byNonOwner_returnsS0102() throws Exception {
        // user2 的 series
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("User2 Series");
        series.setSlug("user2-series");
        series.setDescription("desc");
        series.setAuthorId(USER2_ID);
        series.setArticleCount(0);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        Series saved = seriesRepo.save(series);

        Map<String, Object> updatePayload = Map.of("title", "Hijacked Title");

        // user1 嘗試更新 user2 的 series
        mockMvc.perform(put("/api/v1/series/{uuid}", saved.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S0102"));
    }

    @Test
    @DisplayName("PUT /series/{uuid} - USER 角色（無 ARTICLE_CREATE）→ 403 A0006")
    void put_byUserRole_returns403() throws Exception {
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("Series");
        series.setSlug("series-rbac-test");
        series.setAuthorId(USER1_ID);
        series.setArticleCount(0);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        Series saved = seriesRepo.save(series);

        Map<String, Object> updatePayload = Map.of("title", "Hijacked");

        // USER 角色無 ARTICLE_CREATE，被 controller @PreAuthorize 直接擋
        mockMvc.perform(put("/api/v1/series/{uuid}", saved.getUuid())
                        .with(asUser(USER1_ID, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updatePayload)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
    }

    @Test
    @DisplayName("DELETE /series/{uuid} - owner 刪除 → articles series_id 設 NULL")
    void delete_byOwner_unlinkArticles() throws Exception {
        // 建立 series
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("Series To Delete");
        series.setSlug("series-to-delete");
        series.setAuthorId(USER1_ID);
        series.setArticleCount(1);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        Series saved = seriesRepo.save(series);

        // 建立文章並關聯到 series
        Article article = createPublishedArticle(USER1_ID);
        article.setSeriesId(saved.getId());
        article.setSeriesPosition(1);
        articleRepo.save(article);

        mockMvc.perform(delete("/api/v1/series/{uuid}", saved.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        // series 已刪除
        assertThat(seriesRepo.findByUuid(saved.getUuid())).isEmpty();

        // 文章的 series_id 應由 DB ON DELETE SET NULL 設為 NULL
        Article updatedArticle = articleRepo.findById(article.getId()).orElseThrow();
        assertThat(updatedArticle.getSeriesId()).isNull();
    }

    @Test
    @DisplayName("PUT /series/{uuid}/articles/{articleUuid} - 加文章到 Series 成功 200")
    void putArticle_validRequest_returns200() throws Exception {
        // 建立 series
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("Article Series");
        series.setSlug("article-series");
        series.setAuthorId(USER1_ID);
        series.setArticleCount(0);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        Series saved = seriesRepo.save(series);

        // 建立文章
        Article article = createPublishedArticle(USER1_ID);

        Map<String, Object> addPayload = Map.of("position", 1);

        mockMvc.perform(put("/api/v1/series/{uuid}/articles/{articleUuid}",
                        saved.getUuid(), article.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addPayload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        // 驗證文章已關聯到 series
        Article updated = articleRepo.findById(article.getId()).orElseThrow();
        assertThat(updated.getSeriesId()).isEqualTo(saved.getId());
        assertThat(updated.getSeriesPosition()).isEqualTo(1);
    }

    @Test
    @DisplayName("PUT /series/{uuid}/articles/{articleUuid} - 文章已在其他 Series → 400 S0106")
    void putArticle_articleInOtherSeries_returnsS0106() throws Exception {
        // 建立兩個 series
        Series series1 = new Series();
        series1.setUuid(UUID.randomUUID());
        series1.setTitle("Series 1");
        series1.setSlug("series-one");
        series1.setAuthorId(USER1_ID);
        series1.setArticleCount(1);
        series1.setCreatedAt(LocalDateTime.now());
        series1.setUpdatedAt(LocalDateTime.now());
        Series saved1 = seriesRepo.save(series1);

        Series series2 = new Series();
        series2.setUuid(UUID.randomUUID());
        series2.setTitle("Series 2");
        series2.setSlug("series-two");
        series2.setAuthorId(USER1_ID);
        series2.setArticleCount(0);
        series2.setCreatedAt(LocalDateTime.now());
        series2.setUpdatedAt(LocalDateTime.now());
        Series saved2 = seriesRepo.save(series2);

        // 建立文章並關聯到 series1
        Article article = createPublishedArticle(USER1_ID);
        article.setSeriesId(saved1.getId());
        article.setSeriesPosition(1);
        articleRepo.save(article);

        Map<String, Object> addPayload = Map.of("position", 1);

        // 嘗試將已在 series1 的文章加入 series2 → S0106
        mockMvc.perform(put("/api/v1/series/{uuid}/articles/{articleUuid}",
                        saved2.getUuid(), article.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addPayload)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("S0106"));
    }

    @Test
    @DisplayName("GET /series/{slug} - 已認證用戶取得含 PUBLISHED 文章的 series 詳情（含 myProgress）")
    void getSlug_authenticated_includesMyProgress() throws Exception {
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("Progress Series");
        series.setSlug("progress-series");
        series.setAuthorId(USER1_ID);
        series.setArticleCount(1);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        Series saved = seriesRepo.save(series);
        createArticleInSeries(USER1_ID, ArticleStatus.PUBLISHED, saved.getId(), 1);

        // 已認證用戶取得 series 詳情（myProgress 不應為 null；readingFacade mock 回空 → readCount 0）
        mockMvc.perform(get("/api/v1/series/{slug}", "progress-series")
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.slug").value("progress-series"))
                .andExpect(jsonPath("$.data.myProgress").exists())
                .andExpect(jsonPath("$.data.myProgress.readCount").value(0))
                .andExpect(jsonPath("$.data.myProgress.totalCount").value(1));
    }

    @Test
    @DisplayName("GET /series/{slug} - 含 PUBLISHED 文章的 series 匿名可存取，回 200 且無 myProgress")
    void getSlug_anonymous_returns200WithoutMyProgress() throws Exception {
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("Anonymous Series");
        series.setSlug("anonymous-series");
        series.setAuthorId(USER1_ID);
        series.setArticleCount(1);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        Series saved = seriesRepo.save(series);
        createArticleInSeries(USER1_ID, ArticleStatus.PUBLISHED, saved.getId(), 1);

        // 未帶任何認證：本端點依 security.md 原則 7 豁免為公開端點，
        // 且 GET /api/v1/series/** 於 SecurityConfig 明確 permitAll。
        mockMvc.perform(get("/api/v1/series/{slug}", "anonymous-series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.slug").value("anonymous-series"))
                // currentUserId 為 null 時不計算個人化進度
                .andExpect(jsonPath("$.data.myProgress").doesNotExist());
    }

    @Test
    @DisplayName("GET /series/{slug} - 只含草稿的 series → 匿名仍回 200 空清單（series 視為存在，列表另行策展，finding #3）")
    void getSlug_anonymous_seriesWithoutPublished_returnsEmpty() throws Exception {
        // 只有一篇 DRAFT 文章的 series：不進公開列表（策展），但詳情視為存在、回空清單
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("Draft Only Series");
        series.setSlug("draft-only-series");
        series.setAuthorId(USER1_ID);
        series.setArticleCount(1);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        Series saved = seriesRepo.save(series);
        createArticleInSeries(USER1_ID, ArticleStatus.DRAFT, saved.getId(), 1);

        mockMvc.perform(get("/api/v1/series/{slug}", "draft-only-series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.slug").value("draft-only-series"))
                // 零篇公開：articleCount 0、articles 空、無 DRAFT 外洩
                .andExpect(jsonPath("$.data.articleCount").value(0))
                .andExpect(jsonPath("$.data.articles.length()").value(0))
                .andExpect(jsonPath("$.data.myProgress").doesNotExist());
    }

    @Test
    @DisplayName("GET /series/{slug} - 匿名不得看到 series 內的 DRAFT 文章（#47 過濾回歸守衛，finding #5）")
    void getSlug_anonymous_neverExposesDraftArticle() throws Exception {
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("Mixed Series");
        series.setSlug("mixed-series");
        series.setAuthorId(USER1_ID);
        series.setArticleCount(2);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        Series saved = seriesRepo.save(series);

        Article published = createArticleInSeries(USER1_ID, ArticleStatus.PUBLISHED, saved.getId(), 1);
        createArticleInSeries(USER1_ID, ArticleStatus.DRAFT, saved.getId(), 2);

        mockMvc.perform(get("/api/v1/series/{slug}", "mixed-series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                // 只暴露 PUBLISHED：計數與清單皆為 1，DRAFT 不得外洩
                .andExpect(jsonPath("$.data.articleCount").value(1))
                .andExpect(jsonPath("$.data.articles.length()").value(1))
                .andExpect(jsonPath("$.data.articles[0].uuid").value(published.getUuid().toString()))
                .andExpect(jsonPath("$.data.myProgress").doesNotExist());
    }

    @Test
    @DisplayName("GET /series - 匿名可存取列表，回 200")
    void list_anonymous_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    @Test
    @DisplayName("GET /series - 只含草稿的 series 不出現在公開列表（不依賴反正規化計數，finding #2）")
    void list_seriesWithOnlyDraft_excludedFromPublicList() throws Exception {
        // series A：有一篇 PUBLISHED 文章 → 應出現
        Series withPublished = new Series();
        withPublished.setUuid(UUID.randomUUID());
        withPublished.setTitle("Has Published");
        withPublished.setSlug("has-published");
        withPublished.setAuthorId(USER1_ID);
        withPublished.setArticleCount(1);
        withPublished.setCreatedAt(LocalDateTime.now());
        withPublished.setUpdatedAt(LocalDateTime.now());
        Series savedA = seriesRepo.save(withPublished);
        createArticleInSeries(USER1_ID, ArticleStatus.PUBLISHED, savedA.getId(), 1);

        // series B：只有 DRAFT 文章，但反正規化 article_count 仍為 1（模擬 unpublish 後的漂移）→ 不應出現
        Series draftOnly = new Series();
        draftOnly.setUuid(UUID.randomUUID());
        draftOnly.setTitle("Draft Only");
        draftOnly.setSlug("draft-only");
        draftOnly.setAuthorId(USER1_ID);
        draftOnly.setArticleCount(1);
        draftOnly.setCreatedAt(LocalDateTime.now());
        draftOnly.setUpdatedAt(LocalDateTime.now());
        Series savedB = seriesRepo.save(draftOnly);
        createArticleInSeries(USER1_ID, ArticleStatus.DRAFT, savedB.getId(), 1);

        mockMvc.perform(get("/api/v1/series"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].slug").value("has-published"))
                // articleCount 反映實際 PUBLISHED 數，而非反正規化欄
                .andExpect(jsonPath("$.data.records[0].articleCount").value(1));
    }

    @Test
    @DisplayName("GET /series?size=100000 - 每頁筆數夾到上界 100（未認證亦不能觸發無上界查詢，finding #4）")
    void list_oversizedPageSize_clampedTo100() throws Exception {
        mockMvc.perform(get("/api/v1/series").param("size", "100000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    @DisplayName("GET /series/{slug} - 非 PUBLISHED 文章不得出現在詳情（H3 全棧回歸）")
    void getSlug_mixedStatuses_exposesPublishedOnly() throws Exception {
        Series series = new Series();
        series.setUuid(UUID.randomUUID());
        series.setTitle("Mixed Series");
        series.setSlug("mixed-series");
        series.setAuthorId(USER1_ID);
        // 非正規化計數含未發布文章；response 的 articleCount 不該沿用（見 #1）
        series.setArticleCount(2);
        series.setCreatedAt(LocalDateTime.now());
        series.setUpdatedAt(LocalDateTime.now());
        Series savedSeries = seriesRepo.save(series);

        Article published = createPublishedArticle(USER1_ID);
        published.setSeriesId(savedSeries.getId());
        published.setSeriesPosition(1);
        articleRepo.save(published);

        // 直接屬於同一 series 的 DRAFT（模擬 PUBLISHED 加入後被改回草稿，series_id 未清空）
        Article draft = new Article();
        draft.setUuid(UUID.randomUUID());
        draft.setAuthorId(USER1_ID);
        draft.setTitle("SECRET DRAFT TITLE");
        draft.setSlug("secret-draft-" + UUID.randomUUID());
        draft.setContent("secret content");
        draft.setContentHtml("<p>secret content</p>");
        draft.setStatus(ArticleStatus.DRAFT);
        draft.setLikeCount(0);
        draft.setCommentCount(0);
        draft.setViewCount(0L);
        draft.setCreatedAt(LocalDateTime.now());
        draft.setUpdatedAt(LocalDateTime.now());
        draft.setSeriesId(savedSeries.getId());
        draft.setSeriesPosition(2);
        articleRepo.save(draft);

        // 以 series 作者本人請求：仍只回 PUBLISHED（getSeriesDetail 刻意無作者分支）
        mockMvc.perform(get("/api/v1/series/{slug}", "mixed-series")
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                // 只列出 PUBLISHED；length==1 且該篇為 published，即證明 DRAFT 的 title/slug/summary 未外洩
                .andExpect(jsonPath("$.data.articles.length()").value(1))
                .andExpect(jsonPath("$.data.articles[0].uuid").value(published.getUuid().toString()))
                // articleCount 對齊過濾後數量（#1），不沿用 series.article_count=2
                .andExpect(jsonPath("$.data.articleCount").value(1))
                // 進度分母只算公開文章
                .andExpect(jsonPath("$.data.myProgress.readCount").value(0))
                .andExpect(jsonPath("$.data.myProgress.totalCount").value(1));
    }
}
