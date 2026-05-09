package dowob.xyz.blog.module.comment.controller;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.comment.config.CommentTestApplication;
import dowob.xyz.blog.module.comment.model.Comment;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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

@SpringBootTest(classes = CommentTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("CommentLikeController 整合測試")
class CommentLikeControllerIT {

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
    @Autowired private CommentRepository commentRepo;
    @Autowired private CommentLikeRepository commentLikeRepo;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;

    private static final Long USER_ID = 1L;
    private UUID articleUuid;
    private Long articleId;
    private UUID commentUuid;
    private Long commentId;

    @BeforeEach
    void setup() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(USER_ID);
        article.setTitle("Test Article");
        article.setSlug("test-clike-" + UUID.randomUUID());
        article.setContent("test");
        article.setContentHtml("<p>test</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        Article savedA = articleRepo.save(article);
        articleUuid = savedA.getUuid();
        articleId = savedA.getId();

        Comment c = new Comment();
        c.setUuid(UUID.randomUUID());
        c.setArticleId(articleId);
        c.setUserId(USER_ID);
        c.setContent("seed comment");
        c.setContentHtml("<p>seed comment</p>");
        c.setLikeCount(0);
        Comment savedC = commentRepo.save(c);
        commentUuid = savedC.getUuid();
        commentId = savedC.getId();
    }

    @AfterEach
    void cleanUp() {
        commentLikeRepo.deleteAll();
        commentRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    @Test
    @DisplayName("POST /comments/{uuid}/like - 200 + 建 row + count+1")
    void like_returns200() throws Exception {
        mockMvc.perform(post("/api/v1/comments/{uuid}/like", commentUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(commentLikeRepo.findByUserIdAndCommentId(USER_ID, commentId)).isPresent();
        assertThat(commentRepo.findByUuid(commentUuid).orElseThrow().getLikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /like - idempotent，連續按兩次只算一次")
    void like_idempotent() throws Exception {
        mockMvc.perform(post("/api/v1/comments/{uuid}/like", commentUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/comments/{uuid}/like", commentUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());

        assertThat(commentRepo.findByUuid(commentUuid).orElseThrow().getLikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("POST /like - 軟刪除留言阻擋 (C0104)")
    void like_softDeletedComment_returns_C0104() throws Exception {
        Comment c = commentRepo.findByUuid(commentUuid).orElseThrow();
        c.setDeletedAt(LocalDateTime.now());
        c.setDeletedByRole("AUTHOR");
        commentRepo.save(c);

        mockMvc.perform(post("/api/v1/comments/{uuid}/like", commentUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("C0104"));
    }

    @Test
    @DisplayName("DELETE /like - 200 + 移除 row + count-1")
    void unlike_returns200() throws Exception {
        // 先按讚
        mockMvc.perform(post("/api/v1/comments/{uuid}/like", commentUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/comments/{uuid}/like", commentUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        assertThat(commentLikeRepo.findByUserIdAndCommentId(USER_ID, commentId)).isEmpty();
        assertThat(commentRepo.findByUuid(commentUuid).orElseThrow().getLikeCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("DELETE /like - 沒按過 idempotent")
    void unlike_idempotent() throws Exception {
        mockMvc.perform(delete("/api/v1/comments/{uuid}/like", commentUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /like - 未登入 401")
    void like_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/comments/{uuid}/like", commentUuid))
                .andExpect(status().isUnauthorized());
    }
}
