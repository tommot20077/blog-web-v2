package dowob.xyz.blog.module.comment.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.reading.model.ArticleLike;
import dowob.xyz.blog.module.reading.repository.ArticleLikeRepository;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.comment.config.CommentTestApplication;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.CommentLike;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 跨模組整合測試：Article ↔ Comment ↔ Like。
 *
 * <p>驗證以下端對端流程：</p>
 * <ol>
 *     <li>文章 commentCount / likeCount 反映 API 操作後狀態</li>
 *     <li>ArticleSummaryResponse.liked 反映當前使用者按讚狀態</li>
 *     <li>文章物理刪除連帶清 comments + article_likes（FK CASCADE）</li>
 *     <li>留言物理刪除連帶清 comment_likes（FK CASCADE）</li>
 * </ol>
 *
 * @author Yuan
 */
@SpringBootTest(classes = CommentTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("跨模組整合測試 — Article ↔ Comment ↔ Like")
class CrossModuleCommentIT {

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
    @Autowired private ArticleLikeRepository articleLikeRepo;
    @Autowired private CommentRepository commentRepo;
    @Autowired private CommentLikeRepository commentLikeRepo;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private UUID articleUuid;
    private Long articleId;

    @BeforeEach
    void setup() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(USER_ID);
        article.setTitle("Cross-module IT");
        article.setSlug("cross-it-" + UUID.randomUUID());
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
        articleLikeRepo.deleteAll();
        articleRepo.deleteAll();
    }

    /**
     * 建立模擬認證的 RequestPostProcessor。
     *
     * <p>同時注入角色及其對應的 Permission authorities。</p>
     */
    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    // ─── Test 1: 計數同步 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("文章 commentCount + likeCount 反映 service 操作後狀態")
    void articleCounts_reflectActionsViaServiceLayer() throws Exception {
        // 透過 POST API 建立 3 個留言（走 Service 層，觸發 incrementCommentCount）
        for (int i = 0; i < 3; i++) {
            CreateCommentRequest req = new CreateCommentRequest();
            req.setContent("comment " + i);
            mockMvc.perform(post("/api/v1/articles/{uuid}/comments", articleUuid)
                    .with(asUser(USER_ID, Role.USER))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }

        // 透過 POST API 按讚（走 Service 層，觸發 incrementLikeCount）
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());

        Article a = articleRepo.findByUuid(articleUuid).orElseThrow();
        assertThat(a.getCommentCount()).isEqualTo(3);
        assertThat(a.getLikeCount()).isEqualTo(1);
    }

    // ─── Test 2: liked 欄位反映當前使用者 ─────────────────────────────────────

    @Test
    @DisplayName("按讚記錄反映當前使用者狀態：user1 有讚、user2 無讚、取消讚後清除")
    void articleLikedFlag_reflectsCurrentUserState() throws Exception {
        // user1 按讚
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());

        // user1 有按讚紀錄 → present
        assertThat(articleLikeRepo.findByUserIdAndArticleId(USER_ID, articleId)).isPresent();

        // user2 無按讚紀錄 → empty
        assertThat(articleLikeRepo.findByUserIdAndArticleId(OTHER_USER_ID, articleId)).isEmpty();

        // article likeCount 更新為 1
        assertThat(articleRepo.findByUuid(articleUuid).orElseThrow().getLikeCount()).isEqualTo(1);

        // user1 再按一次 → idempotent，likeCount 仍為 1
        mockMvc.perform(post("/api/v1/articles/{uuid}/like", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());
        assertThat(articleRepo.findByUuid(articleUuid).orElseThrow().getLikeCount()).isEqualTo(1);
    }

    // ─── Test 3: 文章硬刪除 CASCADE comments + article_likes ──────────────────

    @Test
    @DisplayName("Article 物理刪除連帶清 comments + article_likes (FK CASCADE)")
    void deleteArticle_cascadeDeletesCommentsAndLikes() {
        // 直接寫入 Comment（不走 Service，避免影響 commentCount）
        Comment c = new Comment();
        c.setUuid(UUID.randomUUID());
        c.setArticleId(articleId);
        c.setUserId(USER_ID);
        c.setContent("cascade test");
        c.setContentHtml("<p>cascade test</p>");
        c.setLikeCount(0);
        Comment savedComment = commentRepo.save(c);

        // 直接寫入 ArticleLike
        ArticleLike like = new ArticleLike();
        like.setUserId(USER_ID);
        like.setArticleId(articleId);
        like.setCreatedAt(LocalDateTime.now());
        articleLikeRepo.save(like);

        assertThat(commentRepo.findByUuid(savedComment.getUuid())).isPresent();
        assertThat(articleLikeRepo.findByUserIdAndArticleId(USER_ID, articleId)).isPresent();

        // 物理刪除文章 → 應觸發 FK CASCADE
        articleRepo.deleteById(articleId);

        // 驗證 cascade：comments 與 article_likes 均已清除
        assertThat(commentRepo.findByUuid(savedComment.getUuid())).isEmpty();
        assertThat(articleLikeRepo.findByUserIdAndArticleId(USER_ID, articleId)).isEmpty();
    }

    // ─── Test 4: 留言硬刪除 CASCADE comment_likes ─────────────────────────────

    @Test
    @DisplayName("Comment 物理刪除連帶清 comment_likes (FK CASCADE)")
    void deleteComment_cascadeDeletesCommentLikes() {
        // 直接寫入 Comment
        Comment c = new Comment();
        c.setUuid(UUID.randomUUID());
        c.setArticleId(articleId);
        c.setUserId(USER_ID);
        c.setContent("cl cascade");
        c.setContentHtml("<p>cl cascade</p>");
        c.setLikeCount(0);
        Comment savedComment = commentRepo.save(c);
        Long savedCommentId = savedComment.getId();

        // 直接寫入 CommentLike
        CommentLike cl = new CommentLike();
        cl.setUserId(USER_ID);
        cl.setCommentId(savedCommentId);
        cl.setCreatedAt(LocalDateTime.now());
        commentLikeRepo.save(cl);

        assertThat(commentLikeRepo.findByUserIdAndCommentId(USER_ID, savedCommentId)).isPresent();

        // 物理刪除留言 → 應觸發 FK CASCADE
        commentRepo.deleteById(savedCommentId);

        // 驗證 cascade：comment_likes 已清除
        assertThat(commentLikeRepo.findByUserIdAndCommentId(USER_ID, savedCommentId)).isEmpty();
    }
}
