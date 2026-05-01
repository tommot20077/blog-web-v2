package dowob.xyz.blog.module.reading.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.reading.config.ReadingTestApplication;
import dowob.xyz.blog.module.reading.model.UserHighlight;
import dowob.xyz.blog.module.reading.model.dto.request.CreateHighlightRequest;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateHighlightRequest;
import dowob.xyz.blog.module.reading.repository.UserHighlightRepository;
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
import org.springframework.http.MediaType;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = ReadingTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("HighlightController 整合測試")
class HighlightControllerIT {

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
    @Autowired private UserHighlightRepository highlightRepo;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;

    private UUID articleUuid;
    private Long articleId;

    @BeforeEach
    void setup() {
        Article article = new Article();
        article.setUuid(UUID.randomUUID());
        article.setAuthorId(USER_ID);
        article.setTitle("Highlight IT");
        article.setSlug("highlight-it-" + UUID.randomUUID());
        article.setContent("test");
        article.setContentHtml("<p>test</p>");
        article.setStatus(ArticleStatus.PUBLISHED);
        article.setLikeCount(0); article.setCommentCount(0); article.setViewCount(0L);
        article.setCreatedAt(LocalDateTime.now()); article.setUpdatedAt(LocalDateTime.now());
        Article saved = articleRepo.save(article);
        articleUuid = saved.getUuid();
        articleId = saved.getId();
    }

    @AfterEach
    void cleanUp() {
        highlightRepo.deleteAll();
        articleRepo.deleteAll();
    }

    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    private UserHighlight createHighlight(Long ownerId) {
        UserHighlight h = new UserHighlight();
        h.setUuid(UUID.randomUUID());
        h.setUserId(ownerId); h.setArticleId(articleId);
        h.setSnippet("seed"); h.setPrefix(""); h.setSuffix("");
        h.setColor("#FFEB3B");
        return highlightRepo.save(h);
    }

    @Test
    @DisplayName("POST /highlights - 200 with valid request")
    void post_validRequest_returns200() throws Exception {
        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("hello world");
        req.setColor("#FFEB3B");
        req.setNote("note");

        mockMvc.perform(post("/api/v1/articles/{uuid}/highlights", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.snippet").value("hello world"));
    }

    @Test
    @DisplayName("POST - 400 if snippet > 500 chars")
    void post_snippetTooLong_returns400() throws Exception {
        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("a".repeat(501));
        req.setColor("#FFEB3B");

        mockMvc.perform(post("/api/v1/articles/{uuid}/highlights", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST - 400 if color invalid hex")
    void post_invalidColor_returns400() throws Exception {
        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("test");
        req.setColor("yellow");

        mockMvc.perform(post("/api/v1/articles/{uuid}/highlights", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /highlights - 只回我的")
    void list_returnsOnlyMine() throws Exception {
        UserHighlight mine = new UserHighlight();
        mine.setUuid(UUID.randomUUID());
        mine.setUserId(USER_ID); mine.setArticleId(articleId);
        mine.setSnippet("mine"); mine.setPrefix(""); mine.setSuffix(""); mine.setColor("#FFEB3B");
        highlightRepo.save(mine);

        UserHighlight other = new UserHighlight();
        other.setUuid(UUID.randomUUID());
        other.setUserId(OTHER_USER_ID); other.setArticleId(articleId);
        other.setSnippet("other"); other.setPrefix(""); other.setSuffix(""); other.setColor("#FFEB3B");
        highlightRepo.save(other);

        mockMvc.perform(get("/api/v1/articles/{uuid}/highlights", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].snippet").value("mine"));
    }

    @Test
    @DisplayName("PUT /highlights/{uuid} - 200 by owner")
    void put_byOwner_returns200() throws Exception {
        UserHighlight h = createHighlight(USER_ID);

        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");

        mockMvc.perform(put("/api/v1/highlights/{uuid}", h.getUuid())
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.color").value("#00FF00"));
    }

    @Test
    @DisplayName("PUT - 400 (R0202) by non-owner")
    void put_byNonOwner_returns400_R0202() throws Exception {
        UserHighlight h = createHighlight(USER_ID);

        UpdateHighlightRequest req = new UpdateHighlightRequest();
        req.setColor("#00FF00");

        mockMvc.perform(put("/api/v1/highlights/{uuid}", h.getUuid())
                .with(asUser(OTHER_USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("R0202"));
    }

    @Test
    @DisplayName("DELETE /highlights/{uuid} - 200 by owner")
    void delete_byOwner_returns200() throws Exception {
        UserHighlight h = createHighlight(USER_ID);

        mockMvc.perform(delete("/api/v1/highlights/{uuid}", h.getUuid())
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk());

        assertThat(highlightRepo.findByUuid(h.getUuid())).isEmpty();
    }

    @Test
    @DisplayName("POST - 401 unauthenticated")
    void post_unauthenticated_returns401() throws Exception {
        CreateHighlightRequest req = new CreateHighlightRequest();
        req.setSnippet("test"); req.setColor("#FFEB3B");

        mockMvc.perform(post("/api/v1/articles/{uuid}/highlights", articleUuid)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isUnauthorized());
    }
}
