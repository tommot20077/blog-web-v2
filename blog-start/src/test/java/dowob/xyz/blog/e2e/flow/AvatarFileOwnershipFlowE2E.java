package dowob.xyz.blog.e2e.flow;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.E2EAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 頭像檔案歸屬流程 E2E（A6b 審查修正）：設定頭像 URL 指向站內檔案時，檔案必須是本人上傳的 AVATAR。
 *
 * <p>上傳端點的 {@code usageType} 由 client 指定，頭像 5MB 上限只對 AVATAR 生效；
 * 若不驗證，選 ARTICLE_CONTENT 上傳（上限 20MB）再設成頭像即可繞過，也能把別人的檔案設成自己的頭像。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("頭像檔案歸屬流程 E2E")
class AvatarFileOwnershipFlowE2E extends AbstractE2ETest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private AuthHelper authHelper;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    private String upload(String token, String usageType) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg",
                getClass().getClassLoader().getResourceAsStream("test-files/test.jpg"));
        String body = mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(file)
                        .param("usageType", usageType)
                        .with(AuthHelper.bearerToken(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("data").get("url").asText();
    }

    private org.springframework.test.web.servlet.ResultActions setAvatar(String token, String avatarUrl) throws Exception {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("nickname", "AvatarNick");
        profile.put("avatarUrl", avatarUrl);
        return mockMvc.perform(patch("/api/v1/users/me/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(profile))
                .with(AuthHelper.bearerToken(token)));
    }

    @Test
    @DisplayName("ARTICLE_CONTENT 的檔案設成頭像 → 400 A0407；他人的 AVATAR → 400 A0407；自己的 AVATAR → 成功")
    void setAvatar_enforcesAvatarUsageAndOwnership() throws Exception {
        String me = authHelper.createUserWithRole("av-me@test.com", "Password123!", "avme", "AvMe", Role.AUTHOR);
        String other = authHelper.createUserWithRole("av-other@test.com", "Password123!", "avother", "AvOther", Role.AUTHOR);
        String myArticleImage = upload(me, "ARTICLE_CONTENT");
        String othersAvatar = upload(other, "AVATAR");
        String myAvatar = upload(me, "AVATAR");

        setAvatar(me, myArticleImage)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0407"));
        setAvatar(me, othersAvatar)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0407"));
        setAvatar(me, myAvatar)
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());
        /* 站外 URL 維持現行行為；頭像未變更時再存一次不重驗 */
        setAvatar(me, "https://cdn.example.com/a.png").andExpect(status().isOk());
        setAvatar(me, "https://cdn.example.com/a.png").andExpect(status().isOk());
    }
}
