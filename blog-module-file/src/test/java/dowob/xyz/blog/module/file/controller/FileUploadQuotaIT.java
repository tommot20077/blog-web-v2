package dowob.xyz.blog.module.file.controller;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * G5：檔案上傳配額不足整合測試
 *
 * <p>
 * 透過 properties override 把 AUTHOR 配額設為 1B，
 * 任何合法檔案上傳都會超過配額觸發 {@code FileErrorCode.QUOTA_EXCEEDED}（A0403）。
 * 對應前端 e2e mock spec：{@code e2e/integration/author-file-upload.spec.ts} 的 G5。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(
        classes = TestFileApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"blog.file.quotas.AUTHOR=1B"}
)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("FileController 配額不足整合測試 (G5)")
class FileUploadQuotaIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static com.redis.testcontainers.RedisContainer redis =
            new com.redis.testcontainers.RedisContainer("redis:7-alpine");

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

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

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FileMetadataRepository fileMetadataRepository;

    @MockitoBean
    private ConnectionFactory connectionFactory;

    @MockitoBean
    private RabbitTemplate rabbitTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    @MockitoBean
    private UserFacade userFacade;

    /**
     * Mock ArticleFacade（FileServiceImpl.canRead 依賴；article 模組實作未在本 IT 的
     * scanBasePackages 內，須 mock 避免 context 啟動失敗）
     */
    @MockitoBean
    private ArticleFacade articleFacade;

    private static final Long AUTHOR_ID = 1L;
    private static final UUID AUTHOR_UUID = UUID.randomUUID();

    @BeforeEach
    void setUpUserFacade() {
        when(userFacade.getUserUuidById(AUTHOR_ID)).thenReturn(Optional.of(AUTHOR_UUID));
    }

    @BeforeAll
    static void setupMinio(@Autowired MinioClient minioClient) throws Exception {
        if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket("blog-files").build())) {
            minioClient.makeBucket(MakeBucketArgs.builder().bucket("blog-files").build());
        }
    }

    @AfterEach
    void cleanUp() {
        fileMetadataRepository.deleteAll();
    }

    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    private MockMultipartFile createTestJpeg() throws Exception {
        byte[] jpegBytes = getClass().getClassLoader()
                .getResourceAsStream("test.jpg").readAllBytes();
        return new MockMultipartFile("file", "test.jpg", "image/jpeg", jpegBytes);
    }

    @Test
    @DisplayName("G5: AUTHOR quota 設 1B 上傳合法 JPEG 應回 A0403 (QUOTA_EXCEEDED)")
    void uploadFile_quotaExceeded_returnsA0403() throws Exception {
        MockMultipartFile file = createTestJpeg();

        mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(file)
                        .param("usageType", "ARTICLE_CONTENT")
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0403"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
