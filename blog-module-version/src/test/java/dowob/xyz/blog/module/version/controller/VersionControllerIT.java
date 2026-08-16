package dowob.xyz.blog.module.version.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.version.config.VersionTestApplication;
import dowob.xyz.blog.module.version.model.ArticleVersion;
import dowob.xyz.blog.module.version.repository.ArticleVersionRepository;
import dowob.xyz.blog.module.version.service.VersioningService;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * VersionController 整合測試。
 *
 * <p>使用 Testcontainers 啟動 PostgreSQL 與 Redis，
 * 透過 MockMvc 驗證 Version API 的完整 HTTP 流程與資料庫副作用。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = VersionTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("VersionController 整合測試")
class VersionControllerIT {

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
    @Autowired private ArticleRepository articleRepo;
    @Autowired private ArticleVersionRepository versionRepo;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private SeriesFacade seriesFacade;
    /** article 模組的 ArticleFileBinder 依賴 FileFacade，其實作在 blog-module-file（未被本測試 scan） */
    @MockitoBean private FileFacade fileFacade;

    private static final Long USER1_ID = 1L;
    private static final Long USER2_ID = 2L;
    private static final Long ADMIN_ID = 3L;

    @BeforeEach
    void setup() {
        versionRepo.deleteAll();
        articleRepo.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        versionRepo.deleteAll();
        articleRepo.deleteAll();
    }

    /** 建立模擬認證的 RequestPostProcessor。 */
    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    /** 建立並儲存測試文章（DRAFT 狀態）。 */
    private Article createArticle(Long authorId) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(authorId);
        article.setTitle("Test Article " + UUID.randomUUID());
        article.setSlug("test-article-" + UUID.randomUUID());
        article.setContent("This is test content for versioning.");
        article.setContentHtml("<p>This is test content for versioning.</p>");
        article.setStatus(ArticleStatus.DRAFT);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article);
    }

    /** 建立並儲存測試版本快照。 */
    private ArticleVersion createVersion(Long articleId, Long authorId, String type, String note) {
        ArticleVersion v = new ArticleVersion();
        v.setUuid(UUID.randomUUID());
        v.setArticleId(articleId);
        v.setAuthorId(authorId);
        v.setType(type);
        v.setTitle("Version Title");
        v.setSlug("version-slug-" + UUID.randomUUID());
        v.setContent("Version content body.");
        v.setStatus("DRAFT");
        v.setNote(note);
        v.setCreatedAt(LocalDateTime.now());
        return versionRepo.save(v);
    }

    // ─────────────────────────────── LIST ───────────────────────────────────

    @Test
    @DisplayName("GET /versions - owner 取得版本列表 200")
    void list_byOwner_returns200() throws Exception {
        Article article = createArticle(USER1_ID);
        createVersion(article.getId(), USER1_ID, VersioningService.TYPE_AUTO, null);
        createVersion(article.getId(), USER1_ID, VersioningService.TYPE_MANUAL, "重要存檔");

        mockMvc.perform(get("/api/v1/articles/{articleUuid}/versions", article.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records").isArray())
                // architecture.md：對外只出 UUID，內部 Long ID 不得現身
                .andExpect(jsonPath("$.data.records[0].uuid").exists())
                .andExpect(jsonPath("$.data.records[0].authorId").doesNotExist());
    }

    @Test
    @DisplayName("GET /versions - 非 owner 存取 → 400 V0102")
    void list_byNonOwner_returnsV0102() throws Exception {
        Article article = createArticle(USER1_ID);
        createVersion(article.getId(), USER1_ID, VersioningService.TYPE_AUTO, null);

        mockMvc.perform(get("/api/v1/articles/{articleUuid}/versions", article.getUuid())
                        .with(asUser(USER2_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V0102"));
    }

    // ─────────────────────────────── GET DETAIL ─────────────────────────────

    @Test
    @DisplayName("GET /versions/{versionUuid} - owner 取得詳情 200 含 content")
    void getDetail_byOwner_returns200WithContent() throws Exception {
        Article article = createArticle(USER1_ID);
        ArticleVersion v = createVersion(article.getId(), USER1_ID, VersioningService.TYPE_MANUAL, "detail test");

        mockMvc.perform(get("/api/v1/articles/{articleUuid}/versions/{versionUuid}",
                        article.getUuid(), v.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.uuid").value(v.getUuid().toString()))
                .andExpect(jsonPath("$.data.content").value("Version content body."))
                // architecture.md：對外只出 UUID，內部 Long ID 不得現身
                .andExpect(jsonPath("$.data.authorId").doesNotExist())
                .andExpect(jsonPath("$.data.categoryId").doesNotExist());
    }

    @Test
    @DisplayName("GET /versions/{versionUuid} - version 不存在 → 400 V0101")
    void getDetail_versionNotFound_returnsV0101() throws Exception {
        Article article = createArticle(USER1_ID);
        UUID nonExistentUuid = UUID.randomUUID();

        mockMvc.perform(get("/api/v1/articles/{articleUuid}/versions/{versionUuid}",
                        article.getUuid(), nonExistentUuid)
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V0101"));
    }

    // ─────────────────────────────── CREATE MANUAL ──────────────────────────

    @Test
    @DisplayName("POST /versions/manual - 合法請求 200")
    void createManual_validRequest_returns200() throws Exception {
        Article article = createArticle(USER1_ID);
        Map<String, String> payload = Map.of("note", "手動存檔 v1");

        mockMvc.perform(post("/api/v1/articles/{articleUuid}/versions/manual", article.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.type").value("MANUAL"))
                .andExpect(jsonPath("$.data.note").value("手動存檔 v1"))
                // 此端點原本直接回 ArticleVersion entity，內部 Long ID 全數外洩
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.articleId").doesNotExist())
                .andExpect(jsonPath("$.data.authorId").doesNotExist())
                .andExpect(jsonPath("$.data.categoryId").doesNotExist());

        // 確認版本已寫入 DB
        long count = 0;
        for (ArticleVersion v : versionRepo.findAll()) {
            if (v.getArticleId().equals(article.getId())) count++;
        }
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /versions/manual - 未認證 → 401")
    void createManual_unauthenticated_returns401() throws Exception {
        Article article = createArticle(USER1_ID);

        mockMvc.perform(post("/api/v1/articles/{articleUuid}/versions/manual", article.getUuid())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ─────────────────────────────── RESTORE ────────────────────────────────

    @Test
    @DisplayName("POST /versions/{versionUuid}/restore - owner 還原成功 200")
    void restore_byOwner_returns200() throws Exception {
        Article article = createArticle(USER1_ID);
        ArticleVersion v = createVersion(article.getId(), USER1_ID, VersioningService.TYPE_MANUAL, "restore me");

        mockMvc.perform(post("/api/v1/articles/{articleUuid}/versions/{versionUuid}/restore",
                        article.getUuid(), v.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    // ─────────────────────────────── PROMOTE ────────────────────────────────

    @Test
    @DisplayName("POST /versions/{versionUuid}/promote - AUTO 升級成功 200")
    void promote_autoVersion_returns200() throws Exception {
        Article article = createArticle(USER1_ID);
        ArticleVersion v = createVersion(article.getId(), USER1_ID, VersioningService.TYPE_AUTO, null);

        mockMvc.perform(post("/api/v1/articles/{articleUuid}/versions/{versionUuid}/promote",
                        article.getUuid(), v.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.type").value("MANUAL"))
                // 此端點原本直接回 ArticleVersion entity，內部 Long ID 全數外洩
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.articleId").doesNotExist())
                .andExpect(jsonPath("$.data.authorId").doesNotExist())
                .andExpect(jsonPath("$.data.categoryId").doesNotExist());
    }

    @Test
    @DisplayName("POST /versions/{versionUuid}/promote - MANUAL 升級 → 400 V0104")
    void promote_manualVersion_returnsV0104() throws Exception {
        Article article = createArticle(USER1_ID);
        ArticleVersion v = createVersion(article.getId(), USER1_ID, VersioningService.TYPE_MANUAL, "already manual");

        mockMvc.perform(post("/api/v1/articles/{articleUuid}/versions/{versionUuid}/promote",
                        article.getUuid(), v.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V0104"));
    }

    // ─────────────────────────────── DELETE ─────────────────────────────────

    @Test
    @DisplayName("DELETE /versions/{versionUuid} - MANUAL 快照刪除成功 200")
    void delete_manualVersion_returns200() throws Exception {
        Article article = createArticle(USER1_ID);
        ArticleVersion v = createVersion(article.getId(), USER1_ID, VersioningService.TYPE_MANUAL, "to delete");

        mockMvc.perform(delete("/api/v1/articles/{articleUuid}/versions/{versionUuid}",
                        article.getUuid(), v.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(versionRepo.findByUuid(v.getUuid())).isEmpty();
    }

    @Test
    @DisplayName("DELETE /versions/{versionUuid} - PUBLISHED 快照不可刪 → 400 V0103")
    void delete_publishedVersion_returnsV0103() throws Exception {
        Article article = createArticle(USER1_ID);
        ArticleVersion v = createVersion(article.getId(), USER1_ID, VersioningService.TYPE_PUBLISHED, "Published v1");

        mockMvc.perform(delete("/api/v1/articles/{articleUuid}/versions/{versionUuid}",
                        article.getUuid(), v.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V0103"));
    }

    // ─────────────────── ARTICLE-VERSION MISMATCH（巢狀 URL 一致性） ───────────────

    @Test
    @DisplayName("GET /articles/{A}/versions/{B} - version 屬於另一篇 → 400 V0108")
    void getDetail_versionBelongsToAnotherArticle_returnsV0108() throws Exception {
        Article articleA = createArticle(USER1_ID);
        Article articleB = createArticle(USER1_ID);
        ArticleVersion vOfB = createVersion(articleB.getId(), USER1_ID, VersioningService.TYPE_MANUAL, "B's version");

        mockMvc.perform(get("/api/v1/articles/{articleUuid}/versions/{versionUuid}",
                        articleA.getUuid(), vOfB.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V0108"));
    }

    @Test
    @DisplayName("POST /articles/{A}/versions/{B}/restore - version 屬於另一篇 → 400 V0108")
    void restore_versionBelongsToAnotherArticle_returnsV0108() throws Exception {
        Article articleA = createArticle(USER1_ID);
        Article articleB = createArticle(USER1_ID);
        ArticleVersion vOfB = createVersion(articleB.getId(), USER1_ID, VersioningService.TYPE_MANUAL, "B's snap");

        mockMvc.perform(post("/api/v1/articles/{articleUuid}/versions/{versionUuid}/restore",
                        articleA.getUuid(), vOfB.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V0108"));
    }

    @Test
    @DisplayName("POST /articles/{A}/versions/{B}/promote - version 屬於另一篇 → 400 V0108")
    void promote_versionBelongsToAnotherArticle_returnsV0108() throws Exception {
        Article articleA = createArticle(USER1_ID);
        Article articleB = createArticle(USER1_ID);
        ArticleVersion vOfB = createVersion(articleB.getId(), USER1_ID, VersioningService.TYPE_AUTO, null);

        mockMvc.perform(post("/api/v1/articles/{articleUuid}/versions/{versionUuid}/promote",
                        articleA.getUuid(), vOfB.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V0108"));
    }

    @Test
    @DisplayName("DELETE /articles/{A}/versions/{B} - version 屬於另一篇 → 400 V0108")
    void delete_versionBelongsToAnotherArticle_returnsV0108() throws Exception {
        Article articleA = createArticle(USER1_ID);
        Article articleB = createArticle(USER1_ID);
        ArticleVersion vOfB = createVersion(articleB.getId(), USER1_ID, VersioningService.TYPE_MANUAL, "B's");

        mockMvc.perform(delete("/api/v1/articles/{articleUuid}/versions/{versionUuid}",
                        articleA.getUuid(), vOfB.getUuid())
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V0108"));
    }
}
