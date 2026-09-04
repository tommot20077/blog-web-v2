package dowob.xyz.blog.module.comment.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.comment.config.CommentTestApplication;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.model.dto.request.EditCommentRequest;
import dowob.xyz.blog.module.comment.repository.CommentLikeRepository;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CommentController 整合測試。
 *
 * <p>使用 Testcontainers 啟動 PostgreSQL 與 Redis，
 * 透過 MockMvc 驗證留言 API 的完整 HTTP 流程與資料庫副作用。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = CommentTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("CommentController 整合測試")
class CommentControllerIT {

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
    @Autowired private CommentRepository commentRepo;
    @Autowired private CommentLikeRepository commentLikeRepo;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    /** article 模組的 ArticleFileBinder 依賴 FileFacade，其實作在 blog-module-file（未被本測試 scan） */
    @MockitoBean private FileFacade fileFacade;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final Long ADMIN_ID = 3L;

    private UUID articleUuid;
    private Long articleId;

    @BeforeEach
    void setup() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(USER_ID);
        article.setTitle("Test Article for Comments");
        article.setSlug("test-comment-article-" + UUID.randomUUID());
        article.setContent("test");
        article.setContentHtml("<p>test</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        Article saved = articleRepo.save(article);
        articleUuid = saved.getUuid();
        articleId = saved.getId();
    }

    @AfterEach
    void cleanUp() {
        commentLikeRepo.deleteAll();
        commentRepo.deleteAll();
        articleRepo.deleteAll();
    }

    /**
     * 建立模擬認證的 RequestPostProcessor。
     *
     * <p>同時注入角色及其對應的 Permission authorities，
     * 以支援 {@code @PreAuthorize} 宣告式存取控制。</p>
     *
     * @param userId 用戶 ID
     * @param role   角色
     * @return RequestPostProcessor
     */
    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    /**
     * 直接透過 Repository 建立 Comment（繞過 Service，不觸發計數更新）。
     *
     * @param userId   留言者 ID
     * @param parentId 父留言 ID；null 表示 top-level
     * @return 建立的 Comment UUID
     */
    private UUID createCommentDirectly(Long userId, Long parentId) {
        Comment c = new Comment();
        c.setUuid(UUID.randomUUID());
        c.setArticleId(articleId);
        c.setParentId(parentId);
        c.setUserId(userId);
        c.setContent("seed");
        c.setContentHtml("<p>seed</p>");
        c.setLikeCount(0);
        return commentRepo.save(c).getUuid();
    }

    // ─── 1. List ───

    @Test
    @DisplayName("GET /api/v1/articles/{uuid}/comments - 匿名 200 空列表")
    void list_anonymous_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.totalCommentCount").value(0));
    }

    // ─── 2. Create ───

    @Test
    @DisplayName("POST - 未登入回 401")
    void post_unauthenticated_returns401() throws Exception {
        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("hello");
        mockMvc.perform(post("/api/v1/articles/{uuid}/comments", articleUuid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST - 200 with valid markdown")
    void post_validMarkdown_returnsCommentResponse() throws Exception {
        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("**hello** world");
        mockMvc.perform(post("/api/v1/articles/{uuid}/comments", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.contentHtml").value(org.hamcrest.Matchers.containsString("<strong>hello</strong>")));
    }

    @Test
    @DisplayName("POST reply to reply - C0102")
    void post_replyToReply_returnsBusinessError_C0102() throws Exception {
        UUID topUuid = createCommentDirectly(USER_ID, null);
        Comment top = commentRepo.findByUuid(topUuid).orElseThrow();
        UUID replyUuid = createCommentDirectly(USER_ID, top.getId());

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("nested");
        req.setParentUuid(replyUuid);

        mockMvc.perform(post("/api/v1/articles/{uuid}/comments", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C0102"));
    }

    @Test
    @DisplayName("POST to soft-deleted parent - C0106")
    void post_softDeletedParent_returns_C0106() throws Exception {
        UUID topUuid = createCommentDirectly(USER_ID, null);
        Comment top = commentRepo.findByUuid(topUuid).orElseThrow();
        top.setDeletedAt(LocalDateTime.now());
        top.setDeletedByRole("AUTHOR");
        commentRepo.save(top);

        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("frozen reply");
        req.setParentUuid(topUuid);

        mockMvc.perform(post("/api/v1/articles/{uuid}/comments", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C0106"));
    }

    // ─── 3. Edit ───

    @Test
    @DisplayName("PUT within 5min - 200")
    void put_within5Min_returns200() throws Exception {
        UUID commentUuid = createCommentDirectly(USER_ID, null);

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("edited");
        mockMvc.perform(put("/api/v1/comments/{uuid}", commentUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    @Test
    @DisplayName("PUT after 5min - C0103")
    void put_after5Min_returns_C0103() throws Exception {
        UUID commentUuid = createCommentDirectly(USER_ID, null);
        // 把 createdAt 改到 10 分鐘前
        Comment c = commentRepo.findByUuid(commentUuid).orElseThrow();
        c.setCreatedAt(LocalDateTime.now().minusMinutes(10));
        commentRepo.save(c);

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("late");
        mockMvc.perform(put("/api/v1/comments/{uuid}", commentUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C0103"));
    }

    @Test
    @DisplayName("PUT by other - 403 (code A0006)")
    void put_byOther_returns403() throws Exception {
        UUID commentUuid = createCommentDirectly(USER_ID, null);

        EditCommentRequest req = new EditCommentRequest();
        req.setContent("hijack");
        mockMvc.perform(put("/api/v1/comments/{uuid}", commentUuid)
                .with(asUser(OTHER_USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
    }

    // ─── 4. Delete ───

    @Test
    @DisplayName("DELETE by owner - 200, 軟刪除")
    void delete_byOwner_returns200_andRowSoftDeleted() throws Exception {
        UUID commentUuid = createCommentDirectly(USER_ID, null);

        mockMvc.perform(delete("/api/v1/comments/{uuid}", commentUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        Comment c = commentRepo.findByUuid(commentUuid).orElseThrow();
        assertThat(c.getDeletedAt()).isNotNull();
        assertThat(c.getDeletedByRole()).isEqualTo("AUTHOR");
    }

    @Test
    @DisplayName("DELETE by admin - 200, 標記 ADMIN")
    void delete_byAdmin_returns200_marksAdmin() throws Exception {
        UUID commentUuid = createCommentDirectly(USER_ID, null);

        mockMvc.perform(delete("/api/v1/comments/{uuid}", commentUuid)
                .with(asUser(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        Comment c = commentRepo.findByUuid(commentUuid).orElseThrow();
        assertThat(c.getDeletedAt()).isNotNull();
        assertThat(c.getDeletedByRole()).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("DELETE by non-owner non-admin - 403 (code A0006)")
    void delete_byNonOwnerNonAdmin_returns403() throws Exception {
        UUID commentUuid = createCommentDirectly(USER_ID, null);

        mockMvc.perform(delete("/api/v1/comments/{uuid}", commentUuid)
                .with(asUser(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0006"));
    }

    // ─── 5. Soft-delete tombstone in list ───

    @Test
    @DisplayName("GET - 軟刪除 top-level 顯示佔位 + replies 仍可見")
    void list_softDeletedTopLevel_shownAsTombstone() throws Exception {
        UUID topUuid = createCommentDirectly(USER_ID, null);
        Comment top = commentRepo.findByUuid(topUuid).orElseThrow();
        createCommentDirectly(USER_ID, top.getId());     // 一個 reply

        // 軟刪除 top-level
        top.setDeletedAt(LocalDateTime.now());
        top.setDeletedByRole("ADMIN");
        commentRepo.save(top);

        mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topLevels.records[0].deleted").value(true))
                .andExpect(jsonPath("$.data.topLevels.records[0].content").value(""))
                .andExpect(jsonPath("$.data.topLevels.records[0].author").doesNotExist())
                .andExpect(jsonPath("$.data.topLevels.records[0].deletedByRole").value("ADMIN"))
                .andExpect(jsonPath("$.data.topLevels.records[0].replies.length()").value(1));
    }

    // ─── 6. Sort ───

    @Test
    @DisplayName("GET - default newest first")
    void list_sortNewest_default() throws Exception {
        UUID first = createCommentDirectly(USER_ID, null);
        Comment c1 = commentRepo.findByUuid(first).orElseThrow();
        c1.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        commentRepo.save(c1);
        UUID second = createCommentDirectly(USER_ID, null);

        mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topLevels.records[0].uuid").value(second.toString()));
    }

    @Test
    @DisplayName("GET - sort=oldest works")
    void list_sortOldest_works() throws Exception {
        UUID first = createCommentDirectly(USER_ID, null);
        Comment c1 = commentRepo.findByUuid(first).orElseThrow();
        c1.setCreatedAt(LocalDateTime.now().minusMinutes(5));
        commentRepo.save(c1);
        UUID second = createCommentDirectly(USER_ID, null);

        mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid)
                .param("sort", "oldest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topLevels.records[0].uuid").value(first.toString()));
    }

    // ─── 7. Count integration ───

    @Test
    @DisplayName("create then list - articles.comment_count == 1")
    void create_thenList_articleCommentCount_isOne() throws Exception {
        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("first");
        mockMvc.perform(post("/api/v1/articles/{uuid}/comments", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        Article a = articleRepo.findByUuid(articleUuid).orElseThrow();
        assertThat(a.getCommentCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("delete then check - articles.comment_count decremented")
    void delete_thenList_articleCommentCount_decremented() throws Exception {
        UUID commentUuid = createCommentDirectly(USER_ID, null);
        // 手動把 article 計數改成 1（直接 createDirectly 沒走 service）
        Article a0 = articleRepo.findByUuid(articleUuid).orElseThrow();
        a0.setCommentCount(1);
        articleRepo.save(a0);

        mockMvc.perform(delete("/api/v1/comments/{uuid}", commentUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());

        Article a = articleRepo.findByUuid(articleUuid).orElseThrow();
        assertThat(a.getCommentCount()).isEqualTo(0);
    }

    // ─── 8. 文章可見性（下架後留言不得對外曝光）───

    /**
     * 將 setup 建立的文章改為 ARCHIVED（下架）。
     */
    private void archiveArticle() {
        Article a = articleRepo.findByUuid(articleUuid).orElseThrow();
        a.setStatus(ArticleStatus.ARCHIVED);
        articleRepo.save(a);
    }

    @Test
    @DisplayName("GET - 文章下架後，匿名取不到任何留言（200 + 空列表）")
    void list_archivedArticle_anonymous_returnsEmptyList() throws Exception {
        createCommentDirectly(USER_ID, null);
        archiveArticle();

        mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.topLevels.records.length()").value(0))
                .andExpect(jsonPath("$.data.topLevels.total").value(0))
                .andExpect(jsonPath("$.data.totalCommentCount").value(0));
    }

    @Test
    @DisplayName("GET - 文章下架後，非作者的登入者也取不到留言")
    void list_archivedArticle_otherUser_returnsEmptyList() throws Exception {
        createCommentDirectly(USER_ID, null);
        archiveArticle();

        mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid)
                .with(asUser(OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topLevels.records.length()").value(0))
                .andExpect(jsonPath("$.data.totalCommentCount").value(0));
    }

    @Test
    @DisplayName("GET - 文章下架後，作者本人仍看得到自己文章的留言")
    void list_archivedArticle_author_stillSeesComments() throws Exception {
        createCommentDirectly(USER_ID, null);
        archiveArticle();

        mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid)
                .with(asUser(USER_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topLevels.records.length()").value(1));
    }

    @Test
    @DisplayName("GET - 文章下架後，ADMIN 仍看得到留言")
    void list_archivedArticle_admin_stillSeesComments() throws Exception {
        createCommentDirectly(USER_ID, null);
        archiveArticle();

        mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid)
                .with(asUser(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topLevels.records.length()").value(1));
    }

    @Test
    @DisplayName("GET - 未發布（DRAFT）文章的留言同樣不對匿名曝光")
    void list_draftArticle_anonymous_returnsEmptyList() throws Exception {
        createCommentDirectly(USER_ID, null);
        Article a = articleRepo.findByUuid(articleUuid).orElseThrow();
        a.setStatus(ArticleStatus.DRAFT);
        articleRepo.save(a);

        mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.topLevels.records.length()").value(0));
    }

    @Test
    @DisplayName("GET - 不存在的文章與已下架文章回應形狀一致（不成為存在性探測器）")
    void list_unknownArticle_anonymous_returnsEmptyList() throws Exception {
        mockMvc.perform(get("/api/v1/articles/{uuid}/comments", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.topLevels.records.length()").value(0))
                .andExpect(jsonPath("$.data.totalCommentCount").value(0));
    }
}
