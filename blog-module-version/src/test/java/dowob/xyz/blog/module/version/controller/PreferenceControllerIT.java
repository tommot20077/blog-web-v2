package dowob.xyz.blog.module.version.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.infrastructure.facade.FileFacade;
import dowob.xyz.blog.infrastructure.facade.ReadingFacade;
import dowob.xyz.blog.infrastructure.facade.SeriesFacade;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.infrastructure.facade.UserFacade;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.version.config.VersionTestApplication;
import dowob.xyz.blog.module.version.repository.UserPreferenceRepository;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PreferenceController 整合測試。
 *
 * <p>使用 Testcontainers 啟動 PostgreSQL，
 * 透過 MockMvc 驗證 Preference API 的完整 HTTP 流程與資料庫副作用。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@SpringBootTest(classes = VersionTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
@ActiveProfiles("test")
@DisplayName("PreferenceController 整合測試")
class PreferenceControllerIT {

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
    @Autowired private UserPreferenceRepository prefRepo;

    @MockitoBean private ConnectionFactory connectionFactory;
    @MockitoBean private RabbitTemplate rabbitTemplate;
    @MockitoBean private TagFacade tagFacade;
    @MockitoBean private UserFacade userFacade;
    @MockitoBean private UserAuthService userAuthService;
    @MockitoBean private ReadingFacade readingFacade;
    @MockitoBean private SeriesFacade seriesFacade;
    /** article 模組的 ArticleFileBinder 依賴 FileFacade，其實作在 blog-module-file（未被本測試 scan） */
    @MockitoBean private FileFacade fileFacade;

    private static final Long USER1_ID = 1L;

    @BeforeEach
    void setup() {
        prefRepo.deleteAll();
    }

    @AfterEach
    void cleanUp() {
        prefRepo.deleteAll();
    }

    /** 建立模擬認證的 RequestPostProcessor。 */
    private RequestPostProcessor asUser(Long userId, Role role) {
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority(role.getSpringSecurityRole()));
        role.getPermissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(userId, null, authorities);
        return SecurityMockMvcRequestPostProcessors.authentication(auth);
    }

    // ─────────────────────────────── GET ────────────────────────────────────

    @Test
    @DisplayName("GET /preferences/version - 無 override，所有 field source=system")
    void get_noOverride_returnsAllSystemSource() throws Exception {
        mockMvc.perform(get("/api/v1/me/preferences/version")
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.enabled.source").value("system"))
                .andExpect(jsonPath("$.data.retain.source").value("system"))
                .andExpect(jsonPath("$.data.intervalSeconds.source").value("system"))
                .andExpect(jsonPath("$.data.diffChars.source").value("system"));
    }

    // ─────────────────────────────── PUT ────────────────────────────────────

    @Test
    @DisplayName("PUT /preferences/version - 合法值，所有 field source=user")
    void put_validValues_returns200WithUserSource() throws Exception {
        Map<String, Object> payload = Map.of(
                "enabled", true,
                "retain", 100,
                "intervalSeconds", 120,
                "diffChars", 100);

        mockMvc.perform(put("/api/v1/me/preferences/version")
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.enabled.source").value("user"))
                .andExpect(jsonPath("$.data.retain.source").value("user"))
                .andExpect(jsonPath("$.data.intervalSeconds.source").value("user"))
                .andExpect(jsonPath("$.data.diffChars.source").value("user"));
    }

    @Test
    @DisplayName("PUT /preferences/version - retain=301 超出 [1,300] → 400")
    void put_invalidRetain301_returns400() throws Exception {
        Map<String, Object> payload = Map.of("retain", 301);

        mockMvc.perform(put("/api/v1/me/preferences/version")
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isBadRequest());
    }

    // ─────────────────────────────── DELETE ─────────────────────────────────

    @Test
    @DisplayName("DELETE /preferences/version/retain - 重置後 GET 回 system 預設")
    void delete_existingKey_resetToSystem() throws Exception {
        // 先建立 user override
        Map<String, Object> putPayload = Map.of("retain", 100);
        mockMvc.perform(put("/api/v1/me/preferences/version")
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(putPayload)))
                .andExpect(status().isOk());

        // 刪除 retain override
        mockMvc.perform(delete("/api/v1/me/preferences/version/retain")
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.retain.source").value("system"));
    }

    // ─────────────────────────────── ERROR PATHS ────────────────────────────

    @Test
    @DisplayName("DELETE /preferences/version/{unknownKey} - 未知 key 應回 HTTP 400 + body code V0107")
    void delete_unknownKey_returns400WithV0107() throws Exception {
        mockMvc.perform(delete("/api/v1/me/preferences/version/notARealKey")
                        .with(asUser(USER1_ID, Role.AUTHOR)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("V0107"));
    }

    // ─────────────────────────────── PARTIAL ────────────────────────────────

    @Test
    @DisplayName("PUT /preferences/version - 只設 retain，其他三個仍是 system")
    void put_partialUpdate_otherKeysStaySystem() throws Exception {
        Map<String, Object> payload = Map.of("retain", 100);

        mockMvc.perform(put("/api/v1/me/preferences/version")
                        .with(asUser(USER1_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.retain.source").value("user"))
                .andExpect(jsonPath("$.data.enabled.source").value("system"))
                .andExpect(jsonPath("$.data.intervalSeconds.source").value("system"))
                .andExpect(jsonPath("$.data.diffChars.source").value("system"));
    }
}
