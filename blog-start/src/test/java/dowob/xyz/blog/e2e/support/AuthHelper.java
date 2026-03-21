package dowob.xyz.blog.e2e.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.enums.Role;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E2E 測試用認證輔助工具
 * <p>
 * 透過真實 API 流程取得 JWT Token，不使用 mock。
 */
@Component
public class AuthHelper {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * 註冊 → 激活 → 登入，回傳 accessToken
     */
    public String registerAndLogin(String email, String password,
                                   String username, String nickname) throws Exception {
        // 1. 註冊
        String registerBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("email", email);
            put("password", password);
            put("username", username);
            put("nickname", nickname);
        }});

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody))
                .andExpect(status().isOk());

        // 2. 直接透過 DB 激活帳號（因為 E2E 中 EmailVerificationConsumer 不會真的寄信）
        jdbcTemplate.update(
                "UPDATE users SET status = 'ACTIVE', email_verified = true WHERE email = ?",
                email);

        // 3. 登入取得 accessToken
        return login(email, password);
    }

    /**
     * 登入並回傳 accessToken
     */
    public String login(String identifier, String password) throws Exception {
        String loginBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
            put("identifier", identifier);
            put("password", password);
        }});

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("00000"))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.get("data").get("accessToken").asText();
    }

    /**
     * 建立指定角色的使用者，回傳 accessToken
     */
    public String createUserWithRole(String email, String password,
                                     String username, String nickname,
                                     Role role) throws Exception {
        registerAndLogin(email, password, username, nickname);

        // 更新角色
        jdbcTemplate.update("UPDATE users SET role = ? WHERE email = ?",
                role.name(), email);

        // 重新登入取得包含正確角色的 token
        return login(email, password);
    }

    /**
     * 提供 MockMvc 的 Bearer Token RequestPostProcessor
     */
    public static RequestPostProcessor bearerToken(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }
}
