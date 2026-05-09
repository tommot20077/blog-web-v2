package dowob.xyz.blog.module.reading.controller;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.reading.config.ReadingTestApplication;
import dowob.xyz.blog.module.reading.repository.ArticleLikeRepository;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ArticleLikeController 整合測試。
 *
 * <p>使用 Testcontainers 啟動 PostgreSQL 與 Redis，
 * 透過 MockMvc 驗證按讚 API 的完整 HTTP 流程與資料庫副作用。</p>
 *
 * <p>從 blog-module-article 搬到 blog-module-reading（T3），
 * 使用 ReadingTestApplication 啟動 context。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ReadingTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleLikeController 整合測試")
class ArticleLikeControllerIT {

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private ArticleLikeRepository articleLikeRepository;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private TagFacade tagFacade;

    @MockitoBean
    private UserFacade userFacade;

    @MockitoBean
    private UserAuthService userAuthService;

    private static final Long USER_ID = 1L;
    private static final Long AUTHOR_ID = 1L;

    private UUID articleUuid;
    private Long articleId;

    @BeforeEach
    void setup() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(AUTHOR_ID);
        article.setTitle("Test Article");
        article.setSlug("test-article-" + UUID.randomUUID());
        article.setContent("test content");
        article.setContentHtml("<p>test content</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        Article saved = articleRepository.save(article);
        articleUuid = saved.getUuid();
        articleId = saved.getId();
    }

    @AfterEach
    void cleanUp() {
        articleLikeRepository.deleteAll();
        articleRepository.deleteAll();
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
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    @Test
    @DisplayName("POST /like - 200 OK 並建立 row")
    void likeArticle_returns200_andCreatesRow() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(articleLikeRepository.findByUserIdAndArticleId(USER_ID, articleId)).isPresent();
    }

    @Test
    @DisplayName("POST /like - 重複按讚 idempotent")
    void likeArticle_idempotent() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());

        // 重複按讚後仍然只有一筆紀錄
        assertThat(articleLikeRepository.findByUserIdAndArticleId(USER_ID, articleId)).isPresent();
    }

    @Test
    @DisplayName("DELETE /like - 200 OK 並刪除 row")
    void unlikeArticle_returns200_andDeletesRow() throws Exception {
        // 先按讚
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());

        // 再取消
        mockMvc.perform(delete("/api/v1/articles/{uuid}/like", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(articleLikeRepository.findByUserIdAndArticleId(USER_ID, articleId)).isEmpty();
    }

    @Test
    @DisplayName("DELETE /like - 沒按過 idempotent")
    void unlikeArticle_idempotentWhenNotLiked() throws Exception {
        mockMvc.perform(delete("/api/v1/articles/{uuid}/like", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    @Test
    @DisplayName("POST /like - 未登入回 401")
    void likeArticle_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid))
                .andExpect(status().isUnauthorized());
    }
}
