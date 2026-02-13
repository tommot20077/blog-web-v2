package dowob.xyz.blog.module.article.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
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
import org.springframework.boot.test.mock.mockito.MockBean;
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

import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
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
    @MockBean
    private ConnectionFactory connectionFactory;

    /**
     * Mock RabbitTemplate（避免 IT 真正發送 MQ 訊息）
     */
    @MockBean
    private RabbitTemplate rabbitTemplate;

    /**
     * Mock UserFacade（避免依賴 User Module）
     */
    @MockBean
    private UserFacade userFacade;

    /**
     * Mock UserAuthService（避免依賴 User Module 實作）
     */
    @MockBean
    private UserAuthService userAuthService;

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
    private RequestPostProcessor asUser(Long userId, Role role) {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId, null,
                Collections.singletonList(new SimpleGrantedAuthority(role.getSpringSecurityRole())));
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    @Test
    @DisplayName("GET /api/v1/articles - 公開取得已發布文章列表（空列表）")
    void getPublishedArticles_emptyList() throws Exception {
        mockMvc.perform(get("/api/v1/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.list").isArray())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("POST /api/v1/articles → POST publish → GET 完整流程")
    void createAndGetArticle_fullFlow() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0201"));
    }

    @Test
    @DisplayName("GET /api/v1/articles/me - 取得我的文章列表")
    void getMyArticles_success() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));

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
                .andExpect(jsonPath("$.data.list").isArray());
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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("400"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/publish - 非法狀態轉換（PUBLISHED→PUBLISHED）→ A0204")
    void publishArticle_alreadyPublished_error() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));

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
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0204"));
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/reject - Admin 駁回待審文章 → 狀態為 REJECTED")
    void rejectArticle_adminRejects_success() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));

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
        mockMvc.perform(get("/api/admin/articles/pending")
                .with(asUser(AUTHOR_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.list").isArray());
    }

    @Test
    @DisplayName("GET /api/admin/articles/pending - AUTHOR 呼叫 → 403")
    void getPendingArticles_nonAdminForbidden() throws Exception {
        mockMvc.perform(get("/api/admin/articles/pending")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("POST /api/v1/articles/{uuid}/reject - AUTHOR（非 ADMIN）呼叫 → 業務錯誤 A0203")
    void rejectArticle_authorForbidden_businessError() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));

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

        /** AUTHOR 嘗試駁回 → 應回傳業務錯誤（非 500） */
        dowob.xyz.blog.module.article.model.dto.request.RejectArticleRequest rejectRequest =
                new dowob.xyz.blog.module.article.model.dto.request.RejectArticleRequest();
        rejectRequest.setReason("想試試駁回");

        mockMvc.perform(post("/api/v1/articles/" + uuid + "/reject")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(rejectRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("A0203"));
    }

    @Test
    @DisplayName("GET /api/v1/articles/me - 匿名存取 → 回傳 total=0（文件化現有行為）")
    void getMyArticles_anonymous_returnsEmpty() throws Exception {
        mockMvc.perform(get("/api/v1/articles/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("GET /api/v1/articles/{uuid} - 回應中包含 authorNickname 欄位")
    void getArticle_responseContainsAuthorNickname() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));

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
}
