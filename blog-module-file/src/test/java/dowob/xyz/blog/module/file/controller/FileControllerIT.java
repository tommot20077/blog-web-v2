package dowob.xyz.blog.module.file.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.ArticleLookupFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.facade.dto.ArticleData;
import dowob.xyz.blog.module.file.TestFileApplication;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.repository.FileMetadataRepository;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
     * 檔案元資料 Repository（用於測試清理，並以 spy 計數 DB 查詢次數）
     *
     * <p>用 {@link MockitoSpyBean} 而非 {@link Autowired}：spy 會委派真實實作，
     * 既有的清理用途不受影響，另可 verify {@code findById} 的呼叫次數——
     * 內文圖片是熱路徑（一頁十張圖就是十個請求），每個請求查幾次 DB 必須釘住。</p>
     */
    @MockitoSpyBean
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
     * Mock ArticleLookupFacade（FileServiceImpl.canRead 依賴，判斷已綁定文章是否已發布；
     * article 模組實作未在本 IT 的 scanBasePackages 內，須 mock 避免 context 啟動失敗）
     */
    @MockitoBean
    private ArticleLookupFacade articleLookupFacade;

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

    @Test
    @DisplayName("DELETE /api/files/{id} - ADMIN 刪除他人檔案，應回傳 00000 成功")
    void deleteFile_byAdmin_returns200() throws Exception {
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
                .with(asUser(USER_B_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * MEDIUM 2 修復：本端點現套用 canRead 授權矩陣。此檔案為未綁定任何文章的
     * ARTICLE_CONTENT（草稿態），故須以上傳者本人身分查詢才會通過；修復前這裡是匿名呼叫，
     * 等同驗證了「任何人皆可查詢他人草稿檔案 metadata」的漏洞行為，現已調整為合法情境（擁有者本人）。
     */
    @Test
    @DisplayName("GET /api/v1/files/{id} - 擁有者本人取得已上傳檔案的元資料，應回傳 00000 且 data.id 不為 null")
    void getFileMetadata_existingFile_returns200() throws Exception {
        MockMultipartFile file = createTestJpeg();

        String uploadResponse = mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "ARTICLE_CONTENT")
                .with(asUser(USER_A_ID, Role.AUTHOR))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String fileId = objectMapper.readTree(uploadResponse).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/files/" + fileId)
                .with(asUser(USER_A_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.id").value(fileId));
    }

    /**
     * MEDIUM 2 修復驗收：未綁定任何文章的草稿檔案（ARTICLE_CONTENT），匿名查詢 metadata
     * 應被 canRead 拒絕（HTTP 403 + A0405），而非修復前的「完全不受授權矩陣約束」。
     */
    @Test
    @DisplayName("GET /api/v1/files/{id} - 匿名查詢他人未綁定草稿檔案的元資料，應回傳 HTTP 403")
    void getFileMetadata_unboundDraftFile_anonymous_returns403() throws Exception {
        MockMultipartFile file = createTestJpeg();

        String uploadResponse = mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "ARTICLE_CONTENT")
                .with(asUser(USER_A_ID, Role.AUTHOR))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String fileId = objectMapper.readTree(uploadResponse).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/files/" + fileId))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("A0405"));
    }

    /**
     * MEDIUM 2 迴歸驗證：canRead 規則1（AVATAR 對匿名開放）套用到 metadata 端點後，
     * 公開情境（頭像）不受此次修復影響，匿名仍可查詢。
     */
    @Test
    @DisplayName("GET /api/v1/files/{id} - AVATAR 檔案，匿名查詢元資料應回傳 00000")
    void getFileMetadata_avatarUsageType_anonymous_returns200() throws Exception {
        MockMultipartFile file = createTestJpeg();

        String uploadResponse = mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "AVATAR")
                .with(asUser(USER_A_ID, Role.AUTHOR))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String fileId = objectMapper.readTree(uploadResponse).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/files/" + fileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.id").value(fileId));
    }

    /**
     * MEDIUM 2 迴歸驗證：ADMIN 不受擁有權限制，仍可查詢任何檔案的 metadata。
     */
    @Test
    @DisplayName("GET /api/v1/files/{id} - ADMIN 查詢他人未綁定草稿檔案的元資料，應回傳 00000")
    void getFileMetadata_unboundDraftFile_admin_returns200() throws Exception {
        MockMultipartFile file = createTestJpeg();

        String uploadResponse = mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "ARTICLE_CONTENT")
                .with(asUser(USER_A_ID, Role.AUTHOR))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String fileId = objectMapper.readTree(uploadResponse).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/files/" + fileId)
                        .with(asUser(USER_B_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * MEDIUM 2：metadata 回應不應洩漏內部儲存細節 storagePath（FileMetadata 已加 @JsonIgnore）。
     */
    @Test
    @DisplayName("GET /api/v1/files/{id} - 回應 JSON 不應包含 storagePath 欄位")
    void getFileMetadata_response_doesNotExposeStoragePath() throws Exception {
        MockMultipartFile file = createTestJpeg();

        String uploadResponse = mockMvc.perform(multipart("/api/v1/files/upload")
                .file(file)
                .param("usageType", "ARTICLE_CONTENT")
                .with(asUser(USER_A_ID, Role.AUTHOR))
                .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String fileId = objectMapper.readTree(uploadResponse).path("data").path("id").asText();

        mockMvc.perform(get("/api/v1/files/" + fileId)
                        .with(asUser(USER_A_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storagePath").doesNotExist());
    }

    @Test
    @DisplayName("GET /api/v1/files/{id} - 取得不存在的檔案元資料，應回傳錯誤碼")
    void getFileMetadata_notFound_returnsError() throws Exception {
        mockMvc.perform(get("/api/v1/files/" + UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0401"));
    }

    @Test
    @DisplayName("GET /api/users/me/quota - 未認證存取配額應回傳 HTTP 401")
    void getQuota_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/quota"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("DELETE /api/files/{id} - 未認證刪除應回傳 HTTP 401")
    void deleteFile_unauthenticated_returns401() throws Exception {
        mockMvc.perform(delete("/api/v1/files/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/users/me/quota - ADMIN 角色查詢配額，limitBytes 應對應 ADMIN 額度")
    void getQuota_admin_returnsAdminQuota() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/quota")
                .with(asUser(USER_A_ID, Role.ADMIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.usedBytes").value(0));
    }

    @Test
    @DisplayName("GET /v3/api-docs - POST /api/v1/files/upload 應把 usageType 宣告為 multipart form field 而非 query parameter")
    void openApi_fileUpload_declaresUsageTypeAsMultipartFormField() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        ObjectMapper m = new ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode root = m.readTree(json);
        com.fasterxml.jackson.databind.JsonNode uploadPost = root.at("/paths/~1api~1v1~1files~1upload/post");
        org.junit.jupiter.api.Assertions.assertFalse(
                uploadPost.isMissingNode(),
                "POST /api/v1/files/upload 必須存在於 OpenAPI 文件中"
        );

        /*
         * usageType 不應該出現在 parameters 陣列（query 等位置）。
         */
        com.fasterxml.jackson.databind.JsonNode params = uploadPost.path("parameters");
        if (params.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode p : params) {
                org.junit.jupiter.api.Assertions.assertNotEquals(
                        "usageType", p.path("name").asText(),
                        "usageType 不應該被宣告為 OpenAPI parameter（query/header/cookie/path），應放在 requestBody 內"
                );
            }
        }

        /*
         * usageType 應該出現在 multipart/form-data requestBody schema properties。
         * schema 可能是 inline 或 $ref 指向 components.schemas，兩者都接受。
         */
        com.fasterxml.jackson.databind.JsonNode multipartSchema =
                uploadPost.at("/requestBody/content/multipart~1form-data/schema");
        org.junit.jupiter.api.Assertions.assertFalse(
                multipartSchema.isMissingNode(),
                "POST /api/v1/files/upload 必須有 multipart/form-data requestBody schema"
        );

        com.fasterxml.jackson.databind.JsonNode usageTypeProp;
        String ref = multipartSchema.path("$ref").asText("");
        if (!ref.isEmpty()) {
            String name = ref.replace("#/components/schemas/", "");
            usageTypeProp = root.at("/components/schemas/" + name + "/properties/usageType");
        } else {
            usageTypeProp = multipartSchema.path("properties").path("usageType");
        }
        org.junit.jupiter.api.Assertions.assertFalse(
                usageTypeProp.isMissingNode(),
                "usageType 必須出現在 multipart/form-data requestBody schema 的 properties"
        );
    }

    /**
     * B4：GET /api/v1/files/{id}/content 授權矩陣測試（spec §4/§9）。
     *
     * <p>
     * 場景涵蓋：AVATAR 匿名可讀、已綁定 PUBLISHED 匿名可讀、已綁定 DRAFT（匿名/他人拒絕，
     * 上傳者/ADMIN 允許）、未綁定 fail-safe（匿名拒絕，上傳者允許）、檔案不存在 404、
     * 以及回應必須是 302 + Location（絕不可回傳圖片位元組）。
     * Red：此端點目前不存在，GET 會落到 Spring 預設 404，下列非 404 期待的斷言會全部失敗。
     * </p>
     */
    @Nested
    @DisplayName("GET /api/v1/files/{id}/content 授權矩陣測試 (B4)")
    class FileContentEndpointTests {

        /**
         * 以指定角色上傳一個測試檔案，可選擇性帶 articleUuid 一併綁定，回傳新檔案的 UUID 字串。
         *
         * <p>
         * MEDIUM 1 修復後的測試資料調整：下方各測試以 {@code new ArticleData(1L, articleUuid,
         * USER_A_ID, ...)} 宣告 articleUuid 對應文章的作者為 {@code USER_A_ID}——因為
         * {@code uploadAndGetFileId} 呼叫時的上傳者也是 {@code USER_A_ID}，兩者必須一致，
         * 否則 {@code uploadFile} 新增的擁有權檢查（僅本人文章才接受 articleUuid）會使 metadata
         * 存成未綁定（null），導致這些測試原本要驗證的「已綁定 PUBLISHED/DRAFT 文章」情境失真。
         * 修復前這裡曾寫死不相關的 {@code 99L}，因為當時 uploadFile 不檢查擁有權，authorId
         * 是誰並不影響上傳綁定是否成功。
         * </p>
         */
        private String uploadAndGetFileId(UsageType usageType, Long uploaderInternalId, Role role, UUID articleUuid) throws Exception {
            MockMultipartFile file = createTestJpeg();
            var builder = multipart("/api/v1/files/upload")
                    .file(file)
                    .param("usageType", usageType.name());
            if (articleUuid != null) {
                builder.param("articleUuid", articleUuid.toString());
            }
            String uploadResponse = mockMvc.perform(builder
                            .with(asUser(uploaderInternalId, role))
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            return objectMapper.readTree(uploadResponse).path("data").path("id").asText();
        }

        @Test
        @DisplayName("規則1：AVATAR 檔案，匿名存取 → 302 導向 presigned URL")
        void getFileContent_avatarUsageType_anonymous_returns302() throws Exception {
            String fileId = uploadAndGetFileId(UsageType.AVATAR, USER_A_ID, Role.AUTHOR, null);

            MvcResult result = mockMvc.perform(get("/api/v1/files/" + fileId + "/content"))
                    .andExpect(status().isFound())
                    .andReturn();

            String location = result.getResponse().getHeader("Location");
            assertThat(location).isNotBlank();
            assertThat(location).contains("X-Amz-Signature");
        }

        @Test
        @DisplayName("規則2：已綁定 PUBLISHED 文章的檔案，匿名存取 → 302")
        void getFileContent_boundToPublishedArticle_anonymous_returns302() throws Exception {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, USER_A_ID, "PUBLISHED", null, null)));
            String fileId = uploadAndGetFileId(UsageType.ARTICLE_CONTENT, USER_A_ID, Role.AUTHOR, articleUuid);

            mockMvc.perform(get("/api/v1/files/" + fileId + "/content"))
                    .andExpect(status().isFound())
                    .andExpect(header().exists("Location"));
        }

        @Test
        @DisplayName("規則2 反例：已綁定 DRAFT 文章的檔案，匿名存取 → 403")
        void getFileContent_boundToDraftArticle_anonymous_returns403() throws Exception {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, USER_A_ID, "DRAFT", null, null)));
            String fileId = uploadAndGetFileId(UsageType.ARTICLE_CONTENT, USER_A_ID, Role.AUTHOR, articleUuid);

            mockMvc.perform(get("/api/v1/files/" + fileId + "/content"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("已綁定 DRAFT 文章的檔案，非上傳者存取 → 403")
        void getFileContent_boundToDraftArticle_nonUploader_returns403() throws Exception {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, USER_A_ID, "DRAFT", null, null)));
            String fileId = uploadAndGetFileId(UsageType.ARTICLE_CONTENT, USER_A_ID, Role.AUTHOR, articleUuid);

            mockMvc.perform(get("/api/v1/files/" + fileId + "/content")
                            .with(asUser(USER_B_ID, Role.AUTHOR)))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("已綁定 DRAFT 文章的檔案，上傳者本人存取 → 302")
        void getFileContent_boundToDraftArticle_uploader_returns302() throws Exception {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, USER_A_ID, "DRAFT", null, null)));
            String fileId = uploadAndGetFileId(UsageType.ARTICLE_CONTENT, USER_A_ID, Role.AUTHOR, articleUuid);

            mockMvc.perform(get("/api/v1/files/" + fileId + "/content")
                            .with(asUser(USER_A_ID, Role.AUTHOR)))
                    .andExpect(status().isFound())
                    .andExpect(header().exists("Location"));
        }

        @Test
        @DisplayName("已綁定 DRAFT 文章的檔案，ADMIN 存取 → 302")
        void getFileContent_boundToDraftArticle_admin_returns302() throws Exception {
            UUID articleUuid = UUID.randomUUID();
            when(articleLookupFacade.findByUuid(articleUuid)).thenReturn(Optional.of(
                    new ArticleData(1L, articleUuid, USER_A_ID, "DRAFT", null, null)));
            String fileId = uploadAndGetFileId(UsageType.ARTICLE_CONTENT, USER_A_ID, Role.AUTHOR, articleUuid);

            mockMvc.perform(get("/api/v1/files/" + fileId + "/content")
                            .with(asUser(USER_B_ID, Role.ADMIN)))
                    .andExpect(status().isFound())
                    .andExpect(header().exists("Location"));
        }

        @Test
        @DisplayName("fail-safe：未綁定任何文章的檔案，匿名存取 → 403")
        void getFileContent_unbound_anonymous_returns403() throws Exception {
            String fileId = uploadAndGetFileId(UsageType.ARTICLE_CONTENT, USER_A_ID, Role.AUTHOR, null);

            mockMvc.perform(get("/api/v1/files/" + fileId + "/content"))
                    .andExpect(status().isForbidden());
        }

        @Test
        @DisplayName("未綁定任何文章的檔案，上傳者本人存取 → 302")
        void getFileContent_unbound_uploader_returns302() throws Exception {
            String fileId = uploadAndGetFileId(UsageType.ARTICLE_CONTENT, USER_A_ID, Role.AUTHOR, null);

            mockMvc.perform(get("/api/v1/files/" + fileId + "/content")
                            .with(asUser(USER_A_ID, Role.AUTHOR)))
                    .andExpect(status().isFound());
        }

        @Test
        @DisplayName("效能：單次 /content 請求只查一次 file_metadata（原本 canRead 與 presign 各查一次）")
        void getFileContent_authorized_queriesMetadataOnlyOnce() throws Exception {
            String fileId = uploadAndGetFileId(UsageType.AVATAR, USER_A_ID, Role.AUTHOR, null);
            UUID fileUuid = UUID.fromString(fileId);
            /** 上傳流程本身也會碰 repository，從這裡開始重新計數 */
            clearInvocations(fileMetadataRepository);

            mockMvc.perform(get("/api/v1/files/" + fileId + "/content"))
                    .andExpect(status().isFound());

            verify(fileMetadataRepository, times(1)).findById(fileUuid);
        }

        @Test
        @DisplayName("快取：302 需帶 private 且短於 presign 效期的 Cache-Control，避免中間層共用他人網址")
        void getFileContent_setsPrivateCacheControlShorterThanPresignExpiry() throws Exception {
            String fileId = uploadAndGetFileId(UsageType.AVATAR, USER_A_ID, Role.AUTHOR, null);

            mockMvc.perform(get("/api/v1/files/" + fileId + "/content"))
                    .andExpect(status().isFound())
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL,
                            allOf(containsString("private"), containsString("max-age=240"))));
        }

        @Test
        @DisplayName("檔案不存在 → 404")
        void getFileContent_fileNotFound_returns404() throws Exception {
            mockMvc.perform(get("/api/v1/files/" + UUID.randomUUID() + "/content"))
                    .andExpect(status().isNotFound());
        }

        @Test
        @DisplayName("回應必須是 302 + 有效 Location，絕不可回傳圖片位元組")
        void getFileContent_response_isRedirectNotBytes() throws Exception {
            String fileId = uploadAndGetFileId(UsageType.AVATAR, USER_A_ID, Role.AUTHOR, null);

            MvcResult result = mockMvc.perform(get("/api/v1/files/" + fileId + "/content"))
                    .andExpect(status().isFound())
                    .andReturn();

            String location = result.getResponse().getHeader("Location");
            assertThat(location).isNotBlank();
            /** 302 導向回應不應帶圖片 body：Content-Type 不應為圖片類型 */
            assertThat(result.getResponse().getContentType()).isNotEqualTo(MediaType.IMAGE_JPEG_VALUE);
        }

        @Test
        @DisplayName("uploadFile 回傳的 url 為相對路徑（以 /api/v1/files/ 開頭，不含 http）")
        void uploadFile_returnsRelativePathUrl() throws Exception {
            MockMultipartFile file = createTestJpeg();

            String uploadResponse = mockMvc.perform(multipart("/api/v1/files/upload")
                            .file(file)
                            .param("usageType", "ARTICLE_CONTENT")
                            .with(asUser(USER_A_ID, Role.AUTHOR))
                            .contentType(MediaType.MULTIPART_FORM_DATA))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            String url = objectMapper.readTree(uploadResponse).path("data").path("url").asText();
            assertThat(url).startsWith("/api/v1/files/");
            assertThat(url).doesNotContain("http");
        }
    }
}
