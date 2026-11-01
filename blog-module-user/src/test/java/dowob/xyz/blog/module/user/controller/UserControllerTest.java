package dowob.xyz.blog.module.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.AuthStateLoader;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.user.model.dto.request.ChangePasswordRequest;
import dowob.xyz.blog.module.user.model.dto.request.DeleteAccountRequest;
import dowob.xyz.blog.module.user.model.dto.request.NotificationPreferencesRequest;
import dowob.xyz.blog.module.user.model.dto.request.UpdateProfileRequest;
import dowob.xyz.blog.module.user.model.dto.response.UserProfileResponse;
import dowob.xyz.blog.module.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * UserController 單元測試（Web 層）
 *
 * <p>
 * 使用 {@code @WebMvcTest} 僅載入 Web 層，透過 MockMvc 驗證各端點的
 * HTTP 請求處理行為，包含 Authentication 注入、請求驗證與業務例外映射。
 * 所有服務依賴均以 {@code @MockitoBean} 替代。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    /** MockMvc，用於模擬 HTTP 請求 */
    @Autowired
    private MockMvc mockMvc;

    /** Jackson 物件序列化工具 */
    @Autowired
    private ObjectMapper objectMapper;

    /** Mock：用戶自助業務服務 */
    @MockitoBean
    private UserService userService;

    /** Mock：JWT 服務（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private JwtService jwtService;

    /** Mock：Redis 模板（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private StringRedisTemplate redisTemplate;

    /** Mock：用戶認證服務（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private UserAuthService userAuthService;

    /** Mock：認證狀態載入器（JwtAuthenticationFilter 所需） */
    @MockitoBean
    private AuthStateLoader authStateLoader;

    /** 測試用登入者 UUID（controller 應以此 uuid 進入 UserService） */
    private static final UUID USER_UUID = new UUID(0L, 1L);

    /** 測試用 Authentication（principal=AuthUser(uuid=USER_UUID)，角色=ROLE_USER） */
    private static final UsernamePasswordAuthenticationToken USER_AUTH =
            new UsernamePasswordAuthenticationToken(
                    new AuthUser(USER_UUID, Role.USER), null,
                    List.of(new SimpleGrantedAuthority(Role.USER.getSpringSecurityRole())));

    // =========================================================================
    // PATCH /api/v1/users/me/profile 測試
    // =========================================================================

    /**
     * 驗證：已登入用戶提交合法請求應成功更新個人資料並回傳 200。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → 已登入，合法請求 → 應回傳 200 成功回應")
    void updateProfile_authenticatedUser_shouldReturn200() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");
        request.setBio("新的個人簡介");

        doNothing().when(userService).updateProfile(any(UUID.class), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * 驗證：未登入時存取應被拒絕（403 Forbidden）。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → 未登入 → 應回傳 401")
    void updateProfile_unauthenticated_shouldReturn403() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 驗證：暱稱為空白時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → 暱稱為空白 → 應回傳驗證錯誤")
    void updateProfile_blankNickname_shouldReturnValidationError() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("");

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    /**
     * 驗證：暱稱已被使用時業務例外應映射為 NICKNAME_DUPLICATED 錯誤碼。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → 重複暱稱 → 應回傳 NICKNAME_DUPLICATED 錯誤碼")
    void updateProfile_duplicateNickname_shouldReturnNicknameDuplicatedError() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("takenNickname");

        doThrow(new BusinessException(UserErrorCode.NICKNAME_DUPLICATED))
                .when(userService).updateProfile(any(UUID.class), anyString(), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.NICKNAME_DUPLICATED.getCode()));
    }

    /**
     * 驗證：profile 請求帶 avatarUrl 與 location 時應傳遞至 service 持久化。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → 含 avatarUrl 與 location → 應傳遞至 service")
    void updateProfile_withAvatarUrlAndLocation_shouldPassToService() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");
        request.setAvatarUrl("https://cdn.example.com/avatar.png");
        request.setLocation("Taipei");

        doNothing().when(userService).updateProfile(any(UUID.class), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        verify(userService).updateProfile(eq(USER_UUID), eq("newNickname"), nullable(String.class),
                nullable(String.class), nullable(String.class),
                eq("https://cdn.example.com/avatar.png"), eq("Taipei"));
    }

    // =========================================================================
    // PATCH /api/v1/users/me/profile — SEC-10 URL 欄位協定白名單
    // =========================================================================

    /**
     * 送出指定欄位值的 profile PATCH 請求。
     */
    private org.springframework.test.web.servlet.ResultActions patchProfile(UpdateProfileRequest request)
            throws Exception {
        return mockMvc.perform(patch("/api/v1/users/me/profile")
                .with(authentication(USER_AUTH))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    /**
     * 驗證：website 為 javascript: URL 時回傳 400，格式與既有參數驗證錯誤一致（code=400，訊息含欄位名），且不呼叫 service。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → website 為 javascript: → 400 且 service 不被呼叫（SEC-10）")
    void updateProfile_javascriptWebsite_shouldReturn400() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");
        request.setWebsite("javascript:alert(1)");

        patchProfile(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("website")));

        verifyNoInteractions(userService);
    }

    /**
     * 驗證：avatarUrl 為 data: URL 時回傳 400。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → avatarUrl 為 data: → 400 且 service 不被呼叫（SEC-10）")
    void updateProfile_dataAvatarUrl_shouldReturn400() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");
        request.setAvatarUrl("data:text/html,<script>alert(1)</script>");

        patchProfile(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("avatarUrl")));

        verifyNoInteractions(userService);
    }

    /**
     * 驗證：website 為 protocol-relative URL 時回傳 400。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → website 為 //evil.com → 400（SEC-10）")
    void updateProfile_protocolRelativeWebsite_shouldReturn400() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");
        request.setWebsite("//evil.com");

        patchProfile(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("400"));

        verifyNoInteractions(userService);
    }

    /**
     * 驗證：avatarUrl 接受檔案上傳 API 回傳的本站相對路徑（頭貼上傳流程不可被擋）。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → avatarUrl 為本站檔案內容路徑 → 200（SEC-10 不破壞頭貼上傳）")
    void updateProfile_fileContentPathAvatar_shouldReturn200() throws Exception {
        String path = "/api/v1/files/11111111-2222-3333-4444-555555555555/content";
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");
        request.setAvatarUrl(path);

        doNothing().when(userService).updateProfile(any(UUID.class), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));

        patchProfile(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("00000"));

        verify(userService).updateProfile(eq(USER_UUID), eq("newNickname"), nullable(String.class),
                nullable(String.class), nullable(String.class), eq(path), nullable(String.class));
    }

    /**
     * 驗證：socialLinks 的 value 為網址（含 scheme）時回傳 400。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → socialLinks value 為 javascript: → 400 且訊息含欄位名（SEC-10）")
    void updateProfile_javascriptSocialLink_shouldReturn400() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");
        request.setSocialLinks("{\"github\":\"javascript:alert(1)\"}");

        patchProfile(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("socialLinks")));

        verifyNoInteractions(userService);
    }

    /**
     * 驗證：socialLinks 項目數超過上限時回傳 400。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → socialLinks 超過 10 個項目 → 400（SEC-10）")
    void updateProfile_tooManySocialLinks_shouldReturn400() throws Exception {
        StringBuilder json = new StringBuilder("{");
        for (int i = 0; i < 11; i++) {
            json.append(i == 0 ? "" : ",").append("\"k").append(i).append("\":\"v\"");
        }
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");
        request.setSocialLinks(json.append("}").toString());

        patchProfile(request).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("400"));

        verifyNoInteractions(userService);
    }

    /**
     * 驗證：前端設定頁實際送出的 payload（handle 格式，E2E 的 myhandle 等）可通過驗證並傳遞至 service。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → 前端實際 payload（handle 與合法網址）→ 200 並傳遞至 service（SEC-10）")
    void updateProfile_frontendPayload_shouldReturn200() throws Exception {
        String social = "{\"github\":\"myhandle\",\"twitter\":\"mytwitter\",\"linkedin\":\"mylinkedin\"}";
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");
        request.setWebsite("https://myblog.com/about?x=1");
        request.setSocialLinks(social);
        request.setAvatarUrl("https://cdn.example.com/avatar.png");

        doNothing().when(userService).updateProfile(any(UUID.class), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));

        patchProfile(request).andExpect(status().isOk()).andExpect(jsonPath("$.code").value("00000"));

        verify(userService).updateProfile(eq(USER_UUID), eq("newNickname"), nullable(String.class),
                eq("https://myblog.com/about?x=1"), eq(social),
                eq("https://cdn.example.com/avatar.png"), nullable(String.class));
    }

    /**
     * 驗證：URL 欄位為空字串（清除）時通過驗證。
     */
    @Test
    @DisplayName("PATCH /users/me/profile → website／avatarUrl／socialLinks 為空字串 → 200（清除）")
    void updateProfile_emptyUrlFields_shouldReturn200() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");
        request.setWebsite("");
        request.setAvatarUrl("");
        request.setSocialLinks("");

        doNothing().when(userService).updateProfile(any(UUID.class), anyString(), nullable(String.class),
                nullable(String.class), nullable(String.class), nullable(String.class), nullable(String.class));

        patchProfile(request).andExpect(status().isOk());
    }

    // =========================================================================
    // PATCH /api/v1/users/me/notifications 測試
    // =========================================================================

    /**
     * 驗證：已登入用戶提交合法通知偏好應成功更新並回傳 200。
     */
    @Test
    @DisplayName("PATCH /users/me/notifications → 已登入，合法請求 → 應回傳 200 並更新偏好")
    void updateNotifications_authenticatedUser_shouldReturn200() throws Exception {
        NotificationPreferencesRequest request = new NotificationPreferencesRequest();
        request.setComment(false);
        request.setLike(true);
        request.setReview(false);
        request.setFollow(true);
        request.setNewsletter(false);

        doNothing().when(userService).updateNotificationPreferences(
                any(UUID.class), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());

        mockMvc.perform(patch("/api/v1/users/me/notifications")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));

        verify(userService).updateNotificationPreferences(USER_UUID, false, true, false, true, false);
    }

    /**
     * 驗證：未登入存取通知偏好端點應回傳 401。
     */
    @Test
    @DisplayName("PATCH /users/me/notifications → 未登入 → 應回傳 401")
    void updateNotifications_unauthenticated_shouldReturn401() throws Exception {
        NotificationPreferencesRequest request = new NotificationPreferencesRequest();
        request.setComment(true);
        request.setLike(true);
        request.setReview(true);
        request.setFollow(true);
        request.setNewsletter(true);

        mockMvc.perform(patch("/api/v1/users/me/notifications")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    // =========================================================================
    // POST /api/v1/users/me/change-password 測試
    // =========================================================================

    /**
     * 驗證：已登入用戶提交合法請求應成功修改密碼並回傳 200。
     */
    @Test
    @DisplayName("POST /users/me/change-password → 已登入，合法請求 → 應回傳 200 成功回應")
    void changePassword_authenticatedUser_shouldReturn200() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("oldPass123");
        request.setNewPassword("NewPass456!");

        doNothing().when(userService).changePassword(any(UUID.class), anyString(), anyString());

        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * 驗證：未登入時存取應被拒絕（403 Forbidden）。
     */
    @Test
    @DisplayName("POST /users/me/change-password → 未登入 → 應回傳 401")
    void changePassword_unauthenticated_shouldReturn403() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("oldPass123");
        request.setNewPassword("NewPass456!");

        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 驗證：舊密碼錯誤時應回傳 USER_PASSWORD_ERROR 錯誤碼。
     */
    @Test
    @DisplayName("POST /users/me/change-password → 舊密碼錯誤 → 應回傳 USER_PASSWORD_ERROR 錯誤碼")
    void changePassword_wrongOldPassword_shouldReturnUserPasswordError() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("wrongOld");
        request.setNewPassword("NewPass456!");

        doThrow(new BusinessException(UserErrorCode.USER_PASSWORD_ERROR))
                .when(userService).changePassword(any(UUID.class), anyString(), anyString());

        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.USER_PASSWORD_ERROR.getCode()));
    }

    // =========================================================================
    // DELETE /api/v1/users/me 測試
    // =========================================================================

    /**
     * 驗證：已登入用戶透過 JSON body 提供正確密碼應成功刪除帳號並回傳 200。
     */
    @Test
    @DisplayName("DELETE /users/me → 已登入，JSON body 正確密碼 → 應回傳 200 成功回應")
    void deleteAccount_authenticatedUser_shouldReturn200() throws Exception {
        doNothing().when(userService).deleteAccount(any(UUID.class), anyString());

        DeleteAccountRequest request = new DeleteAccountRequest();
        request.setPassword("correctPassword");

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * 驗證：未登入時存取應被拒絕（401 Unauthorized）。
     */
    @Test
    @DisplayName("DELETE /users/me → 未登入 → 應回傳 401")
    void deleteAccount_unauthenticated_shouldReturn401() throws Exception {
        DeleteAccountRequest request = new DeleteAccountRequest();
        request.setPassword("somePassword");

        mockMvc.perform(delete("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 驗證：密碼錯誤時應回傳 USER_PASSWORD_ERROR 錯誤碼。
     */
    @Test
    @DisplayName("DELETE /users/me → 密碼錯誤 → 應回傳 USER_PASSWORD_ERROR 錯誤碼")
    void deleteAccount_wrongPassword_shouldReturnUserPasswordError() throws Exception {
        doThrow(new BusinessException(UserErrorCode.USER_PASSWORD_ERROR))
                .when(userService).deleteAccount(any(UUID.class), anyString());

        DeleteAccountRequest request = new DeleteAccountRequest();
        request.setPassword("wrongPassword");

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.USER_PASSWORD_ERROR.getCode()));
    }

    /**
     * 驗證：刪除帳號請求密碼為空時應回傳驗證錯誤。
     */
    @Test
    @DisplayName("DELETE /users/me → 密碼為空 → 應回傳驗證錯誤")
    void deleteAccount_blankPassword_shouldReturnValidationError() throws Exception {
        DeleteAccountRequest request = new DeleteAccountRequest();
        request.setPassword("");

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    /**
     * 驗證：刪除帳號時 6 字元密碼應通過驗證（與全站密碼政策 min=6 一致）。
     */
    @Test
    @DisplayName("DELETE /users/me → 6 字元密碼 → 應通過驗證回傳 200")
    void deleteAccount_sixCharPassword_shouldPassValidation() throws Exception {
        DeleteAccountRequest request = new DeleteAccountRequest();
        request.setPassword("abc123");

        doNothing().when(userService).deleteAccount(any(UUID.class), anyString());

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * 驗證：刪除帳號時 5 字元密碼應被驗證層擋下（低於全站密碼政策 min=6）。
     */
    @Test
    @DisplayName("DELETE /users/me → 5 字元密碼 → 應回傳驗證錯誤")
    void deleteAccount_fiveCharPassword_shouldReturnValidationError() throws Exception {
        DeleteAccountRequest request = new DeleteAccountRequest();
        request.setPassword("abc12");

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }

    // =========================================================================
    // GET /api/v1/users/me 測試
    // =========================================================================

    /**
     * 驗證：已登入用戶呼叫 GET /users/me 應回傳 200 及使用者資料。
     */
    @Test
    @DisplayName("GET /users/me → 已登入 → 應回傳 200 及使用者資料")
    void getMe_authenticated_shouldReturn200WithUserProfile() throws Exception {
        UUID testUuid = UUID.randomUUID();
        UserProfileResponse profile = new UserProfileResponse(
                testUuid,
                "test@example.com",
                "testNickname",
                null,
                "https://example.com/avatar.png",
                null,
                null,
                "Taipei",
                Role.USER,
                true,
                LocalDateTime.of(2024, 1, 1, 0, 0),
                true,
                false,
                true,
                false,
                true
        );
        when(userService.getUserProfile(USER_UUID)).thenReturn(profile);

        mockMvc.perform(get("/api/v1/users/me")
                        .with(authentication(USER_AUTH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andExpect(jsonPath("$.data.uuid").value(testUuid.toString()))
                .andExpect(jsonPath("$.data.email").value("test@example.com"))
                .andExpect(jsonPath("$.data.nickname").value("testNickname"))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.avatarUrl").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.data.location").value("Taipei"))
                .andExpect(jsonPath("$.data.notificationComment").value(true))
                .andExpect(jsonPath("$.data.notificationLike").value(false))
                .andExpect(jsonPath("$.data.notificationNewsletter").value(true))
                .andExpect(jsonPath("$.data.emailVerified").value(true));
    }

    /**
     * 驗證：未登入時呼叫 GET /users/me 應回傳 401。
     */
    @Test
    @DisplayName("GET /users/me → 未登入 → 應回傳 401")
    void getMe_unauthenticated_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * 驗證：使用者不存在時應回傳 USER_NOT_FOUND 錯誤碼。
     */
    @Test
    @DisplayName("GET /users/me → 使用者不存在 → 應回傳 USER_NOT_FOUND 錯誤碼")
    void getMe_userNotFound_shouldReturnUserNotFoundError() throws Exception {
        when(userService.getUserProfile(USER_UUID))
                .thenThrow(new BusinessException(UserErrorCode.USER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/users/me")
                        .with(authentication(USER_AUTH)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(UserErrorCode.USER_NOT_FOUND.getCode()));
    }

    // =========================================================================
    // 密碼複雜度驗證測試
    // =========================================================================

    @Test
    @DisplayName("POST /users/me/change-password → 新密碼只含英文字母（無數字）→ 應回傳 400")
    void changePassword_weakNewPassword_noNumber_shouldReturn400() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("Test1234");
        request.setNewPassword("Abcdefghij");

        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
    }
}
