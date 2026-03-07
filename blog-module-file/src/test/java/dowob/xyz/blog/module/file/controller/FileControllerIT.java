package dowob.xyz.blog.module.file.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.module.file.TestFileApplication;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 檔案 Controller 整合測試
 *
 * <p>
 * 使用 TestContainers 啟動 PostgreSQL、Redis 與 MinIO，
 * 測試 FileController 的完整 API 流程。
 * 使用 Spring Security Test 提供的 RequestPostProcessor 模擬認證，
 * 確保 SecurityContext 在完整過濾器鏈中正確傳遞。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = TestFileApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("FileController 整合測試")
class FileControllerIT {

    /**
     * PostgreSQL TestContainer
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Redis TestContainer
     */
    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    /**
     * MinIO TestContainer
     */
    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

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
        registry.add("minio.endpoint", minio::getS3URL);
        registry.add("minio.access-key", minio::getUserName);
        registry.add("minio.secret-key", minio::getPassword);
        registry.add("minio.bucket-name", () -> "blog-files");
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
     * 檔案元資料 Repository（用於測試清理）
     */
    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    /**
     * MinIO 客戶端（用於初始化儲存桶）
     */
    @Autowired
    private MinioClient minioClient;

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
     * Mock UserAuthService（JwtAuthenticationFilter 依賴，避免依賴 User Module 實作）
     */
    @MockitoBean
    private UserAuthService userAuthService;

    /**
     * Mock UserFacade（FileController 依賴，用於 userId → UUID 轉換）
     */
    @MockitoBean
    private UserFacade userFacade;

    /**
     * 測試用使用者 A 的內部 ID
     */
    private static final Long USER_A_ID = 1L;

    /**
     * 測試用使用者 B 的內部 ID
     */
    private static final Long USER_B_ID = 2L;

    /**
     * 測試用使用者 A 的 UUID
     */
    private static final UUID USER_A_UUID = UUID.randomUUID();

    /**
     * 測試用使用者 B 的 UUID
     */
    private static final UUID USER_B_UUID = UUID.randomUUID();

    /**
     * 每次測試前設定 UserFacade mock，回傳測試用 UUID
     */
    @BeforeEach
    void setUpUserFacade() {
        when(userFacade.getUserUuidById(USER_A_ID)).thenReturn(Optional.of(USER_A_UUID));
        when(userFacade.getUserUuidById(USER_B_ID)).thenReturn(Optional.of(USER_B_UUID));
    }

    /**
     * 在所有測試前初始化 MinIO 儲存桶
     *
     * @param minioClient 注入的 MinIO 客戶端
     * @throws Exception 建立儲存桶失敗時拋出
     */
    @BeforeAll
    static void setupMinio(@Autowired MinioClient minioClient) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket("blog-files").build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket("blog-files").build());
        }
    }

    /**
     * 每次測試後清理 file_metadata 資料
     */
    @AfterEach
    void cleanUp() {
        fileMetadataRepository.deleteAll();
    }

    /**
     * 建立具有指定角色及其對應所有 Permission 的模擬認證 RequestPostProcessor。
     * Principal 為 Long userId，與生產環境 JwtAuthenticationFilter 一致。
     *
     * @param userId 使用者內部 ID
     * @param role   角色
     * @return RequestPostProcessor
     */
    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    /**
     * 建立測試用 JPEG MockMultipartFile，從 test/resources/test.jpg 讀取
     *
     * @return JPEG 格式的 MockMultipartFile
     * @throws Exception 讀取檔案失敗時拋出
     */
    private MockMultipartFile createTestJpeg() throws Exception {
        byte[] jpegBytes = getClass().getClassLoader()
                .getResourceAsStream("test.jpg").readAllBytes();
        return new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
    }

    @Test
    @DisplayName("POST /api/files/upload - AUTHOR 具備 FILE_UPLOAD 權限，上傳合法 JPEG 應回傳 00000 且 data.id 不為 null")
    void uploadFile_withValidJpeg_withFileUploadPermission_returns200() throws Exception {
        MockMultipartFile file = createTestJpeg();

        mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "ARTICLE_CONTENT")
                .with(asUser(USER_A_ID, Role.AUTHOR))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.id").isNotEmpty());
    }

    @Test
    @DisplayName("POST /api/files/upload - 未認證應回傳 HTTP 401")
    void uploadFile_withoutAuthentication_returns401() throws Exception {
        MockMultipartFile file = createTestJpeg();

        mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "ARTICLE_CONTENT")
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/files/upload - USER 角色不具備 FILE_UPLOAD 權限，應回傳 HTTP 403")
    void uploadFile_withUserRoleOnly_withoutFileUploadPermission_returns403() throws Exception {
        MockMultipartFile file = createTestJpeg();

        mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "AVATAR")
                .with(asUser(USER_A_ID, Role.USER))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET /api/users/me/files - 先上傳再查詢，應包含該檔案")
    void getUserFiles_afterUpload_containsUploadedFile() throws Exception {
        MockMultipartFile file = createTestJpeg();

        mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "ARTICLE_CONTENT")
                .with(asUser(USER_A_ID, Role.AUTHOR))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        mockMvc.perform(get("/api/v1/users/me/files")
                .with(asUser(USER_A_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[0].id").isNotEmpty())
                .andExpect(jsonPath("$.data[0].originalName").value("test.jpg"));
    }

    @Test
    @DisplayName("DELETE /api/files/{id} - 擁有者刪除自己的檔案，應回傳 00000")
    void deleteFile_byOwner_returns200() throws Exception {
        MockMultipartFile file = createTestJpeg();

        String uploadResponse = mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "ARTICLE_CONTENT")
                .with(asUser(USER_A_ID, Role.AUTHOR))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String fileId = objectMapper.readTree(uploadResponse).path("data").path("id").asText();

        mockMvc.perform(delete("/api/v1/files/" + fileId)
                .with(asUser(USER_A_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    @Test
    @DisplayName("DELETE /api/files/{id} - 非擁有者刪除他人檔案，應回傳 A0405 錯誤碼")
    void deleteFile_byNonOwner_returns403ErrorCode() throws Exception {
        MockMultipartFile file = createTestJpeg();

        String uploadResponse = mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "ARTICLE_CONTENT")
                .with(asUser(USER_A_ID, Role.AUTHOR))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String fileId = objectMapper.readTree(uploadResponse).path("data").path("id").asText();

        mockMvc.perform(delete("/api/v1/files/" + fileId)
                .with(asUser(USER_B_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0405"));
    }

    @Test
    @DisplayName("GET /api/users/me/files - 已認證用戶查詢，應回傳列表（可為空）")
    void getUserFiles_authenticated_returnsList() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/files")
                .with(asUser(USER_A_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("GET /api/users/me/quota - 已認證用戶查詢配額，應回傳 usedBytes >= 0 且 limitBytes > 0")
    void getQuota_authenticated_returnsQuotaInfo() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/quota")
                .with(asUser(USER_A_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.usedBytes").value(0))
                .andExpect(jsonPath("$.data.limitBytes").value(500L * 1024 * 1024));
    }

    @Test
    @DisplayName("GET /api/users/me/files - 未認證存取應回傳 HTTP 401")
    void getUserFiles_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/files"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/files/{id} - 刪除不存在的檔案，應回傳 A0401 錯誤碼")
    void deleteFile_notFound_returnsA0401() throws Exception {
        mockMvc.perform(delete("/api/v1/files/" + UUID.randomUUID())
                .with(asUser(USER_A_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0401"));
    }
}
