package dowob.xyz.blog.module.tag.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.tag.TestTagApplication;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 標籤 Controller 整合測試
 *
 * <p>
 * 使用 TestContainers 啟動 PostgreSQL 與 Redis，測試完整標籤 API 流程。
 * 使用 Spring Security Test 的 RequestPostProcessor 模擬認證，
 * 確保 SecurityContext 在完整過濾器鏈中正確傳遞。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestTagApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("TagController 整合測試")
class TagControllerIT {

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
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("spring.flyway.locations", () -> "classpath:db/it-migration");
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
     * JDBC 操作模板（用於插入測試資料）
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Redis String 操作模板（用於填入自動補全測試資料）
     */
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * Mock RabbitMQ ConnectionFactory（避免啟動時找不到 Bean）
     */
    @MockitoBean
    private ConnectionFactory connectionFactory;

    /**
     * Mock UserAuthService（JwtAuthenticationFilter 依賴，避免需要 User 模組實作）
     */
    @MockitoBean
    private UserAuthService userAuthService;

    /**
     * Mock UserFacade（避免依賴 User 模組）
     */
    @MockitoBean
    private UserFacade userFacade;

    /**
     * 每次測試前清理資料表，確保測試隔離
     */
    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM user_tag_follows");
        jdbcTemplate.execute("DELETE FROM article_tags");
        jdbcTemplate.execute("DELETE FROM tags");
        stringRedisTemplate.delete("tag:autocomplete");
        stringRedisTemplate.delete("tag:hot");
    }

    /**
     * 在 tags 資料表中插入一筆測試標籤，並回傳其 ID
     *
     * @param name       標籤名稱
     * @param slug       標籤 Slug
     * @param usageCount 使用計數
     * @return 新插入標籤的 UUID
     */
    private UUID insertTestTag(String name, String slug, int usageCount) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO tags (id, name, slug, usage_count, created_at) VALUES (?, ?, ?, ?, NOW())",
                id, name, slug, usageCount
        );
        return id;
    }

    /**
     * 建立模擬認證物件
     *
     * <p>
     * 使用 {@link UsernamePasswordAuthenticationToken} 以 {@code Long} 型別的 userId 作為 principal，
     * 並注入角色與權限作為 {@link org.springframework.security.core.GrantedAuthority}。
     * </p>
     *
     * @param userId      使用者 ID（Long 型別，對應 {@code @AuthenticationPrincipal Long userId}）
     * @param role        角色名稱（不含 ROLE_ 前綴，例如 "USER"、"ADMIN"）
     * @param permissions 額外的權限名稱（例如 "SYSTEM_CONFIG"）
     * @return 認證物件
     */
    private Authentication buildAuth(Long userId, String role, String... permissions) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        for (String perm : permissions) {
            authorities.add(new SimpleGrantedAuthority(perm));
        }
        return new UsernamePasswordAuthenticationToken(userId, null, authorities);
    }

    @Test
    @DisplayName("GET /api/v1/tags/suggest?q=spring - 自動補全（以 USER 認證），回傳 200 且 $.data 為陣列")
    void suggest_withPrefix_returns200WithList() throws Exception {
        stringRedisTemplate.opsForZSet().add("tag:autocomplete", "spring-boot", 0.0);
        stringRedisTemplate.opsForZSet().add("tag:autocomplete", "spring-cloud", 0.0);

        mockMvc.perform(get("/api/v1/tags/suggest")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                buildAuth(1L, "USER", "COMMENT_WRITE")))
                        .param("q", "spring"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/tags/hot?limit=3 - 熱門標籤（以 USER 認證），回傳 200 且 $.data 為列表")
    void getHotTags_returns200() throws Exception {
        insertTestTag("Java", "java", 100);
        insertTestTag("Spring", "spring", 80);
        insertTestTag("Docker", "docker", 60);

        mockMvc.perform(get("/api/v1/tags/hot")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                buildAuth(1L, "USER", "COMMENT_WRITE")))
                        .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/v1/tags/java - 依 Slug 取得標籤詳情（以 USER 認證），回傳 200 且 $.data.slug == 'java'")
    void getTagDetail_withExistingSlug_returns200() throws Exception {
        insertTestTag("Java", "java", 10);

        mockMvc.perform(get("/api/v1/tags/java")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                buildAuth(1L, "USER", "COMMENT_WRITE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.slug").value("java"));
    }

    @Test
    @DisplayName("GET /api/v1/tags/nonexistent-tag-xyz - 不存在的 Slug（以 USER 認證），回傳 200 且 $.code 為 T001")
    void getTagDetail_withUnknownSlug_returns200WithErrorCode() throws Exception {
        mockMvc.perform(get("/api/v1/tags/nonexistent-tag-xyz")
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                buildAuth(1L, "USER", "COMMENT_WRITE"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("T001"));
    }

    @Test
    @DisplayName("POST /api/v1/tags/{id}/follow - 已認證使用者追蹤標籤，回傳 200 成功")
    void followTag_authenticated_returns200() throws Exception {
        UUID tagId = insertTestTag("Kubernetes", "kubernetes", 5);

        mockMvc.perform(post("/api/v1/tags/{id}/follow", tagId)
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                buildAuth(1L, "USER", "COMMENT_WRITE")))
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    @Test
    @DisplayName("POST /api/v1/tags/{id}/follow - 未認證使用者，回傳 403")
    void followTag_unauthenticated_returns403() throws Exception {
        mockMvc.perform(post("/api/v1/tags/{id}/follow", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT /api/admin/tags/{id} - 具備 SYSTEM_CONFIG 權限的 ADMIN，回傳 200 成功")
    void adminUpdateTag_withSystemConfigPermission_returns200() throws Exception {
        UUID tagId = insertTestTag("Redis", "redis", 0);
        Map<String, String> body = Map.of("color", "#ff0000");

        mockMvc.perform(put("/api/admin/tags/{id}", tagId)
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                buildAuth(1L, "ADMIN", "SYSTEM_CONFIG")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    @Test
    @DisplayName("PUT /api/admin/tags/{id} - USER 角色（無 ADMIN），回傳 403")
    void adminUpdateTag_withoutPermission_returns403() throws Exception {
        UUID tagId = insertTestTag("RabbitMQ", "rabbitmq", 0);
        Map<String, String> body = Map.of("color", "#00ff00");

        mockMvc.perform(put("/api/admin/tags/{id}", tagId)
                        .with(SecurityMockMvcRequestPostProcessors.authentication(
                                buildAuth(1L, "USER", "COMMENT_WRITE")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }
}
