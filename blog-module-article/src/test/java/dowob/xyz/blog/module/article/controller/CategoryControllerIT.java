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
import dowob.xyz.blog.module.article.model.dto.request.CreateCategoryRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateCategoryRequest;
import dowob.xyz.blog.module.article.repository.ArticleRepository;
import dowob.xyz.blog.module.article.repository.CategoryRepository;
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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 分類 Controller 整合測試
 *
 * <p>
 * 使用 TestContainers 啟動 PostgreSQL，測試完整 Category API 流程。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = ArticleTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("CategoryController 整合測試")
class CategoryControllerIT {

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
    private ObjectMapper objectMapper;

    @Autowired
    private ArticleRepository articleRepository;

    @Autowired
    private CategoryRepository categoryRepository;

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

    @MockitoBean
    private ReadingFacade readingFacade;

    /**
     * Mock SeriesFacade（避免依賴 Series Module 實作）
     */
    @MockitoBean
    private SeriesFacade seriesFacade;

    private static final Long AUTHOR_ID = 1L;
    private static final UUID AUTHOR_UUID = UUID.randomUUID();

    @AfterEach
    void cleanUp() {
        articleRepository.deleteAll();
        categoryRepository.deleteAll();
    }

    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    @Test
    @DisplayName("GET /api/v1/categories - 公開取得空分類列表")
    void getAllCategories_emptyList() throws Exception {
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("POST /api/admin/categories - Admin 建立分類成功")
    void createCategory_adminSuccess() throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest();
        request.setName("後端");
        request.setSlug("backend");
        request.setDescription("後端技術");
        request.setSortOrder(1);

        mockMvc.perform(post("/api/v1/admin/categories")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.name").value("後端"))
                .andExpect(jsonPath("$.data.slug").value("backend"));
    }

    @Test
    @DisplayName("POST /api/admin/categories - AUTHOR 呼叫 → 403")
    void createCategory_authorForbidden() throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest();
        request.setName("後端");
        request.setSlug("backend");

        mockMvc.perform(post("/api/v1/admin/categories")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/v1/categories/{slug} - 取得已建立的分類詳情")
    void getCategoryBySlug_success() throws Exception {
        /** 先建立分類 */
        CreateCategoryRequest createRequest = new CreateCategoryRequest();
        createRequest.setName("前端");
        createRequest.setSlug("frontend");

        mockMvc.perform(post("/api/v1/admin/categories")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk());

        /** 取得分類詳情 */
        mockMvc.perform(get("/api/v1/categories/frontend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("前端"))
                .andExpect(jsonPath("$.data.slug").value("frontend"));
    }

    @Test
    @DisplayName("GET /api/v1/categories/{slug} - slug 不存在 → A0205")
    void getCategoryBySlug_notFound() throws Exception {
        mockMvc.perform(get("/api/v1/categories/nonexistent"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0205"));
    }

    @Test
    @DisplayName("PUT /api/admin/categories/{uuid} - Admin 更新分類")
    void updateCategory_adminSuccess() throws Exception {
        /** 建立分類 */
        CreateCategoryRequest createRequest = new CreateCategoryRequest();
        createRequest.setName("DevOps");
        createRequest.setSlug("devops");

        String createResponse = mockMvc.perform(post("/api/v1/admin/categories")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        /** 更新分類 */
        UpdateCategoryRequest updateRequest = new UpdateCategoryRequest();
        updateRequest.setName("DevOps & 雲端");

        mockMvc.perform(put("/api/v1/admin/categories/" + uuid)
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("DevOps & 雲端"));
    }

    @Test
    @DisplayName("DELETE /api/admin/categories/{uuid} - Admin 刪除空分類成功")
    void deleteCategory_adminSuccess() throws Exception {
        /** 建立分類 */
        CreateCategoryRequest createRequest = new CreateCategoryRequest();
        createRequest.setName("測試分類");
        createRequest.setSlug("test-cat");

        String createResponse = mockMvc.perform(post("/api/v1/admin/categories")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        /** 刪除分類 */
        mockMvc.perform(delete("/api/v1/admin/categories/" + uuid)
                .with(asUser(AUTHOR_ID, Role.ADMIN)))
                .andExpect(status().isOk());

        /** 確認已刪除 */
        mockMvc.perform(get("/api/v1/categories/test-cat"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0205"));
    }

    @Test
    @DisplayName("DELETE /api/admin/categories/{uuid} - 刪除有文章的分類 → A0206")
    void deleteCategory_hasArticles_error() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立分類 */
        CreateCategoryRequest createRequest = new CreateCategoryRequest();
        createRequest.setName("有文章的分類");
        createRequest.setSlug("with-articles");

        String createResponse = mockMvc.perform(post("/api/v1/admin/categories")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String categoryUuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        /** 建立文章並關聯分類 */
        CreateArticleRequest articleRequest = new CreateArticleRequest();
        articleRequest.setTitle("測試文章");
        articleRequest.setContent("測試內容");
        articleRequest.setCategoryIds(List.of(UUID.fromString(categoryUuid)));

        mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(articleRequest)))
                .andExpect(status().isOk());

        /** 嘗試刪除有文章的分類 -> A0206 */
        mockMvc.perform(delete("/api/v1/admin/categories/" + categoryUuid)
                .with(asUser(AUTHOR_ID, Role.ADMIN)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0206"));
    }

    @Test
    @DisplayName("GET /api/v1/articles?categorySlug=backend - 依分類篩選已發布文章")
    void getPublishedArticles_filteredByCategorySlug() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立分類 */
        CreateCategoryRequest categoryRequest = new CreateCategoryRequest();
        categoryRequest.setName("後端");
        categoryRequest.setSlug("backend");

        String categoryResponse = mockMvc.perform(post("/api/v1/admin/categories")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(categoryRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String categoryUuid = objectMapper.readTree(categoryResponse).path("data").path("uuid").asText();

        /** 建立並發布屬於此分類的文章 */
        CreateArticleRequest articleRequest = new CreateArticleRequest();
        articleRequest.setTitle("Java 入門");
        articleRequest.setContent("Java 後端內容");
        articleRequest.setCategoryIds(List.of(UUID.fromString(categoryUuid)));

        String articleResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(articleRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String articleUuid = objectMapper.readTree(articleResponse).path("data").path("uuid").asText();

        /** 發布文章 */
        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 依分類篩選 -> 找到 1 篇 */
        mockMvc.perform(get("/api/v1/articles?categorySlug=backend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("Java 入門"));

        /** 其他分類 -> 0 篇 */
        mockMvc.perform(get("/api/v1/articles?categorySlug=frontend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    @DisplayName("POST /api/admin/categories - slug 重複 → A0207")
    void createCategory_duplicateSlug_error() throws Exception {
        CreateCategoryRequest request = new CreateCategoryRequest();
        request.setName("後端");
        request.setSlug("backend");

        /** 第一次建立成功 */
        mockMvc.perform(post("/api/v1/admin/categories")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        /** 第二次同 slug -> A0207 */
        CreateCategoryRequest duplicateRequest = new CreateCategoryRequest();
        duplicateRequest.setName("後端技術");
        duplicateRequest.setSlug("backend");

        mockMvc.perform(post("/api/v1/admin/categories")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(duplicateRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0207"));
    }

    @Test
    @DisplayName("GET /api/v1/articles/{uuid} - 回應中包含 categories 欄位")
    void getArticle_responseContainsCategories() throws Exception {
        when(userFacade.getUserUuidById(anyLong())).thenReturn(Optional.of(AUTHOR_UUID));
        when(userFacade.getUserNicknameById(anyLong())).thenReturn(Optional.of("TestAuthor"));
        when(userFacade.getUserUsernameById(anyLong())).thenReturn(Optional.of("testuser"));

        /** 建立分類 */
        CreateCategoryRequest categoryRequest = new CreateCategoryRequest();
        categoryRequest.setName("後端");
        categoryRequest.setSlug("backend");

        String categoryResponse = mockMvc.perform(post("/api/v1/admin/categories")
                .with(asUser(AUTHOR_ID, Role.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(categoryRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String categoryUuid = objectMapper.readTree(categoryResponse).path("data").path("uuid").asText();

        /** 建立文章並關聯分類 */
        CreateArticleRequest articleRequest = new CreateArticleRequest();
        articleRequest.setTitle("分類文章");
        articleRequest.setContent("內容");
        articleRequest.setCategoryIds(List.of(UUID.fromString(categoryUuid)));

        String articleResponse = mockMvc.perform(post("/api/v1/articles")
                .with(asUser(AUTHOR_ID, Role.AUTHOR))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(articleRequest)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String articleUuid = objectMapper.readTree(articleResponse).path("data").path("uuid").asText();

        /** 發布文章 */
        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/publish")
                .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk());

        /** 取得文章，驗證 categories 欄位 */
        mockMvc.perform(get("/api/v1/articles/" + articleUuid))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categories").isArray())
                .andExpect(jsonPath("$.data.categories[0].slug").value("backend"));
    }
}
