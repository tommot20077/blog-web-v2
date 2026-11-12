package dowob.xyz.blog.module.user.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.exception.HttpStatusBusinessException;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.AuthStateLoader;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.user.model.dto.response.AuthorSummaryResponse;
import dowob.xyz.blog.module.user.model.dto.response.PublicProfileResponse;
import dowob.xyz.blog.module.user.service.PublicAuthorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link PublicAuthorController} Web 層測試（載入真實 {@link SecurityConfig}）
 *
 * <p>重點：兩個端點匿名（不帶 token）可達；公開資料回應的 JSON 欄位集合以白名單斷言，
 * 防止日後有人在 DTO 加 {@code email}／{@code username}／{@code role}；不存在或非 ACTIVE 回 404 {@code A0101}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(PublicAuthorController.class)
@Import(SecurityConfig.class)
class PublicAuthorControllerTest {

    /** 測試用公開 UUID（隨機，與任何 id 無同構關係） */
    private static final UUID TARGET = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PublicAuthorService publicAuthorService;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private StringRedisTemplate redisTemplate;

    @MockitoBean
    private UserAuthService userAuthService;

    @MockitoBean
    private AuthStateLoader authStateLoader;

    private PublicProfileResponse profile() {
        Map<String, String> links = new LinkedHashMap<>();
        links.put("github", "alice");
        links.put("twitter", null);
        links.put("linkedin", null);
        return new PublicProfileResponse(TARGET, "Alice", null, "bio", "Taipei", "https://a.example.com",
                links, LocalDateTime.of(2025, 3, 4, 5, 6), new PublicProfileResponse.Stats(3L, 1500L, 42L));
    }

    @Test
    @DisplayName("匿名 GET /users/{uuid}/profile → 200，data 欄位集合恰為白名單（不含 email／username／role）")
    void getPublicProfile_anonymous_returns200WithWhitelistedKeys() throws Exception {
        when(publicAuthorService.getPublicProfile(TARGET)).thenReturn(profile());

        String body = mockMvc.perform(get("/api/v1/users/" + TARGET + "/profile"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.uuid").value(TARGET.toString()))
                .andExpect(jsonPath("$.data.stats.publishedArticleCount").value(3))
                .andExpect(jsonPath("$.data.stats.totalViewCount").value(1500))
                .andExpect(jsonPath("$.data.stats.totalLikeCount").value(42))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(body).get("data");
        assertThat(fieldNames(data)).containsExactlyInAnyOrder("uuid", "nickname", "avatarUrl", "bio", "location",
                "website", "socialLinks", "joinedAt", "stats");
        assertThat(fieldNames(data.get("stats")))
                .containsExactlyInAnyOrder("publishedArticleCount", "totalViewCount", "totalLikeCount");
        assertThat(fieldNames(data.get("socialLinks"))).containsExactlyInAnyOrder("github", "twitter", "linkedin");
        assertThat(body).doesNotContain("email").doesNotContain("username").doesNotContain("role");
    }

    @Test
    @DisplayName("service 回 404 A0101（不存在／DELETED／未驗證）→ HTTP 404 且 code=A0101")
    void getPublicProfile_notFound_returns404WithA0101() throws Exception {
        when(publicAuthorService.getPublicProfile(TARGET))
                .thenThrow(new HttpStatusBusinessException(UserErrorCode.USER_NOT_FOUND, HttpStatus.NOT_FOUND));

        mockMvc.perform(get("/api/v1/users/" + TARGET + "/profile"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("A0101"));
    }

    @Test
    @DisplayName("匿名 GET /authors → 200，keyword 原樣交給 service")
    void searchAuthors_anonymous_returns200AndForwardsKeyword() throws Exception {
        UUID author = UUID.randomUUID();
        when(publicAuthorService.searchAuthors("ali"))
                .thenReturn(List.of(new AuthorSummaryResponse(author, "Alice", null, 7L)));

        String body = mockMvc.perform(get("/api/v1/authors").param("keyword", "ali"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data[0].uuid").value(author.toString()))
                .andExpect(jsonPath("$.data[0].nickname").value("Alice"))
                .andExpect(jsonPath("$.data[0].publishedArticleCount").value(7))
                .andReturn().getResponse().getContentAsString();

        assertThat(fieldNames(objectMapper.readTree(body).get("data").get(0)))
                .containsExactlyInAnyOrder("uuid", "nickname", "avatarUrl", "publishedArticleCount");
        verify(publicAuthorService).searchAuthors("ali");
    }

    @Test
    @DisplayName("匿名 GET /authors 不帶 keyword → 200，service 收到 null")
    void searchAuthors_withoutKeyword_passesNull() throws Exception {
        when(publicAuthorService.searchAuthors(null)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/authors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("keyword 超過 50 字（service 擲 PARAM_VALID_ERROR）→ HTTP 400")
    void searchAuthors_keywordTooLong_returns400() throws Exception {
        String longKeyword = "a".repeat(51);
        when(publicAuthorService.searchAuthors(longKeyword))
                .thenThrow(new BusinessException(CommonErrorCode.PARAM_VALID_ERROR));

        mockMvc.perform(get("/api/v1/authors").param("keyword", longKeyword))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(CommonErrorCode.PARAM_VALID_ERROR.getCode()));
    }

    @Test
    @DisplayName("反向：匿名 GET /users/me/profile → 401（permitAll 只限 UUID 形狀的路徑）")
    void getMeProfile_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("反向：匿名 GET /users/not-a-uuid/profile → 401")
    void getProfileWithNonUuid_anonymous_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/users/not-a-uuid/profile")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("反向：匿名 PATCH /users/{uuid}/profile → 401（只開放 GET）")
    void patchProfile_anonymous_returns401() throws Exception {
        mockMvc.perform(patch("/api/v1/users/" + TARGET + "/profile")).andExpect(status().isUnauthorized());
    }

    private Set<String> fieldNames(JsonNode node) {
        return StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(node.fieldNames(), 0), false)
                .collect(Collectors.toSet());
    }
}
