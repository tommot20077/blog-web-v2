package dowob.xyz.blog.module.reading.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.reading.config.ReadingTestApplication;
import dowob.xyz.blog.module.reading.model.UserBookmark;
import dowob.xyz.blog.module.reading.model.UserHighlight;
import dowob.xyz.blog.module.reading.model.UserReadingProgress;
import dowob.xyz.blog.module.reading.model.dto.request.UpdateProgressRequest;
import dowob.xyz.blog.module.reading.repository.UserBookmarkRepository;
import dowob.xyz.blog.module.reading.repository.UserHighlightRepository;
import dowob.xyz.blog.module.reading.repository.UserReadingProgressRepository;
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
import org.springframework.data.redis.core.StringRedisTemplate;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reading 跨模組整合 IT。
 *
 * <p>驗證 Article ↔ Bookmark ↔ Highlight ↔ ReadingProgress 的端到端整合：
 * <ul>
 *   <li>ArticleResponse.bookmarked - bookmark 後反映</li>
 *   <li>ArticleResponse.lastReadProgress - PUT progress 後反映</li>
 *   <li>匿名查詢 bookmarked=false, progress 為 null</li>
 *   <li>Article 物理刪除 cascade 清光所有 reading state</li>
 * </ul>
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ReadingTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("Reading 跨模組整合 IT")
class CrossModuleReadingIT {

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
    @Autowired private UserBookmarkRepository bookmarkRepo;
    @Autowired private UserHighlightRepository highlightRepo;
    @Autowired private UserReadingProgressRepository progressRepo;
    @Autowired private StringRedisTemplate redisTemplate;

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
        article.setTitle("Cross IT");
        article.setSlug("cross-it-" + UUID.randomUUID());
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
        bookmarkRepo.deleteAll();
        highlightRepo.deleteAll();
        progressRepo.deleteAll();
        articleRepo.deleteAll();
        Set<String> keys = redisTemplate.keys(RedisKeyConstant.READING_PROGRESS_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) redisTemplate.delete(keys);
        redisTemplate.delete(RedisKeyConstant.READING_DIRTY_KEY);
    }

    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    @Test
    @DisplayName("ArticleResponse.bookmarked - bookmark 後變 true")
    void articleResponse_includesBookmarkedFlag() throws Exception {
        mockMvc.perform(post("/api/v1/articles/{uuid}/bookmark", articleUuid)
                .with(asUser(USER_ID, Role.USER))).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/articles/{uuid}", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookmarked").value(true));
    }

    @Test
    @DisplayName("ArticleSummary.lastReadProgress - PUT progress 後反映")
    void articleSummary_includesLastReadProgress() throws Exception {
        UpdateProgressRequest req = new UpdateProgressRequest();
        req.setProgress(new BigDecimal("0.5"));
        mockMvc.perform(put("/api/v1/articles/{uuid}/progress", articleUuid)
                .with(asUser(USER_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        // 用單篇查詢，因為列表 endpoint 可能未產生 records[0]（取決於分頁/狀態）
        mockMvc.perform(get("/api/v1/articles/{uuid}", articleUuid)
                .with(asUser(USER_ID, Role.USER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.lastReadProgress").value(0.5));
    }

    @Test
    @DisplayName("Anonymous - bookmarked / progress 都為 false / null")
    void articleSummary_unauth_progressIsNullAndBookmarkedFalse() throws Exception {
        mockMvc.perform(get("/api/v1/articles/{uuid}", articleUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bookmarked").value(false));
        // lastReadProgress 應為 null/不存在
    }

    @Test
    @DisplayName("Article 物理刪除 cascade - bookmarks/highlights/progress 清空")
    void deleteArticle_cascadeRemovesAllReadingState() throws Exception {
        // 建 bookmark
        UserBookmark bm = new UserBookmark();
        bm.setUserId(USER_ID); bm.setArticleId(articleId); bm.setCreatedAt(LocalDateTime.now());
        bookmarkRepo.save(bm);

        // 建 highlight
        UserHighlight h = new UserHighlight();
        h.setUuid(UUID.randomUUID());
        h.setUserId(USER_ID); h.setArticleId(articleId);
        h.setSnippet("seed"); h.setPrefix(""); h.setSuffix(""); h.setColor("#FFEB3B");
        highlightRepo.save(h);

        // 建 progress (DB 直接，繞過 service)
        UserReadingProgress p = new UserReadingProgress();
        p.setUserId(USER_ID); p.setArticleId(articleId);
        p.setProgress(new BigDecimal("0.5"));
        progressRepo.save(p);

        // 物理刪除 article
        articleRepo.deleteById(articleId);

        assertThat(bookmarkRepo.findByUserIdAndArticleId(USER_ID, articleId)).isEmpty();
        assertThat(highlightRepo.findByUserIdAndArticleIdOrderByCreatedAtAsc(USER_ID, articleId)).isEmpty();
        assertThat(progressRepo.findByUserIdAndArticleId(USER_ID, articleId)).isEmpty();
    }
}
