package dowob.xyz.blog.module.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.infrastructure.config.SecurityConfig;
import dowob.xyz.blog.infrastructure.security.JwtService;
import dowob.xyz.blog.infrastructure.security.UserAuthService;
import dowob.xyz.blog.module.user.model.dto.request.ChangePasswordRequest;
import dowob.xyz.blog.module.user.model.dto.request.UpdateProfileRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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

    /** 測試用 Authentication（principal=1L，角色=ROLE_USER） */
    private static final UsernamePasswordAuthenticationToken USER_AUTH =
            new UsernamePasswordAuthenticationToken(
                    1L, null,
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

        doNothing().when(userService).updateProfile(anyLong(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class));

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
    @DisplayName("PATCH /users/me/profile → 未登入 → 應回傳 403")
    void updateProfile_unauthenticated_shouldReturn403() throws Exception {
        UpdateProfileRequest request = new UpdateProfileRequest();
        request.setNickname("newNickname");

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
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
                .andExpect(status().isOk())
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
                .when(userService).updateProfile(anyLong(), anyString(), nullable(String.class), nullable(String.class), nullable(String.class));

        mockMvc.perform(patch("/api/v1/users/me/profile")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(UserErrorCode.NICKNAME_DUPLICATED.getCode()));
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
        request.setNewPassword("newPass456");

        doNothing().when(userService).changePassword(anyLong(), anyString(), anyString());

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
    @DisplayName("POST /users/me/change-password → 未登入 → 應回傳 403")
    void changePassword_unauthenticated_shouldReturn403() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("oldPass123");
        request.setNewPassword("newPass456");

        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    /**
     * 驗證：舊密碼錯誤時應回傳 USER_PASSWORD_ERROR 錯誤碼。
     */
    @Test
    @DisplayName("POST /users/me/change-password → 舊密碼錯誤 → 應回傳 USER_PASSWORD_ERROR 錯誤碼")
    void changePassword_wrongOldPassword_shouldReturnUserPasswordError() throws Exception {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setOldPassword("wrongOld");
        request.setNewPassword("newPass456");

        doThrow(new BusinessException(UserErrorCode.USER_PASSWORD_ERROR))
                .when(userService).changePassword(anyLong(), anyString(), anyString());

        mockMvc.perform(post("/api/v1/users/me/change-password")
                        .with(authentication(USER_AUTH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(UserErrorCode.USER_PASSWORD_ERROR.getCode()));
    }

    // =========================================================================
    // DELETE /api/v1/users/me 測試
    // =========================================================================

    /**
     * 驗證：已登入用戶提供正確密碼應成功刪除帳號並回傳 200。
     */
    @Test
    @DisplayName("DELETE /users/me → 已登入，正確密碼 → 應回傳 200 成功回應")
    void deleteAccount_authenticatedUser_shouldReturn200() throws Exception {
        doNothing().when(userService).deleteAccount(anyLong(), anyString());

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(authentication(USER_AUTH))
                        .param("password", "correctPassword"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"));
    }

    /**
     * 驗證：未登入時存取應被拒絕（403 Forbidden）。
     */
    @Test
    @DisplayName("DELETE /users/me → 未登入 → 應回傳 403")
    void deleteAccount_unauthenticated_shouldReturn403() throws Exception {
        mockMvc.perform(delete("/api/v1/users/me")
                        .param("password", "somePassword"))
                .andExpect(status().isForbidden());
    }

    /**
     * 驗證：密碼錯誤時應回傳 USER_PASSWORD_ERROR 錯誤碼。
     */
    @Test
    @DisplayName("DELETE /users/me → 密碼錯誤 → 應回傳 USER_PASSWORD_ERROR 錯誤碼")
    void deleteAccount_wrongPassword_shouldReturnUserPasswordError() throws Exception {
        doThrow(new BusinessException(UserErrorCode.USER_PASSWORD_ERROR))
                .when(userService).deleteAccount(anyLong(), anyString());

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(authentication(USER_AUTH))
                        .param("password", "wrongPassword"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(UserErrorCode.USER_PASSWORD_ERROR.getCode()));
    }
}
