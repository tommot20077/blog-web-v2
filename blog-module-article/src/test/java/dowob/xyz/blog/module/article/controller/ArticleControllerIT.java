package dowob.xyz.blog.module.article.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.article.config.ArticleTestApplication;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.RejectArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import org.junit.jupiter.api.AfterEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文章 Controller 整合測試
 *
 * <p>
 * 使用 TestContainers 啟動 PostgreSQL，測試完整 API 流程。
 * 使用 Spring Security Test 的 RequestPostProcessor 模擬認證，
 * 確保 SecurityContext 在完整過濾器鏈中正確傳遞。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("ArticleController 整合測試")
class ArticleControllerIT {

    /**
     * PostgreSQL TestContainer
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("blog_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Redis TestContainer
     */
    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    /**
     * 動態注入容器連線設定
     *
     * @param registry Spring 動態屬性源
     */
    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.url", redis::getRedisURI);
    }

    /**
     * MockMvc 測試客戶端
     */
    @Autowired
    private MockMvc mockMvc;

    /**
     * JSON 序列化工具
     */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 文章 Repository（用於測試清理）
     */
    @Autowired
    private ArticleRepository articleRepository;

    /**
     * Mock RabbitMQ ConnectionFactory（避免啟動時找不到 Bean）
     */
    @MockitoBean
    private ConnectionFactory connectionFactory;

    /**
     * Mock RabbitTemplate（避免 IT 真正發送 MQ 訊息）
     */
    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    /**
     * Mock TagFacade（避免依賴 Tag Module）
     */
    @MockitoBean
    private TagFacade tagFacade;

    /**
     * Mock UserFacade（避免依賴 User Module）
     */
    @MockitoBean
    private UserFacade userFacade;

    /**
     * Mock UserAuthService（避免依賴 User Module 實作）
     */
    @MockitoBean
    private UserAuthService userAuthService;

    /**
     * Mock ReadingFacade（避免依賴 Reading Module 實作）
     */
    @MockitoBean
    private ReadingFacade readingFacade;

    /**
     * Mock SeriesFacade（避免依賴 Series Module 實作）
     */
    @MockitoBean
    private SeriesFacade seriesFacade;

    /**
     * 測試用預設作者 ID（對應 V1 Migration 預設資料）
     */
    private static final Long AUTHOR_ID = 1L;

    /**
     * 測試用作者 UUID
     */
    private static final UUID AUTHOR_UUID = UUID.randomUUID();

    /**
     * 每次測試後清理文章資料
     */
    @AfterEach
    void cleanUp() {
        articleRepository.deleteAll();
    }

    /**
     * 建立模擬認證的 RequestPostProcessor
     *
     * <p>
     * 使用 Spring Security Test 提供的機制，
     * 確保認證物件在完整過濾器鏈中正確傳遞。
     * </p>
     *
     * @param userId 用戶 ID
     * @param role   角色
     * @return RequestPostProcessor
     */
    /**
     * 建立模擬認證的 RequestPostProcessor
     *
     * <p>
     * 使用 Spring Security Test 提供的機制，
     * 確保認證物件在完整過濾器鏈中正確傳遞。
     * 同時注入角色對應的所有 {@link dowob.xyz.blog.common.api.enums.Permission} 作為 Authority，
     * 以支援 {@code @PreAuthorize("hasAuthority('...')")} 的宣告式存取控制。
     * </p>
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
    @DisplayName("GET /api/v1/articles - 公開取得已發布文章列表（空列表）")
    void getPublishedArticles_emptyList() throws Exception {
        mockMvc.perform(get("/api/v1/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/articles → POST publish → GET 完整流程")
    void createAndGetArticle_fullFlow() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立文章 */
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("整合測試文章");
        createRequest.setContent("整合測試內容 Markdown");
        createRequest.setSummary("摘要");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        /** 發布文章 */
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        /** 匿名取得已發布文章 */
        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("整合測試文章"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        /** 確認列表中有此文章 */
        mockMvc.perform(get("/api/v1/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("PUT /api/v1/articles/{uuid} - 作者更新自己的文章")
    void updateArticle_authorUpdatesOwn() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立文章 */
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("原始標題");
        createRequest.setContent("原始內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        /** 更新文章 */
        UpdateArticleRequest updateRequest = new UpdateArticleRequest();
        updateRequest.setTitle("更新後標題");

        mockMvc.perform(put("/api/v1/articles/" + uuid)
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("更新後標題"));
    }

    @Test
    @DisplayName("DELETE /api/v1/articles/{uuid} - 作者刪除自己的文章")
    void deleteArticle_authorDeletesOwn() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立文章 */
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("待刪文章");
        createRequest.setContent("內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        /** 刪除文章 */
        mockMvc.perform(delete("/api/v1/articles/" + uuid)
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 確認已刪除（匿名也找不到） */
        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0201"));
    }

    @Test
    @DisplayName("GET /api/v1/articles/me - 取得我的文章列表")
    void getMyArticles_success() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立兩篇文章 */
        for (int i = 1; i <= 2; i++) {
            CreateArticleRequest req = new CreateArticleRequest();
            req.setTitle("我的文章 " + i);
            req.setContent("內容 " + i);
            mockMvc.perform(post("/api/v1/articles")
                    .with(asUser(AUTHOR_ID, Role.AUTHOR))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }

        mockMvc.perform(get("/api/v1/articles/me")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("POST /api/v1/articles - title 為空 → 回傳 code=400")
    void createArticle_emptyTitle_validationError() throws Exception {
        CreateArticleRequest request = new CreateArticleRequest();
        request.setTitle("");
        request.setContent("內容");

        mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/publish - 非法狀態轉換（PUBLISHED→PUBLISHED）→ A0204")
    void publishArticle_alreadyPublished_error() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立並發布文章 */
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("待發布");
        createRequest.setContent("內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 嘗試再次發布 → 非法狀態轉換 */
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0204"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/reject - Admin 駁回待審文章 → 狀態為 REJECTED")
    void rejectArticle_adminRejects_success() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立文章並送審 */
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("待審文章");
        createRequest.setContent("內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        /** 送審 */
        UpdateArticleRequest submitRequest = new UpdateArticleRequest();
        submitRequest.setStatus(dowob.xyz.blog.common.api.enums.ArticleStatus.PENDING_REVIEW);
        mockMvc.perform(put("/api/v1/articles/" + uuid)
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(submitRequest)))
                .andExpect(status().isOk());

        /** Admin 駁回 */
        RejectArticleRequest rejectRequest = new RejectArticleRequest();
        rejectRequest.setReason("內容不符合規範");

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/reject")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rejectRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    @Test
    @DisplayName("GET /api/admin/articles/pending - ADMIN 取得待審列表成功（200）")
    void getPendingArticles_adminSuccess() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立並送審一篇文章 */
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("待審文章");
        createRequest.setContent("內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        UpdateArticleRequest submitRequest = new UpdateArticleRequest();
        submitRequest.setStatus(dowob.xyz.blog.common.api.enums.ArticleStatus.PENDING_REVIEW);
        mockMvc.perform(put("/api/v1/articles/" + uuid)
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(submitRequest)))
                .andExpect(status().isOk());

        /** Admin 查詢待審列表 */
        mockMvc.perform(get("/api/v1/admin/articles/pending")
                .with(asUser(AUTHOR_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records").isArray());
    }

    @Test
    @DisplayName("GET /api/admin/articles/pending - AUTHOR 呼叫 → 403")
    void getPendingArticles_nonAdminForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/articles/pending")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/reject - AUTHOR（無 SYSTEM_CONFIG 權限）→ 403")
    void rejectArticle_authorForbidden_returns403() throws Exception {
        RejectArticleRequest rejectRequest = new RejectArticleRequest();
        rejectRequest.setReason("想試試駁回");

        mockMvc.perform(post("/api/v1/articles/" + UUID.randomUUID() + "/reject")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rejectRequest)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/articles/me - 匿名存取 → 需登入，回傳 401")
    void getMyArticles_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/articles/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/articles - Role.USER 呼叫應回傳 403（缺少 ARTICLE_CREATE）")
    void createArticle_userRole_forbidden() throws Exception {
        CreateArticleRequest request = new CreateArticleRequest();
        request.setTitle("USER 發文");
        request.setContent("不應被允許");

        mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/v1/articles/{uuid} - Role.USER 呼叫應回傳 403（缺少 ARTICLE_EDIT）")
    void updateArticle_userRole_forbidden() throws Exception {
        UpdateArticleRequest request = new UpdateArticleRequest();
        request.setTitle("嘗試更新");

        mockMvc.perform(put("/api/v1/articles/" + UUID.randomUUID())
                .with(asUser(AUTHOR_ID, Role.USER))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("DELETE /api/v1/articles/{uuid} - Role.USER 呼叫應回傳 403（缺少 ARTICLE_DELETE）")
    void deleteArticle_userRole_forbidden() throws Exception {
        mockMvc.perform(delete("/api/v1/articles/" + UUID.randomUUID())
                .with(asUser(AUTHOR_ID, Role.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/articles - Role.ADMIN 呼叫應正常通過（擁有 ARTICLE_CREATE）")
    void createArticle_adminRole_success() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("AdminUser"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("adminuser"));

        CreateArticleRequest request = new CreateArticleRequest();
        request.setTitle("Admin 發文");
        request.setContent("管理員內容");

        mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").exists());
    }

    @Test
    @DisplayName("GET /api/v1/articles/{uuid} - 回應中包含 authorNickname 欄位")
    void getArticle_responseContainsAuthorNickname() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立文章並發布 */
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("暱稱驗證文章");
        createRequest.setContent("驗證 authorNickname 欄位");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 匿名取得已發布文章，驗證 authorNickname */
        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.authorNickname").value("TestAuthor"));
    }

    @Test
    @DisplayName("GET /api/v1/articles/{uuid} - 回應中包含 contentHtml 欄位（含 HTML 標籤）")
    void getArticle_shouldReturnContentHtml() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));

        /** 建立文章並發布 */
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("HTML 渲染驗證");
        createRequest.setContent("這是 Markdown 段落內容。");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 匿名取得已發布文章，驗證 contentHtml 存在且含 HTML 標籤 */
        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contentHtml").exists())
                .andExpect(jsonPath("$.data.contentHtml").value(org.hamcrest.Matchers.containsString("<")));
    }

    @Test
    @DisplayName("GET /api/v1/articles/slug/{slug} - 以 slug 取得已發布文章")
    void getArticleBySlug_success() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("Slug 測試文章");
        createRequest.setContent("Slug 測試內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        // 從已發布文章的 GET 回應中取得 slug（EditorArticleResponse 不含 slug）
        String getResponse = mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String slug = objectMapper.readTree(getResponse).path("data").path("slug").asText();

        mockMvc.perform(get("/api/v1/articles/slug/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Slug 測試文章"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/submit - 作者送審草稿 → 狀態為 PENDING_REVIEW")
    void submitForReview_authorSubmitsDraft_success() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("送審測試文章");
        createRequest.setContent("送審測試內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/submit")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
    }

    @Test
    @DisplayName("GET /api/v1/articles?categorySlug=xxx - 以分類 slug 篩選已發布文章")
    void getPublishedArticles_withCategorySlug() throws Exception {
        mockMvc.perform(get("/api/v1/articles")
                .param("categorySlug", "nonexistent-category"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/articles/me?status=DRAFT - 以狀態篩選我的文章")
    void getMyArticles_withStatusFilter() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("草稿篩選測試");
        createRequest.setContent("草稿內容");

        mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/articles/me")
                .param("status", "DRAFT")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    @DisplayName("GET /api/v1/articles/{uuid} - 同 IP 連續兩次存取，MQ 瀏覽事件只發送一次（防刷）")
    void getArticle_shouldNotCountViewTwice_whenSameIpWithinWindow() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));

        /** 建立文章並發布 */
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("防刷測試文章");
        createRequest.setContent("防刷測試內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 第一次存取：應發送 MQ 瀏覽事件並回應 200 */
        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk());

        /** 第二次存取（同 IP，5 分鐘內）：不再發送 MQ 事件 */
        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk());

        /** 驗證 MQ 瀏覽事件（ArticleViewedEvent）整個流程只發送過一次 */
        org.mockito.Mockito.verify(rabbitTemplate, org.mockito.Mockito.times(1))
                .convertAndSend(
                        org.mockito.ArgumentMatchers.eq(dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig.EXCHANGE),
                        org.mockito.ArgumentMatchers.eq(dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig.ROUTING_KEY_VIEWED),
                        org.mockito.ArgumentMatchers.any(dowob.xyz.blog.module.article.event.ArticleViewedEvent.class));
    }

    /**
     * 驗證：取得文章時發送的 viewed 事件不得在交易作用域內送出。
     *
     * <p>規範依據：{@code ai-docs/code-standards.md} §Transaction+MQ 時序——禁止在 @Transactional
     * 方法內呼叫 rabbitTemplate.convertAndSend。讀路徑雖無 DB 寫入，但仍屬字面違規，
     * 且會在 MQ 網路 I/O 期間持有 DB 連線。</p>
     *
     * <p>本測試以事件送出當下是否存在作用中交易作為判準——僅驗事件有發，
     * 在修復前後皆會通過，無法鑑別此缺陷。</p>
     */
    @Test
    @DisplayName("GET /api/v1/articles/{uuid} - viewed 事件不得在交易作用域內送出")
    void getArticle_publishesViewedOutsideTransaction() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("交易邊界驗證文章");
        createRequest.setContent("驗證 viewed 事件不在交易內送出");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 記錄 viewed 事件送出當下是否仍在交易作用域內 */
        AtomicBoolean txActiveAtPublish = new AtomicBoolean(false);
        org.mockito.Mockito.doAnswer(invocation -> {
            txActiveAtPublish.set(TransactionSynchronizationManager.isActualTransactionActive());
            return null;
        }).when(rabbitTemplate).convertAndSend(
                org.mockito.ArgumentMatchers.eq(dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig.EXCHANGE),
                org.mockito.ArgumentMatchers.eq(dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig.ROUTING_KEY_VIEWED),
                org.mockito.ArgumentMatchers.any(dowob.xyz.blog.module.article.event.ArticleViewedEvent.class));

        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk());

        /** 先確認事件確實有送出，避免因未呼叫而讓下方斷言空過 */
        org.mockito.Mockito.verify(rabbitTemplate, org.mockito.Mockito.times(1))
                .convertAndSend(
                        org.mockito.ArgumentMatchers.eq(dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig.EXCHANGE),
                        org.mockito.ArgumentMatchers.eq(dowob.xyz.blog.module.article.config.ArticleRabbitMqConfig.ROUTING_KEY_VIEWED),
                        org.mockito.ArgumentMatchers.any(dowob.xyz.blog.module.article.event.ArticleViewedEvent.class));

        assertThat(txActiveAtPublish.get())
                .as("viewed 事件不得在交易作用域內送出（code-standards §Transaction+MQ 時序）")
                .isFalse();
    }

    // ===== Editor API 測試 =====

    @Test
    @DisplayName("POST /api/v1/articles → 回傳 EditorArticleResponse（有 uuid、content，無 contentHtml、viewCount）")
    void createArticle_returnsEditorArticleResponse() throws Exception {
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        CreateArticleRequest request = new CreateArticleRequest();
        request.setTitle("Editor 建立文章");
        request.setContent("# Markdown 內容");

        mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.content").value("# Markdown 內容"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.contentHtml").doesNotExist())
                .andExpect(jsonPath("$.data.viewCount").doesNotExist())
                .andExpect(jsonPath("$.data.slug").doesNotExist());
    }

    @Test
    @DisplayName("PUT /api/v1/articles/{uuid} → DRAFT 狀態 → 成功更新，回傳 EditorArticleResponse（無 contentHtml）")
    void updateArticle_draft_allowed_returnsEditorResponse() throws Exception {
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("草稿");
        createRequest.setContent("初始內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        UpdateArticleRequest updateRequest = new UpdateArticleRequest();
        updateRequest.setTitle("更新後標題");

        mockMvc.perform(put("/api/v1/articles/" + uuid)
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("更新後標題"))
                .andExpect(jsonPath("$.data.contentHtml").doesNotExist())
                .andExpect(jsonPath("$.data.viewCount").doesNotExist());
    }

    @Test
    @DisplayName("PUT /api/v1/articles/{uuid} → PUBLISHED 狀態 → 400（ARTICLE_EDIT_NOT_ALLOWED）")
    void updateArticle_published_returns403() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("待發布文章");
        createRequest.setContent("內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        UpdateArticleRequest updateRequest = new UpdateArticleRequest();
        updateRequest.setTitle("試圖修改已發布文章");

        mockMvc.perform(put("/api/v1/articles/" + uuid)
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/articles/{uuid}/edit → 作者本人可取得 EditorArticleResponse")
    void getArticleForEdit_authorCanAccess() throws Exception {
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("可編輯文章");
        createRequest.setContent("**Markdown 內容**");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(get("/api/v1/articles/" + uuid + "/edit")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").value(uuid))
                .andExpect(jsonPath("$.data.content").value("**Markdown 內容**"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.contentHtml").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/articles/{uuid}/edit → 未認證 → 401")
    void getArticleForEdit_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/articles/" + UUID.randomUUID() + "/edit"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/v1/articles/{uuid}/edit → Role.USER（無 ARTICLE_EDIT 權限）→ 403")
    void getArticleForEdit_roleUser_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/articles/" + UUID.randomUUID() + "/edit")
                .with(asUser(AUTHOR_ID, Role.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/articles/{uuid}/edit → 他人文章 → 400（ARTICLE_ACCESS_DENIED）")
    void getArticleForEdit_otherUserArticle_returns403() throws Exception {
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("他人文章");
        createRequest.setContent("內容");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(get("/api/v1/articles/" + uuid + "/edit")
                .with(asUser(99L, Role.AUTHOR)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/articles/{uuid}/edit → 文章不存在 → 400（ARTICLE_NOT_FOUND）")
    void getArticleForEdit_notFound_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/articles/" + UUID.randomUUID() + "/edit")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /api/v1/articles/archive - 公開取得歸檔（200、只含 PUBLISHED、依 publishedAt desc）")
    void getArchive_returns200WithPublishedOnly() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立並發布第一篇（較早發布） */
        String firstUuid = createArticle("歸檔文章 A", "內容 A");
        mockMvc.perform(post("/api/v1/articles/" + firstUuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 建立並發布第二篇（較晚發布，應排在最前面） */
        String secondUuid = createArticle("歸檔文章 B", "內容 B");
        mockMvc.perform(post("/api/v1/articles/" + secondUuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 建立一篇草稿（未發布，不應出現在歸檔中） */
        createArticle("草稿不應出現", "草稿內容");

        /** 匿名（無認證）存取歸檔端點 → 公開、200、信封、只含已發布且依 publishedAt desc */
        mockMvc.perform(get("/api/v1/articles/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].title").value("歸檔文章 B"))
                .andExpect(jsonPath("$.data[1].title").value("歸檔文章 A"))
                .andExpect(jsonPath("$.data[0].uuid").value(secondUuid))
                .andExpect(jsonPath("$.data[0].slug").exists())
                .andExpect(jsonPath("$.data[0].publishedAt").exists());
    }

    /**
     * 建立一篇草稿文章並回傳其 UUID（測試輔助方法）
     *
     * @param title   文章標題
     * @param content 文章 Markdown 內容
     * @return 新建文章的公開 UUID 字串
     * @throws Exception MockMvc 執行例外
     */
    private String createArticle(String title, String content) throws Exception {
        CreateArticleRequest req = new CreateArticleRequest();
        req.setTitle(title);
        req.setContent(content);
        String response = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).path("data").path("uuid").asText();
    }

    @Test
    @DisplayName("POST /api/v1/articles - title 超過 120 字 → 回傳 code=400")
    void createArticle_titleTooLong_shouldReturn400() throws Exception {
        String longTitle = "a".repeat(121);
        CreateArticleRequest request = new CreateArticleRequest();
        request.setTitle(longTitle);
        request.setContent("內容");

        mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    @DisplayName("PUT /api/v1/articles/{uuid} - title 超過 120 字 → 回傳 code=400")
    void updateArticle_titleTooLong_shouldReturn400() throws Exception {
        String longTitle = "a".repeat(121);
        UpdateArticleRequest request = new UpdateArticleRequest();
        request.setTitle(longTitle);

        mockMvc.perform(put("/api/v1/articles/" + UUID.randomUUID())
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    /**
     * 建立一篇文章並送審，回傳其 UUID（withdraw 測試輔助方法）
     *
     * @param title 文章標題
     * @return 已進入 PENDING_REVIEW 狀態的文章公開 UUID 字串
     * @throws Exception MockMvc 執行例外
     */
    private String createPendingReviewArticle(String title) throws Exception {
        String uuid = createArticle(title, "內容");
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/submit")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        return uuid;
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/withdraw - 作者抽回自己的待審文章 → 狀態變 DRAFT")
    void withdrawArticle_authorWithdrawsOwnPendingReview_success() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        String uuid = createPendingReviewArticle("待抽回文章");

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/withdraw")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        /** 抽回後應可再次編輯（PUT 守衛只放行 DRAFT / REJECTED） */
        UpdateArticleRequest updateRequest = new UpdateArticleRequest();
        updateRequest.setTitle("抽回後改標題");
        mockMvc.perform(put("/api/v1/articles/" + uuid)
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("抽回後改標題"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/withdraw - DRAFT 狀態抽回 → A0204")
    void withdrawArticle_draftStatus_returnsA0204() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        String uuid = createArticle("草稿文章", "內容");

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/withdraw")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0204"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/withdraw - PUBLISHED 狀態抽回 → A0204")
    void withdrawArticle_publishedStatus_returnsA0204() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        String uuid = createArticle("已發布文章", "內容");
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/withdraw")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0204"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/withdraw - REJECTED 狀態抽回 → A0204")
    void withdrawArticle_rejectedStatus_returnsA0204() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        String uuid = createPendingReviewArticle("待駁回文章");

        RejectArticleRequest rejectRequest = new RejectArticleRequest();
        rejectRequest.setReason("內容不符合規範");
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/reject")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rejectRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/withdraw")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0204"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/withdraw - 其他 AUTHOR 抽回他人文章 → A0203")
    void withdrawArticle_nonOwnerAuthor_returnsA0203() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        String uuid = createPendingReviewArticle("他人的待審文章");

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/withdraw")
                .with(asUser(99L, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0203"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/withdraw - ADMIN 抽回他人文章 → A0203（職責分離：ADMIN 應走 reject）")
    void withdrawArticle_adminOnOthersArticle_returnsA0203() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        String uuid = createPendingReviewArticle("他人送審的文章");

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/withdraw")
                .with(asUser(99L, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0203"));

        /** ADMIN 對他人送審文章的正當途徑是 reject，該路徑不受本次收緊影響 */
        RejectArticleRequest rejectRequest = new RejectArticleRequest();
        rejectRequest.setReason("內容不符合規範");
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/reject")
                .with(asUser(99L, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rejectRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/withdraw - Role.USER（無 ARTICLE_EDIT 權限）→ 403")
    void withdrawArticle_userRole_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/articles/" + UUID.randomUUID() + "/withdraw")
                .with(asUser(AUTHOR_ID, Role.USER)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/withdraw - 未認證 → 401")
    void withdrawArticle_unauthenticated_returns401() throws Exception {
        mockMvc.perform(post("/api/v1/articles/" + UUID.randomUUID() + "/withdraw"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/withdraw - 文章不存在 → A0201")
    void withdrawArticle_articleNotFound_returnsA0201() throws Exception {
        mockMvc.perform(post("/api/v1/articles/" + UUID.randomUUID() + "/withdraw")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0201"));
    }

    // ===== TOC 契約測試（T5：端到端驗證 T1-T4 渲染器 → 持久化 → DTO 串起來真的 work）=====

    @Test
    @DisplayName("建立含h2h3的文章_GET詳情_toc為結構化陣列")
    void createArticleWithH2H3_getDetail_tocIsStructuredArray() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立含 h2/h3 標題的文章：h2「安裝步驟」→ h3「Port 被佔用」→ h2「常見問題」（文件順序） */
        String markdown = "## 安裝步驟\n\n安裝說明段落。\n\n### Port 被佔用\n\n子節說明文字。\n\n## 常見問題\n\n常見問題說明段落。";
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("TOC 契約驗證文章");
        createRequest.setContent(markdown);

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 匿名取得已發布文章詳情，驗證 toc 為非空的結構化陣列，且順序與文件順序一致 */
        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toc").isArray())
                .andExpect(jsonPath("$.data.toc", hasSize(3)))
                // 第一個 ## 標題「安裝步驟」→ 陣列第一個 entry，level 為 2
                .andExpect(jsonPath("$.data.toc[0].id").value("heading-安裝步驟"))
                .andExpect(jsonPath("$.data.toc[0].id").value(matchesPattern("^heading-.*")))
                .andExpect(jsonPath("$.data.toc[0].text").value("安裝步驟"))
                .andExpect(jsonPath("$.data.toc[0].level").value(2))
                // ### 子標題「Port 被佔用」緊接於後，level 為 3
                .andExpect(jsonPath("$.data.toc[1].id").value("heading-port-被佔用"))
                .andExpect(jsonPath("$.data.toc[1].id").value(matchesPattern("^heading-.*")))
                .andExpect(jsonPath("$.data.toc[1].text").value("Port 被佔用"))
                .andExpect(jsonPath("$.data.toc[1].level").value(3))
                // 第二個 ## 標題「常見問題」排在最後，level 為 2
                .andExpect(jsonPath("$.data.toc[2].id").value("heading-常見問題"))
                .andExpect(jsonPath("$.data.toc[2].id").value(matchesPattern("^heading-.*")))
                .andExpect(jsonPath("$.data.toc[2].text").value("常見問題"))
                .andExpect(jsonPath("$.data.toc[2].level").value(2));
    }

    @Test
    @DisplayName("建立無heading的文章_GET詳情_toc為空陣列")
    void createArticleWithoutHeading_getDetail_tocIsEmptyArray() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立純段落文章（內文無任何 ## / ### 標題） */
        CreateArticleRequest createRequest = new CreateArticleRequest();
        createRequest.setTitle("無標題章節文章");
        createRequest.setContent("這是一段純文字內容，沒有任何標題。第二句話延伸內容，僅為段落。");

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 匿名取得已發布文章詳情，驗證 toc 為空陣列（非 null） */
        mockMvc.perform(get("/api/v1/articles/" + uuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toc").isArray())
                .andExpect(jsonPath("$.data.toc", hasSize(0)));
    }
}
