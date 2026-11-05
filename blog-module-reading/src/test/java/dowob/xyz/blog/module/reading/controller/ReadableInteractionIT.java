package dowob.xyz.blog.module.reading.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.reading.config.ReadingTestApplication;
import dowob.xyz.blog.module.reading.model.UserHighlight;
import dowob.xyz.blog.module.reading.model.dto.request.CreateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateProgressRequest;
import dowob.xyz.blog.module.reading.repository.ArticleLikeRepository;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
import dowob.xyz.blog.module.reading.repository.UserHighlightRepository;
import dowob.xyz.blog.module.reading.repository.UserReadingProgressRepository;
import dowob.xyz.blog.module.reading.support.SeededReadingUsers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * reading 模組互動寫入路徑「可讀才可互動」整合測試（SEC-16 ＝ AUTH-05／DATA-12）。
 *
 * <p>涵蓋文章按讚、收藏、劃線（建立／更新）、閱讀進度（更新）等建立／更新類路徑：
 * 對他人的非公開文章，回應必須與「文章不存在」<b>完全相同</b>（狀態碼、錯誤碼、訊息），
 * 且不得落任何列、不得動計數；作者本人、ADMIN、PUBLISHED 一律允許。
 * 移除類路徑（取消按讚、移除收藏、刪除自己的劃線）對已下架文章仍允許且計數正確。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ReadingTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("reading 互動寫入「可讀才可互動」整合測試")
class ReadableInteractionIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("blog_test").withUsername("test").withPassword("test");

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
    @Autowired private ArticleLikeRepository likeRepo;
    @Autowired private UserBookmarkRepository bookmarkRepo;
    @Autowired private UserHighlightRepository highlightRepo;
    @Autowired private UserReadingProgressRepository progressRepo;
    @Autowired private StringRedisTemplate redisTemplate;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;

    /** 文章作者 */
    private static final UUID AUTHOR = SeededReadingUsers.OTHER_USER_UUID;
    /** 與作者無關的一般讀者 */
    private static final UUID VIEWER = SeededReadingUsers.USER_UUID;
    private static final UUID ADMIN = SeededReadingUsers.ADMIN_UUID;

    @AfterEach
    void cleanUp() {
        Set<String> keys = redisTemplate.keys(RedisKeyConstant.READING_PROGRESS_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        redisTemplate.delete(RedisKeyConstant.READING_DIRTY_KEY);
        likeRepo.deleteAll();
        bookmarkRepo.deleteAll();
        highlightRepo.deleteAll();
        progressRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private UUID newArticle(ArticleStatus status) {
        Article a = new Article();
        a.setUuid(UUID.randomUUID());
        a.setAuthorUuid(AUTHOR);
        a.setTitle("readable-it");
        a.setSlug("readable-it-" + UUID.randomUUID());
        a.setContent("c");
        a.setContentHtml("<p>c</p>");
        a.setStatus(status);
        a.setLikeCount(0);
        a.setCommentCount(0);
        a.setViewCount(0L);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(a).getUuid();
    }

    private RequestPostProcessor as(UUID userUuid, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(userUuid, role), null, authorities));
    }

    private long likeCount(UUID articleUuid) {
        return articleRepo.findByUuid(articleUuid).orElseThrow().getLikeCount();
    }

    /** 取回「狀態碼＋回應 body 的 code／message」，用來斷言兩個請求的回應完全相同 */
    private record Outcome(int status, String code, String message) {}

    private Outcome outcomeOf(MvcResult r) throws Exception {
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        return new Outcome(r.getResponse().getStatus(),
                body.path("code").asText(), body.path("message").asText());
    }

    private String highlightBody() throws Exception {
        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("snip");
        req.setColor("#FFEB3B");
        return objectMapper.writeValueAsString(req);
    }

    // ───────────────────────── 文章按讚 ─────────────────────────

    @Test
    @DisplayName("POST /like：他人 DRAFT 與不存在的回應完全相同，且不落列、like_count 不變")
    void likeArticle_othersDraft_sameAsNotFound_noSideEffect() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);

        Outcome onDraft = outcomeOf(mockMvc.perform(post("/api/v1/articles/{u}/like", draft)
                .with(as(VIEWER, Role.USER))).andReturn());
        Outcome onMissing = outcomeOf(mockMvc.perform(post("/api/v1/articles/{u}/like", UUID.randomUUID())
                .with(as(VIEWER, Role.USER))).andReturn());

        assertThat(onDraft).isEqualTo(onMissing);
        assertThat(onDraft.code()).isEqualTo("A0201");
        assertThat(likeRepo.count()).isZero();
        assertThat(likeCount(draft)).isZero();
    }

    @Test
    @DisplayName("POST /like：PENDING_REVIEW／REJECTED／ARCHIVED 他人一律被拒且 like_count 不變")
    void likeArticle_othersNonPublished_allRejected() throws Exception {
        for (ArticleStatus s : List.of(ArticleStatus.PENDING_REVIEW, ArticleStatus.REJECTED, ArticleStatus.ARCHIVED)) {
            UUID a = newArticle(s);
            mockMvc.perform(post("/api/v1/articles/{u}/like", a).with(as(VIEWER, Role.USER)))
                    .andExpect(status().isBadRequest());
            assertThat(likeCount(a)).as(s.name()).isZero();
        }
        assertThat(likeRepo.count()).isZero();
    }

    @Test
    @DisplayName("POST /like：作者本人對自己的 DRAFT、ADMIN 對他人 DRAFT、任何人對 PUBLISHED 皆允許，計數 +1")
    void likeArticle_authorAdminPublished_allowed() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);
        mockMvc.perform(post("/api/v1/articles/{u}/like", draft).with(as(AUTHOR, Role.USER)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/{u}/like", draft).with(as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk());
        assertThat(likeCount(draft)).isEqualTo(2);

        UUID published = newArticle(ArticleStatus.PUBLISHED);
        mockMvc.perform(post("/api/v1/articles/{u}/like", published).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());
        assertThat(likeCount(published)).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE /like：文章已下架（ARCHIVED）仍可取消按讚，like_count 正確 -1")
    void unlikeArticle_archived_allowedAndCountCorrect() throws Exception {
        UUID a = newArticle(ArticleStatus.PUBLISHED);
        mockMvc.perform(post("/api/v1/articles/{u}/like", a).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());
        assertThat(likeCount(a)).isEqualTo(1);

        Article article = articleRepo.findByUuid(a).orElseThrow();
        article.setStatus(ArticleStatus.ARCHIVED);
        articleRepo.save(article);

        mockMvc.perform(delete("/api/v1/articles/{u}/like", a).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());
        assertThat(likeRepo.count()).isZero();
        assertThat(likeCount(a)).isZero();
    }

    // ───────────────────────── 收藏 ─────────────────────────

    @Test
    @DisplayName("POST /bookmark：他人 DRAFT 與不存在的回應完全相同，且不落列")
    void bookmark_othersDraft_sameAsNotFound_noSideEffect() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);

        Outcome onDraft = outcomeOf(mockMvc.perform(post("/api/v1/articles/{u}/bookmark", draft)
                .with(as(VIEWER, Role.USER))).andReturn());
        Outcome onMissing = outcomeOf(mockMvc.perform(post("/api/v1/articles/{u}/bookmark", UUID.randomUUID())
                .with(as(VIEWER, Role.USER))).andReturn());

        assertThat(onDraft).isEqualTo(onMissing);
        assertThat(onDraft.code()).isEqualTo("A0201");
        assertThat(bookmarkRepo.count()).isZero();
    }

    @Test
    @DisplayName("POST /bookmark：作者本人、ADMIN 對 DRAFT 允許；PUBLISHED 允許")
    void bookmark_authorAdminPublished_allowed() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);
        mockMvc.perform(post("/api/v1/articles/{u}/bookmark", draft).with(as(AUTHOR, Role.USER)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/{u}/bookmark", draft).with(as(ADMIN, Role.ADMIN)))
                .andExpect(status().isOk());
        UUID published = newArticle(ArticleStatus.PUBLISHED);
        mockMvc.perform(post("/api/v1/articles/{u}/bookmark", published).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());
        assertThat(bookmarkRepo.count()).isEqualTo(3);
    }

    @Test
    @DisplayName("DELETE /bookmark：文章已下架（ARCHIVED）仍可移除收藏")
    void unbookmark_archived_allowed() throws Exception {
        UUID a = newArticle(ArticleStatus.PUBLISHED);
        mockMvc.perform(post("/api/v1/articles/{u}/bookmark", a).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());
        Article article = articleRepo.findByUuid(a).orElseThrow();
        article.setStatus(ArticleStatus.ARCHIVED);
        articleRepo.save(article);

        mockMvc.perform(delete("/api/v1/articles/{u}/bookmark", a).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());
        assertThat(bookmarkRepo.count()).isZero();
    }

    // ───────────────────────── 劃線 ─────────────────────────

    @Test
    @DisplayName("POST /highlights：他人 DRAFT 與不存在的回應完全相同，且不落列")
    void createHighlight_othersDraft_sameAsNotFound_noSideEffect() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);

        Outcome onDraft = outcomeOf(mockMvc.perform(post("/api/v1/articles/{u}/highlights", draft)
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(highlightBody())).andReturn());
        Outcome onMissing = outcomeOf(mockMvc.perform(post("/api/v1/articles/{u}/highlights", UUID.randomUUID())
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(highlightBody())).andReturn());

        assertThat(onDraft).isEqualTo(onMissing);
        assertThat(onDraft.code()).isEqualTo("A0201");
        assertThat(highlightRepo.count()).isZero();
    }

    @Test
    @DisplayName("POST /highlights：作者本人、ADMIN 對 DRAFT 允許；PUBLISHED 允許")
    void createHighlight_authorAdminPublished_allowed() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);
        mockMvc.perform(post("/api/v1/articles/{u}/highlights", draft)
                .with(as(AUTHOR, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(highlightBody()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/{u}/highlights", draft)
                .with(as(ADMIN, Role.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(highlightBody()))
                .andExpect(status().isOk());
        UUID published = newArticle(ArticleStatus.PUBLISHED);
        mockMvc.perform(post("/api/v1/articles/{u}/highlights", published)
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(highlightBody()))
                .andExpect(status().isOk());
        assertThat(highlightRepo.count()).isEqualTo(3);
    }

    private UserHighlight seedHighlight(UUID articleUuid, UUID owner) {
        UserHighlight h = new UserHighlight();
        h.setUuid(UUID.randomUUID());
        h.setUserUuid(owner);
        h.setArticleUuid(articleUuid);
        h.setSnippet("seed");
        h.setPrefix("");
        h.setSuffix("");
        h.setColor("#FFEB3B");
        return highlightRepo.save(h);
    }

    @Test
    @DisplayName("PUT /highlights/{uuid}：文章已非公開且操作者非作者非 ADMIN → ARTICLE_NOT_FOUND，劃線不被修改")
    void updateHighlight_articleNoLongerReadable_rejected() throws Exception {
        UUID a = newArticle(ArticleStatus.ARCHIVED);
        UserHighlight h = seedHighlight(a, VIEWER);
        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");

        mockMvc.perform(put("/api/v1/highlights/{u}", h.getUuid())
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());

        assertThat(highlightRepo.findByUuid(h.getUuid()).orElseThrow().getColor()).isEqualTo("#FFEB3B");
    }

    @Test
    @DisplayName("PUT /highlights/{uuid}：PUBLISHED 允許更新")
    void updateHighlight_published_allowed() throws Exception {
        UUID a = newArticle(ArticleStatus.PUBLISHED);
        UserHighlight h = seedHighlight(a, VIEWER);
        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");

        mockMvc.perform(put("/api/v1/highlights/{u}", h.getUuid())
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());
        assertThat(highlightRepo.findByUuid(h.getUuid()).orElseThrow().getColor()).isEqualTo("#00FF00");
    }

    @Test
    @DisplayName("DELETE /highlights/{uuid}：文章已下架仍可刪除自己的劃線")
    void deleteHighlight_archived_allowed() throws Exception {
        UUID a = newArticle(ArticleStatus.ARCHIVED);
        UserHighlight h = seedHighlight(a, VIEWER);

        mockMvc.perform(delete("/api/v1/highlights/{u}", h.getUuid()).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());
        assertThat(highlightRepo.count()).isZero();
    }

    @Test
    @DisplayName("GET /highlights：他人 DRAFT 與不存在的 uuid 回應完全相同（status 與 body）")
    void listHighlights_othersDraft_identicalToNotFound() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);

        MvcResult onDraft = mockMvc.perform(get("/api/v1/articles/{u}/highlights", draft)
                .with(as(VIEWER, Role.USER))).andReturn();
        MvcResult onMissing = mockMvc.perform(get("/api/v1/articles/{u}/highlights", UUID.randomUUID())
                .with(as(VIEWER, Role.USER))).andReturn();

        assertThat(onDraft.getResponse().getStatus()).isEqualTo(200);
        assertThat(onDraft.getResponse().getStatus()).isEqualTo(onMissing.getResponse().getStatus());
        JsonNode a = objectMapper.readTree(onDraft.getResponse().getContentAsString());
        JsonNode b = objectMapper.readTree(onMissing.getResponse().getContentAsString());
        assertThat(a.path("code")).isEqualTo(b.path("code"));
        assertThat(a.path("message")).isEqualTo(b.path("message"));
        assertThat(a.path("data")).isEqualTo(b.path("data"));
        assertThat(a.path("data").size()).isZero();
    }

    @Test
    @DisplayName("GET /highlights：自己在已下架文章上的劃線仍可取回")
    void listHighlights_ownHighlightOnArchivedArticle_stillReturned() throws Exception {
        UUID a = newArticle(ArticleStatus.ARCHIVED);
        seedHighlight(a, VIEWER);
        seedHighlight(a, AUTHOR);

        MvcResult r = mockMvc.perform(get("/api/v1/articles/{u}/highlights", a)
                .with(as(VIEWER, Role.USER))).andReturn();

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(objectMapper.readTree(r.getResponse().getContentAsString()).path("data").size()).isEqualTo(1);
    }

    // ───────────────────────── 閱讀進度 ─────────────────────────

    private String progressBody(String p) throws Exception {
        UpdateProgressRequest req = new UpdateProgressRequest();
        req.setProgress(new BigDecimal(p));
        return objectMapper.writeValueAsString(req);
    }

    @Test
    @DisplayName("PUT /progress：他人 DRAFT 與不存在的回應完全相同（200 靜默略過），Redis 與 DB 都不落資料")
    void updateProgress_othersDraft_sameAsNotFound_writesNothing() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);

        Outcome onDraft = outcomeOf(mockMvc.perform(put("/api/v1/articles/{u}/progress", draft)
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(progressBody("0.6"))).andReturn());
        Outcome onMissing = outcomeOf(mockMvc.perform(put("/api/v1/articles/{u}/progress", UUID.randomUUID())
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(progressBody("0.6"))).andReturn());
        mockMvc.perform(put("/api/v1/articles/{u}/progress", draft)
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(progressBody("1.0")))
                .andExpect(status().isOk());

        assertThat(onDraft).isEqualTo(onMissing);
        assertThat(redisTemplate.opsForHash().entries(
                RedisKeyConstant.READING_PROGRESS_PREFIX + VIEWER + ":" + draft)).isEmpty();
        assertThat(redisTemplate.opsForSet().members(RedisKeyConstant.READING_DIRTY_KEY)).isNullOrEmpty();
        assertThat(progressRepo.findByUserUuidAndArticleUuid(VIEWER, draft)).isEmpty();
    }

    @Test
    @DisplayName("PUT /progress：作者本人對自己的 DRAFT 允許，寫入 Redis")
    void updateProgress_authorOwnDraft_allowed() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);

        mockMvc.perform(put("/api/v1/articles/{u}/progress", draft)
                .with(as(AUTHOR, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(progressBody("0.6")))
                .andExpect(status().isOk());

        assertThat(redisTemplate.opsForHash().entries(
                RedisKeyConstant.READING_PROGRESS_PREFIX + AUTHOR + ":" + draft)).containsKey("progress");
    }

    @Test
    @DisplayName("PUT /progress：ADMIN 對他人 DRAFT 允許")
    void updateProgress_adminOthersDraft_allowed() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);

        mockMvc.perform(put("/api/v1/articles/{u}/progress", draft)
                .with(as(ADMIN, Role.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(progressBody("0.6")))
                .andExpect(status().isOk());

        assertThat(redisTemplate.opsForHash().entries(
                RedisKeyConstant.READING_PROGRESS_PREFIX + ADMIN + ":" + draft)).containsKey("progress");
    }
}
