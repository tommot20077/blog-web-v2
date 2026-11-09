package dowob.xyz.blog.module.comment.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.security.AuthUser;
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
import dowob.xyz.blog.module.comment.consumer.CommentArticleDeletedConsumer;
import dowob.xyz.blog.module.comment.repository.CommentRepository;
import dowob.xyz.blog.module.comment.support.SeededUsers;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
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

import com.fasterxml.jackson.databind.JsonNode;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;

/**
 * B5（DATA-06，決策 B5）：留言區 {@code totalCommentCount} 與 {@code articles.comment_count} 的口徑一致性。
 *
 * <p>兩者都必須等於「未刪除的留言數」（頂層加回覆合計），<b>不含任何已刪除的留言，包括頂層墓碑</b>。
 * 用真實 PostgreSQL 與真實 article 模組（經 {@code ArticleFacade} 增減計數），逐一走過
 * 新增、刪除有回覆的頂層、刪除沒有回覆的頂層、刪除回覆、admin 刪除、重複刪除等路徑，
 * 每一步都同時斷言留言區的數字、DB 欄位與「實際未刪除的列數」三者相等。</p>
 *
 * @author Yuan
 */
@SpringBootTest(classes = CommentTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("B5 留言數口徑一致 — totalCommentCount 與 articles.comment_count")
class CommentCountConsistencyIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:18-alpine")
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:8.8.0-alpine");

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
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;

    private UUID articleUuid;

    @BeforeEach
    void setup() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorUuid(SeededUsers.USER_UUID);
        article.setTitle("Count consistency");
        article.setSlug("count-consistency-" + UUID.randomUUID());
        article.setContent("test");
        article.setContentHtml("<p>test</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        articleUuid = articleRepo.save(article).getUuid();
    }

    @AfterEach
    void cleanUp() {
        commentLikeRepo.deleteAll();
        commentRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private RequestPostProcessor asUser(Long userId, Role role) {
        UUID userUuid = new UUID(0L, userId);
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(userUuid, role), null, authorities));
    }

    /** 以 API 建立留言，回傳其 uuid；parent 為 null 表示頂層 */
    private UUID create(UUID parent) throws Exception {
        CreateCommentRequest req = new CreateCommentRequest();
        req.setContent("c-" + UUID.randomUUID());
        req.setParentUuid(parent);
        String body = mockMvc.perform(post("/api/v1/articles/{uuid}/comments", articleUuid)
                        .with(asUser(SeededUsers.USER_ID, Role.USER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(objectMapper.readTree(body).get("data").get("uuid").asText());
    }

    private void deleteAsOwner(UUID commentUuid) throws Exception {
        mockMvc.perform(delete("/api/v1/comments/{uuid}", commentUuid).with(asUser(SeededUsers.USER_ID, Role.USER)))
                .andExpect(status().isOk());
    }

    private void deleteAsAdmin(UUID commentUuid) throws Exception {
        mockMvc.perform(delete("/api/v1/comments/{uuid}", commentUuid).with(asUser(SeededUsers.ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk());
    }

    /** 留言區顯示的總數（totalCommentCount） */
    private int sectionTotal() throws Exception {
        String body = mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid).param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("totalCommentCount").asInt();
    }

    private int articleCommentCount() {
        return articleRepo.findByUuid(articleUuid).orElseThrow().getCommentCount();
    }

    private int liveRowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM comments WHERE article_uuid = ? AND deleted_at IS NULL",
                Integer.class, articleUuid);
    }

    /** 三個數字必須相等且等於預期 */
    private void assertAllEqual(int expected, String step) throws Exception {
        assertThat(sectionTotal()).as(step + "：留言區 totalCommentCount").isEqualTo(expected);
        assertThat(articleCommentCount()).as(step + "：articles.comment_count").isEqualTo(expected);
        assertThat(liveRowCount()).as(step + "：實際未刪除列數").isEqualTo(expected);
    }

    @Test
    @DisplayName("頂層有回覆時刪除頂層：墓碑仍顯示，但 totalCommentCount 與 comment_count 都減 1")
    void deleteTopLevelWithReplies_tombstoneStaysButBothCountsDrop() throws Exception {
        UUID top = create(null);
        create(top);
        create(top);
        assertAllEqual(3, "建立 1 頂層 + 2 回覆");

        deleteAsOwner(top);

        assertAllEqual(2, "刪除有回覆的頂層");
        String body = mockMvc.perform(get("/api/v1/articles/{uuid}/comments", articleUuid))
                .andReturn().getResponse().getContentAsString();
        JsonNode records = objectMapper.readTree(body).get("data").get("topLevels").get("records");
        assertThat(records).as("墓碑仍保留在列表").hasSize(1);
        assertThat(records.get(0).get("deleted").asBoolean()).isTrue();
        assertThat(records.get(0).get("replies")).as("回覆仍在").hasSize(2);
    }

    @Test
    @DisplayName("刪除沒有回覆的頂層：兩者都減 1")
    void deleteTopLevelWithoutReplies_bothDrop() throws Exception {
        UUID top = create(null);
        create(null);
        assertAllEqual(2, "建立 2 頂層");

        deleteAsOwner(top);

        assertAllEqual(1, "刪除沒有回覆的頂層");
    }

    @Test
    @DisplayName("刪除回覆：兩者都減 1")
    void deleteReply_bothDrop() throws Exception {
        UUID top = create(null);
        UUID reply = create(top);
        create(top);
        assertAllEqual(3, "建立 1 頂層 + 2 回覆");

        deleteAsOwner(reply);

        assertAllEqual(2, "刪除回覆");
    }

    @Test
    @DisplayName("admin 刪除他人頂層（有回覆）與回覆：兩者同步減少")
    void adminDelete_bothDrop() throws Exception {
        UUID top = create(null);
        UUID reply = create(top);
        create(top);
        assertAllEqual(3, "建立");

        deleteAsAdmin(top);
        assertAllEqual(2, "admin 刪除有回覆的頂層");
        deleteAsAdmin(reply);
        assertAllEqual(1, "admin 刪除回覆");
    }

    @Test
    @DisplayName("重複刪除（idempotent）不會二次扣減，兩個數字維持一致")
    void doubleDelete_noDoubleDecrement() throws Exception {
        UUID top = create(null);
        create(top);
        assertAllEqual(2, "建立");

        deleteAsOwner(top);
        deleteAsOwner(top);

        assertAllEqual(1, "重複刪除頂層");
    }

    @Test
    @DisplayName("多種刪除組合後，三個數字始終相等：刪光所有頂層與回覆後為 0")
    void mixedDeleteSequence_numbersNeverDiverge() throws Exception {
        UUID topA = create(null);
        UUID topB = create(null);
        UUID a1 = create(topA);
        UUID a2 = create(topA);
        UUID b1 = create(topB);
        assertAllEqual(5, "建立 2 頂層 + 3 回覆");

        deleteAsOwner(topA);
        assertAllEqual(4, "刪 topA（有回覆，留墓碑）");
        deleteAsOwner(a1);
        assertAllEqual(3, "刪 topA 墓碑下的回覆 a1");
        deleteAsAdmin(b1);
        assertAllEqual(2, "admin 刪 b1");
        deleteAsOwner(topB);
        assertAllEqual(1, "刪 topB（回覆已刪光）");
        deleteAsOwner(a2);
        assertAllEqual(0, "刪光");
    }
}
