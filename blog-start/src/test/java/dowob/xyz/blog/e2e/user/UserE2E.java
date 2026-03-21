package dowob.xyz.blog.e2e.user;

import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.E2EAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * User 模組 E2E 測試
 *
 * <p>涵蓋更新個人資料、修改密碼、刪除帳號等用戶自助服務功能。</p>
 */
@DisplayName("User E2E 測試")
class UserE2E extends AbstractE2ETest {

    private static final String BASE_URL = "/api/v1/users";

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private AuthHelper authHelper;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    @DisplayName("更新個人資料 — 修改暱稱成功")
    void updateProfile_success() throws Exception {
        // Arrange — 註冊並登入
        String token = authHelper.registerAndLogin(
                "profile@test.com", "password123", "profileuser", "ProfileUser");

        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("nickname", "NewNickname");
        updateBody.put("bio", "這是我的個人簡介");
        updateBody.put("website", "https://myblog.com");

        // Act
        mockMvc.perform(patch(BASE_URL + "/me/profile")
                        .with(AuthHelper.bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());
    }

    @Test
    @DisplayName("修改密碼 — 舊密碼驗證通過後更新，舊密碼失效、新密碼可用")
    void changePassword_success() throws Exception {
        // Arrange — 註冊並登入
        String token = authHelper.registerAndLogin(
                "changepw@test.com", "oldPassword1", "changepwuser", "ChangePwUser");

        Map<String, Object> changePwBody = new LinkedHashMap<>();
        changePwBody.put("oldPassword", "oldPassword1");
        changePwBody.put("newPassword", "newPassword1");

        // Act — 修改密碼
        mockMvc.perform(post(BASE_URL + "/me/change-password")
                        .with(AuthHelper.bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(changePwBody)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // Assert — 舊密碼登入失敗
        Map<String, Object> oldPwLogin = new LinkedHashMap<>();
        oldPwLogin.put("identifier", "changepw@test.com");
        oldPwLogin.put("password", "oldPassword1");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(oldPwLogin)))
                .andExpect(status().isBadRequest())
                .andExpect(E2EAssertions.apiError("A0102"));

        // Assert — 新密碼登入成功
        Map<String, Object> newPwLogin = new LinkedHashMap<>();
        newPwLogin.put("identifier", "changepw@test.com");
        newPwLogin.put("password", "newPassword1");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newPwLogin)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());
    }

    @Test
    @DisplayName("刪除帳號 — 刪除後後續 API 呼叫回傳 401")
    void deleteAccount_success() throws Exception {
        // Arrange — 註冊並登入
        String token = authHelper.registerAndLogin(
                "deleteacc@test.com", "password123", "deleteaccuser", "DeleteAccUser");

        // Act — 刪除帳號
        mockMvc.perform(delete(BASE_URL + "/me")
                        .with(AuthHelper.bearerToken(token))
                        .param("password", "password123"))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // Assert — 使用同一 token 存取需認證的 API 應失敗
        Map<String, Object> profileBody = new LinkedHashMap<>();
        profileBody.put("nickname", "Ghost");

        mockMvc.perform(patch(BASE_URL + "/me/profile")
                        .with(AuthHelper.bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(profileBody)))
                .andExpect(status().isUnauthorized());
    }
}
