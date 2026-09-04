package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.reading.config.ReadingTestApplication;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BookmarkController 整合測試。
 *
 * <p>使用 Testcontainers 啟動 PostgreSQL 與 Redis，
 * 透過 MockMvc 驗證收藏 API 的完整 HTTP 流程與資料庫副作用。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ReadingTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("BookmarkController 整合測試")
class BookmarkControllerIT {

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
    @Autowired private ArticleRepository articleRepo;
    @Autowired private UserBookmarkRepository bookmarkRepo;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    /** article 模組的 ArticleFileBinder 依賴 FileFacade，其實作在 blog-module-file（未被本測試 scan） */
    @MockitoBean private FileFacade fileFacade;
    @MockitoBean private UserAuthService userAuthService;

    private static final Long USER_ID = 1L;
    private UUID articleUuid;
    private Long articleId;

    @BeforeEach
    void setup() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(USER_ID);
        article.setTitle("Bookmark IT");
        article.setSlug("bookmark-it-" + UUID.randomUUID());
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
        bookmarkRepo.deleteAll();
        articleRepo.deleteAll();
    }

    /**
     * 建立模擬認證的 RequestPostProcessor。
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

    @Test
    @DisplayName("POST /bookmark - 200 + row created")
    void bookmark_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(bookmarkRepo.findByUserIdAndArticleId(USER_ID, articleId)).isPresent();
    }

    @Test
    @DisplayName("POST /bookmark - idempotent")
    void bookmark_idempotent() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        assertThat(bookmarkRepo.findByUserIdAndArticleId(USER_ID, articleId)).isPresent();
    }

    @Test
    @DisplayName("DELETE /bookmark - 200 + row removed")
    void unbookmark_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(bookmarkRepo.findByUserIdAndArticleId(USER_ID, articleId)).isEmpty();
    }

    @Test
    @DisplayName("DELETE /bookmark - idempotent")
    void unbookmark_idempotent() throws Exception {
        mockMvc.perform(delete("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("GET /me/bookmarks - 分頁列表")
    void myBookmarks_returnsPaginated() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/bookmarks")
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("POST /bookmark - 401 unauthenticated")
    void bookmark_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid))
                .andExpect(status().isUnauthorized());
    }

    // ─── 文章可見性（下架文章不得留在收藏列表）───

    private static final Long OTHER_AUTHOR_ID = 2L;
    private static final Long ADMIN_ID = 3L;

    /**
     * 建立指定作者與狀態的文章。
     *
     * @param authorId 作者資料庫主鍵
     * @param status   文章狀態
     * @return 已存檔的 Article
     */
    private Article createArticle(Long authorId, ArticleStatus status) {
        Article a = new Article();
        a.setUuid(UUID.randomUUID());
        a.setAuthorId(authorId);
        a.setTitle("Bookmark IT " + status);
        a.setSlug("bookmark-it-" + UUID.randomUUID());
        a.setContent("test");
        a.setContentHtml("<p>test</p>");
        a.setStatus(status);
        a.setLikeCount(0);
        a.setCommentCount(0);
        a.setViewCount(0L);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(a);
    }

    @Test
    @DisplayName("GET /me/bookmarks - 他人文章下架後不再出現在收藏列表")
    void myBookmarks_archivedArticleOfOtherAuthor_isExcluded() throws Exception {
        Article archived = createArticle(OTHER_AUTHOR_ID, ArticleStatus.PUBLISHED);
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", archived.getUuid())
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        archived.setStatus(ArticleStatus.ARCHIVED);
        articleRepo.save(archived);

        mockMvc.perform(get("/api/v1/users/me/bookmarks")
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].uuid").value(articleUuid.toString()))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("GET /me/bookmarks - 收藏他人 DRAFT 文章同樣不出現在收藏列表")
    void myBookmarks_draftArticleOfOtherAuthor_isExcluded() throws Exception {
        Article draft = createArticle(OTHER_AUTHOR_ID, ArticleStatus.DRAFT);
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", draft.getUuid())
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/users/me/bookmarks")
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("GET /me/bookmarks - 作者本人收藏自己的下架文章仍看得到")
    void myBookmarks_archivedOwnArticle_stillVisibleToAuthor() throws Exception {
        Article own = createArticle(USER_ID, ArticleStatus.PUBLISHED);
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", own.getUuid())
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        own.setStatus(ArticleStatus.ARCHIVED);
        articleRepo.save(own);

        mockMvc.perform(get("/api/v1/users/me/bookmarks")
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].uuid").value(own.getUuid().toString()));
    }

    @Test
    @DisplayName("GET /me/bookmarks - ADMIN 收藏的下架文章仍看得到")
    void myBookmarks_archivedArticle_stillVisibleToAdmin() throws Exception {
        Article archived = createArticle(OTHER_AUTHOR_ID, ArticleStatus.PUBLISHED);
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", archived.getUuid())
                .with(asUser(ADMIN_ID, Role.ADMIN))).andExpect(status().isOk());

        archived.setStatus(ArticleStatus.ARCHIVED);
        articleRepo.save(archived);

        mockMvc.perform(get("/api/v1/users/me/bookmarks")
                .with(asUser(ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(1));
    }
}
