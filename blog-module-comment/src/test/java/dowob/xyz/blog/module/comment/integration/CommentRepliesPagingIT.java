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

/**
 * D3（PERF-22）：回覆分頁——列表只帶前 3 則回覆 + {@code replyCount}，以及
 * {@code GET /api/v1/comments/{commentUuid}/replies} 回覆分頁端點。
 *
 * <p>真實 PostgreSQL（驗證 LATERAL／分頁 SQL）、真實 SecurityConfig（驗證匿名可讀的 permitAll 規則）
 * 與真實 article 模組（驗證「文章可讀才可讀」）。</p>
 *
 * @author Yuan
 */
@SpringBootTest(classes = CommentTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("D3 回覆分頁 — 列表前 3 則 + replyCount、回覆分頁端點")
class CommentRepliesPagingIT {

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
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;

    private UUID articleUuid;
    private Comment top;
    private List<Comment> replies;

    @BeforeEach
    void setup() {
        articleUuid = saveArticle(ArticleStatus.PUBLISHED);
        top = saveComment(articleUuid, null);
        replies = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            replies.add(saveComment(articleUuid, top.getId()));
        }
    }

    @AfterEach
    void cleanUp() {
        commentLikeRepo.deleteAll();
        commentRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private UUID saveArticle(ArticleStatus status) {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorUuid(SeededUsers.USER_UUID);
        article.setTitle("Replies paging");
        article.setSlug("replies-paging-" + UUID.randomUUID());
        article.setContent("test");
        article.setContentHtml("<p>test</p>");
        article.setStatus(status);
        article.setLikeCount(0);
        article.setCommentCount(0);
        article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now());
        article.setUpdatedAt(LocalDateTime.now());
        return articleRepo.save(article).getUuid();
    }

    private Comment saveComment(UUID forArticle, Long parentId) {
        Comment c = new Comment();
        c.setUuid(UUID.randomUUID());
        c.setArticleUuid(forArticle);
        c.setParentId(parentId);
        c.setUserUuid(SeededUsers.USER_UUID);
        c.setContent("c");
        c.setContentHtml("<p>c</p>");
        c.setLikeCount(0);
        return commentRepo.save(c);
    }

    private RequestPostProcessor asUser(Long userId, Role role) {
        UUID userUuid = new UUID(0L, userId);
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        return SecurityMockMvcRequestPostProcessors.authentication(
                new UsernamePasswordAuthenticationToken(new AuthUser(userUuid, role), null, authorities));
    }

    private JsonNode getJson(String url, int expectedStatus, String... params) throws Exception {
        var request = get(url);
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        String body = mockMvc.perform(request).andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    @DisplayName("有 5 則回覆時，列表只帶最早的 3 則，replyCount = 5（匿名可讀）")
    void list_carriesOnlyFirstThreeReplies_withReplyCount() throws Exception {
        JsonNode data = getJson("/api/v1/articles/" + articleUuid + "/comments", 200).get("data");

        JsonNode topNode = data.get("topLevels").get("records").get(0);
        assertThat(topNode.get("replyCount").asInt()).isEqualTo(5);
        assertThat(topNode.get("replies")).hasSize(3);
        assertThat(topNode.get("replies").get(0).get("uuid").asText()).isEqualTo(replies.get(0).getUuid().toString());
        assertThat(topNode.get("replies").get(2).get("uuid").asText()).isEqualTo(replies.get(2).getUuid().toString());
        assertThat(data.get("totalCommentCount").asInt()).as("1 頂層 + 5 回覆（這裡直接寫 DB，未經 service 增計數，故看留言區口徑）")
                .isEqualTo(6);
    }

    @Test
    @DisplayName("回覆分頁 page=1 size=2 回 2 則且總數 5；page=3 size=2 回最後 1 則；順序與列表一致")
    void repliesEndpoint_pagesAndTotals() throws Exception {
        String url = "/api/v1/comments/" + top.getUuid() + "/replies";

        JsonNode page1 = getJson(url, 200, "page", "1", "size", "2").get("data");
        assertThat(page1.get("records")).hasSize(2);
        assertThat(page1.get("total").asInt()).isEqualTo(5);
        assertThat(page1.get("current").asInt()).isEqualTo(1);
        assertThat(page1.get("records").get(0).get("uuid").asText()).isEqualTo(replies.get(0).getUuid().toString());
        assertThat(page1.get("records").get(0).get("parentUuid").asText()).isEqualTo(top.getUuid().toString());
        assertThat(page1.get("records").get(0).get("replies")).isEmpty();

        JsonNode page3 = getJson(url, 200, "page", "3", "size", "2").get("data");
        assertThat(page3.get("records")).hasSize(1);
        assertThat(page3.get("records").get(0).get("uuid").asText()).isEqualTo(replies.get(4).getUuid().toString());
    }

    @Test
    @DisplayName("已刪除的回覆不算在 replyCount，也不在列表與分頁端點回傳")
    void deletedReplies_excludedFromCountAndBothEndpoints() throws Exception {
        jdbc.update("UPDATE comments SET deleted_at = now(), deleted_by_role = 'AUTHOR' WHERE id = ?",
                replies.get(0).getId());

        JsonNode topNode = getJson("/api/v1/articles/" + articleUuid + "/comments", 200)
                .get("data").get("topLevels").get("records").get(0);
        assertThat(topNode.get("replyCount").asInt()).isEqualTo(4);
        assertThat(topNode.get("replies")).hasSize(3);
        assertThat(topNode.get("replies").get(0).get("uuid").asText()).isEqualTo(replies.get(1).getUuid().toString());

        JsonNode page = getJson("/api/v1/comments/" + top.getUuid() + "/replies", 200, "size", "100").get("data");
        assertThat(page.get("total").asInt()).isEqualTo(4);
        assertThat(page.get("records")).extracting(n -> n.get("uuid").asText())
                .doesNotContain(replies.get(0).getUuid().toString());
    }

    @Test
    @DisplayName("頂層已軟刪除（墓碑）時，其存活的回覆仍可由分頁端點取得")
    void tombstonedTopLevel_repliesStillPageable() throws Exception {
        jdbc.update("UPDATE comments SET deleted_at = now(), deleted_by_role = 'AUTHOR' WHERE id = ?", top.getId());

        JsonNode page = getJson("/api/v1/comments/" + top.getUuid() + "/replies", 200).get("data");

        assertThat(page.get("total").asInt()).isEqualTo(5);
    }

    @Test
    @DisplayName("文章不可讀（草稿）：匿名與無關使用者回 200 空頁；作者本人與 ADMIN 可讀")
    void unreadableArticle_isEmptyPage_forAnonymous_butAuthorAndAdminCanRead() throws Exception {
        UUID draftArticle = saveArticle(ArticleStatus.DRAFT);
        Comment draftTop = saveComment(draftArticle, null);
        saveComment(draftArticle, draftTop.getId());
        String url = "/api/v1/comments/" + draftTop.getUuid() + "/replies";

        JsonNode anonymous = getJson(url, 200);
        assertThat(anonymous.get("data").get("records")).isEmpty();
        assertThat(anonymous.get("data").get("total").asInt()).isZero();

        String author = mockMvc.perform(get(url).with(asUser(SeededUsers.USER_ID, Role.USER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(author).get("data").get("total").asInt()).isEqualTo(1);
        String admin = mockMvc.perform(get(url).with(asUser(SeededUsers.ADMIN_ID, Role.ADMIN)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(admin).get("data").get("total").asInt()).isEqualTo(1);
        String other = mockMvc.perform(get(url).with(asUser(SeededUsers.OTHER_USER_ID, Role.USER)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(other).get("data").get("total").asInt()).isZero();
    }

    @Test
    @DisplayName("傳入回覆的 uuid、不存在的 uuid、不可讀文章：三者回應完全相同（200 空頁），不形成存在性探測")
    void replyUuid_unknownUuid_unreadableArticle_haveIdenticalResponses() throws Exception {
        UUID draftArticle = saveArticle(ArticleStatus.DRAFT);
        Comment draftTop = saveComment(draftArticle, null);

        String onReply = mockMvc.perform(get("/api/v1/comments/" + replies.get(0).getUuid() + "/replies"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String unknown = mockMvc.perform(get("/api/v1/comments/" + UUID.randomUUID() + "/replies"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String unreadable = mockMvc.perform(get("/api/v1/comments/" + draftTop.getUuid() + "/replies"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        for (String body : new String[]{onReply, unknown, unreadable}) {
            JsonNode data = objectMapper.readTree(body).get("data");
            assertThat(data.get("records")).isEmpty();
            assertThat(data.get("total").asInt()).isZero();
        }
        assertThat(stripTimestamp(unknown)).isEqualTo(stripTimestamp(onReply)).isEqualTo(stripTimestamp(unreadable));
    }

    private String stripTimestamp(String body) {
        return body.replaceAll("\"timestamp\"\\s*:\\s*[^,}]+,?", "");
    }

    @Test
    @DisplayName("分頁參數沿用 PageQuery：page 從 1 起算、size 上限 100，超出回 400")
    void pageQueryBounds_returns400() throws Exception {
        String url = "/api/v1/comments/" + top.getUuid() + "/replies";

        getJson(url, 400, "page", "0");
        getJson(url, 400, "size", "101");
        getJson(url, 400, "size", "0");
        getJson(url, 200, "size", "100");
    }
}
