package dowob.xyz.blog.module.comment.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.comment.config.CommentTestApplication;
import dowob.xyz.blog.module.comment.model.Comment;
import dowob.xyz.blog.module.comment.model.dto.request.CreateCommentRequest;
import dowob.xyz.blog.module.comment.repository.CommentLikeRepository;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import dowob.xyz.blog.module.comment.support.SeededUsers;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.MvcResult;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * comment 模組互動寫入路徑「可讀才可互動」整合測試（SEC-16 ＝ AUTH-05／DATA-12）。
 *
 * <p>涵蓋新增留言、回覆、對留言按讚：文章對操作者不可讀（他人的 DRAFT 等）時，回應必須與
 * 「文章不存在」完全相同，且不落列、不動 {@code comment_count}／{@code like_count}；
 * 作者本人、ADMIN、PUBLISHED 允許。移除類（取消按讚、刪除自己的留言）對已下架文章仍允許且計數正確。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = CommentTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("comment 互動寫入「可讀才可互動」整合測試")
class CommentReadableInteractionIT {

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
    @Autowired private CommentRepository commentRepo;
    @Autowired private CommentLikeRepository commentLikeRepo;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private FileFacade fileFacade;

    private static final UUID AUTHOR = SeededUsers.USER_UUID;
    private static final UUID VIEWER = SeededUsers.OTHER_USER_UUID;
    private static final UUID ADMIN = SeededUsers.ADMIN_UUID;

    @AfterEach
    void cleanUp() {
        commentLikeRepo.deleteAll();
        commentRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private UUID newArticle(ArticleStatus status) {
        Article a = new Article();
        a.setUuid(UUID.randomUUID());
        a.setAuthorUuid(AUTHOR);
        a.setTitle("comment-readable-it");
        a.setSlug("comment-readable-it-" + UUID.randomUUID());
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

    private Comment seedComment(UUID articleUuid, UUID owner) {
        Comment c = new Comment();
        c.setUuid(UUID.randomUUID());
        c.setArticleUuid(articleUuid);
        c.setUserUuid(owner);
        c.setContent("seed");
        c.setContentHtml("<p>seed</p>");
        c.setLikeCount(0);
        return commentRepo.save(c);
    }

    private void setStatus(UUID articleUuid, ArticleStatus status) {
        Article a = articleRepo.findByUuid(articleUuid).orElseThrow();
        a.setStatus(status);
        articleRepo.save(a);
    }

    private int commentCount(UUID articleUuid) {
        return articleRepo.findByUuid(articleUuid).orElseThrow().getCommentCount();
    }

    private record Outcome(int status, String code, String message) {}

    private Outcome outcomeOf(MvcResult r) throws Exception {
        JsonNode body = objectMapper.readTree(r.getResponse().getContentAsString());
        return new Outcome(r.getResponse().getStatus(), body.path("code").asText(), body.path("message").asText());
    }

    private String commentBody(UUID parentUuid) throws Exception {
        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("hello");
        req.setParentUuid(parentUuid);
        return objectMapper.writeValueAsString(req);
    }

    @Test
    @DisplayName("POST 留言：他人 DRAFT 與不存在的回應完全相同，且不落列、comment_count 不變")
    void createComment_othersDraft_sameAsNotFound_noSideEffect() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);

        Outcome onDraft = outcomeOf(mockMvc.perform(post("/api/v1/articles/{u}/comments", draft)
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(commentBody(null))).andReturn());
        Outcome onMissing = outcomeOf(mockMvc.perform(post("/api/v1/articles/{u}/comments", UUID.randomUUID())
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(commentBody(null))).andReturn());

        assertThat(onDraft).isEqualTo(onMissing);
        assertThat(onDraft.code()).isEqualTo("A0201");
        assertThat(commentRepo.count()).isZero();
        assertThat(commentCount(draft)).isZero();
    }

    @Test
    @DisplayName("POST 留言：PENDING_REVIEW／REJECTED／ARCHIVED 他人一律被拒，comment_count 不變")
    void createComment_othersNonPublished_allRejected() throws Exception {
        for (ArticleStatus s : List.of(ArticleStatus.PENDING_REVIEW, ArticleStatus.REJECTED, ArticleStatus.ARCHIVED)) {
            UUID a = newArticle(s);
            mockMvc.perform(post("/api/v1/articles/{u}/comments", a)
                    .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(commentBody(null)))
                    .andExpect(status().isBadRequest());
            assertThat(commentCount(a)).as(s.name()).isZero();
        }
        assertThat(commentRepo.count()).isZero();
    }

    @Test
    @DisplayName("POST 回覆：他人 DRAFT 下的回覆同樣被拒（不因父留言存在而放行）")
    void createReply_othersDraft_rejected() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);
        Comment parent = seedComment(draft, AUTHOR);

        mockMvc.perform(post("/api/v1/articles/{u}/comments", draft)
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(commentBody(parent.getUuid())))
                .andExpect(status().isBadRequest());

        assertThat(commentRepo.count()).isEqualTo(1);
        assertThat(commentCount(draft)).isZero();
    }

    @Test
    @DisplayName("POST 留言：作者本人對自己的 DRAFT、ADMIN 對他人 DRAFT、任何人對 PUBLISHED 皆允許")
    void createComment_authorAdminPublished_allowed() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);
        mockMvc.perform(post("/api/v1/articles/{u}/comments", draft)
                .with(as(AUTHOR, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(commentBody(null)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/{u}/comments", draft)
                .with(as(ADMIN, Role.ADMIN)).contentType(MediaType.APPLICATION_JSON).content(commentBody(null)))
                .andExpect(status().isOk());
        assertThat(commentCount(draft)).isEqualTo(2);

        UUID published = newArticle(ArticleStatus.PUBLISHED);
        mockMvc.perform(post("/api/v1/articles/{u}/comments", published)
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(commentBody(null)))
                .andExpect(status().isOk());
        assertThat(commentCount(published)).isEqualTo(1);
    }

    @Test
    @DisplayName("POST 留言按讚：留言所屬文章對操作者不可讀 → 與文章不存在相同回應，like_count 不變、不落列")
    void likeComment_articleUnreadable_sameAsNotFound_noSideEffect() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);
        Comment c = seedComment(draft, AUTHOR);

        MvcResult r = mockMvc.perform(post("/api/v1/comments/{u}/like", c.getUuid())
                .with(as(VIEWER, Role.USER))).andReturn();
        Outcome onDraft = outcomeOf(r);

        assertThat(onDraft.status()).isEqualTo(400);
        assertThat(onDraft.code()).isEqualTo("A0201");
        assertThat(commentLikeRepo.count()).isZero();
        assertThat(commentRepo.findByUuid(c.getUuid()).orElseThrow().getLikeCount()).isZero();
    }

    @Test
    @DisplayName("POST 留言按讚：作者本人對自己 DRAFT 下的留言、PUBLISHED 下的留言允許")
    void likeComment_authorAndPublished_allowed() throws Exception {
        UUID draft = newArticle(ArticleStatus.DRAFT);
        Comment onDraft = seedComment(draft, AUTHOR);
        mockMvc.perform(post("/api/v1/comments/{u}/like", onDraft.getUuid()).with(as(AUTHOR, Role.USER)))
                .andExpect(status().isOk());
        assertThat(commentRepo.findByUuid(onDraft.getUuid()).orElseThrow().getLikeCount()).isEqualTo(1);

        UUID published = newArticle(ArticleStatus.PUBLISHED);
        Comment onPublished = seedComment(published, AUTHOR);
        mockMvc.perform(post("/api/v1/comments/{u}/like", onPublished.getUuid()).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());
        assertThat(commentRepo.findByUuid(onPublished.getUuid()).orElseThrow().getLikeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE 留言按讚：文章已下架（ARCHIVED）仍可取消，like_count 正確 -1")
    void unlikeComment_archived_allowedAndCountCorrect() throws Exception {
        UUID a = newArticle(ArticleStatus.PUBLISHED);
        Comment c = seedComment(a, AUTHOR);
        mockMvc.perform(post("/api/v1/comments/{u}/like", c.getUuid()).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());
        assertThat(commentRepo.findByUuid(c.getUuid()).orElseThrow().getLikeCount()).isEqualTo(1);
        setStatus(a, ArticleStatus.ARCHIVED);

        mockMvc.perform(delete("/api/v1/comments/{u}/like", c.getUuid()).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());

        assertThat(commentLikeRepo.count()).isZero();
        assertThat(commentRepo.findByUuid(c.getUuid()).orElseThrow().getLikeCount()).isZero();
    }

    @Test
    @DisplayName("DELETE 留言：文章已下架（ARCHIVED）仍可刪除自己的留言，comment_count 正確 -1")
    void deleteOwnComment_archived_allowedAndCountCorrect() throws Exception {
        UUID a = newArticle(ArticleStatus.PUBLISHED);
        mockMvc.perform(post("/api/v1/articles/{u}/comments", a)
                .with(as(VIEWER, Role.USER)).contentType(MediaType.APPLICATION_JSON).content(commentBody(null)))
                .andExpect(status().isOk());
        assertThat(commentCount(a)).isEqualTo(1);
        Comment mine = commentRepo.findAll().iterator().next();
        setStatus(a, ArticleStatus.ARCHIVED);

        mockMvc.perform(delete("/api/v1/comments/{u}", mine.getUuid()).with(as(VIEWER, Role.USER)))
                .andExpect(status().isOk());

        assertThat(commentRepo.findByUuid(mine.getUuid()).orElseThrow().getDeletedAt()).isNotNull();
        assertThat(commentCount(a)).isZero();
    }
}
