package dowob.xyz.blog.e2e.red;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.E2EAssertions;
import dowob.xyz.blog.e2e.support.MailInterceptor;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("P0 紅燈 - Auth 生命週期")
class P0AuthLifecycleRedE2E extends AbstractE2ETest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MailInterceptor mailInterceptor;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    @DisplayName("註冊驗證登入 refresh 登出應維持 token 與使用者狀態一致")
    void authLifecycleShouldKeepTokenAndUserStateConsistent() throws Exception {
        String email = "p0-auth-red@test.local";
        String password = "AuthRed123!";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DataBuilder.register(email, password, "p0authred", "P0 Auth Red"))))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        String token = mailInterceptor.interceptVerificationToken(email);

        mockMvc.perform(post("/api/v1/auth/verify-email")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.verifyEmail(token))))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        MvcResult login = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.login(email, password))))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(header().exists("Set-Cookie"))
                .andReturn();

        JsonNode loginData = objectMapper.readTree(login.getResponse().getContentAsString()).get("data");
        String accessToken = loginData.path("accessToken").asText();
        assertThat(accessToken).isNotBlank();

        Cookie refreshCookie = login.getResponse().getCookie("refreshToken");
        assertThat(refreshCookie).as("refresh cookie should be issued to browser clients").isNotNull();
        assertThat(refreshCookie.isHttpOnly()).isTrue();

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .cookie(refreshCookie))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(refreshCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("重複註冊（username 重複） 錯密碼 無 refresh cookie 應回傳穩定錯誤契約")
    void authNegativeCasesShouldUseStableErrorEnvelope() throws Exception {
        String email = "p0-auth-negative-red@test.local";
        String password = "AuthRed123!";

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DataBuilder.register(email, password, "p0authneg", "P0 Auth Negative"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DataBuilder.register("p0-auth-negative-red-2@test.local", password,
                                        "p0authneg", "P0 Auth Negative 2"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.login(email, "Wrong123!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(post("/api/v1/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").isNotEmpty())
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
