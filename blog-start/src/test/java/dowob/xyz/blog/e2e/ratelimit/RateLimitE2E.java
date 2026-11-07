package dowob.xyz.blog.e2e.ratelimit;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.module.search.document.ArticleDocument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Map;
import java.util.Set;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 應用層限流 E2E（SEC-23 / A4）：完整應用程式 + 真實 Spring Security + 真實 Redis，
 * <b>在正式門檻下</b>驗證限流本身（其他 E2E 的 profile 把門檻調高，本類別以 {@code @TestPropertySource}
 * 還原 comment-write=10、search=120，窗口維持 application.yaml 的 1 分鐘）。
 *
 * @author Yuan
 * @version 1.0
 */
@TestPropertySource(properties = {
        "app.rate-limit.rules.comment-write.limit=10",
        "app.rate-limit.rules.search.limit=120",
        // 搜尋建議的正式門檻也是 120；為了在 E2E 內快速驗證「帶 Origin 的跨來源 429」，只有這條調低
        "app.rate-limit.rules.search-suggest.limit=2"
})
@DisplayName("應用層限流 E2E（正式門檻）")
class RateLimitE2E extends AbstractE2ETest {

    @Autowired private AuthHelper authHelper;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private ElasticsearchOperations esOps;
    @Autowired private StringRedisTemplate redis;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
        var indexOps = esOps.indexOps(ArticleDocument.class);
        if (indexOps.exists()) {
            indexOps.delete();
            indexOps.createWithMapping();
        }
    }

    private String createPublishedArticle(String authorToken) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/articles")
                        .with(bearerToken(authorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.article("限流 E2E 文章", "內容"))))
                .andExpect(status().isOk())
                .andReturn();
        String uuid = objectMapper.readTree(created.getResponse().getContentAsString()).path("data").path("uuid").asText();
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish").with(bearerToken(authorToken)))
                .andExpect(status().isOk());
        return uuid;
    }

    private void postComment(String token, String articleUuid, int expectedStatus) throws Exception {
        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/comments")
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "限流測試留言"))))
                .andExpect(status().is(expectedStatus));
    }

    private static RequestPostProcessor fromIp(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    @Test
    @DisplayName("留言每人每分鐘 10 次：第 11 次回 429（A0009）並帶 Retry-After；另一位使用者不受影響")
    void commentEleventhRequest_returns429_otherUserUnaffected() throws Exception {
        String authorToken = authHelper.createUserWithRole(
                "rl-author@test.com", "Password1!", "rlauthor", "RlAuthor", Role.AUTHOR);
        String readerA = authHelper.createUserWithRole(
                "rl-a@test.com", "Password1!", "rla", "RlA", Role.USER);
        String readerB = authHelper.createUserWithRole(
                "rl-b@test.com", "Password1!", "rlb", "RlB", Role.USER);
        String articleUuid = createPublishedArticle(authorToken);

        for (int i = 1; i <= 10; i++) {
            postComment(readerA, articleUuid, 200);
        }

        MvcResult limited = mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/comments")
                        .with(bearerToken(readerA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", "第 11 則"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("A0009"))
                .andReturn();
        long retryAfter = Long.parseLong(limited.getResponse().getHeader("Retry-After"));
        assertThat(retryAfter).isBetween(1L, 60L);

        // 另一位使用者的額度獨立
        postComment(readerB, articleUuid, 200);
    }

    @Test
    @DisplayName("未登入的寫入請求回 401，而且不消耗限流額度（Security 先於限流）")
    void unauthenticatedWrite_returns401_andIsNotCounted() throws Exception {
        String authorToken = authHelper.createUserWithRole(
                "rl-author2@test.com", "Password1!", "rlauthor2", "RlAuthor2", Role.AUTHOR);
        String reader = authHelper.createUserWithRole(
                "rl-c@test.com", "Password1!", "rlc", "RlC", Role.USER);
        String articleUuid = createPublishedArticle(authorToken);
        String anonymousIp = "198.51.100.23";

        // 匿名連打 15 次（超過 10 次門檻）：每次都是 401，不會變成 429
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/comments")
                            .with(fromIp(anonymousIp))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("content", "匿名"))))
                    .andExpect(status().isUnauthorized());
        }

        // 沒有任何 comment-write 計數 key 被建立（以 IP 或使用者都沒有）
        Set<String> keys = redis.keys("ratelimit:comment-write:*");
        assertThat(keys).as("未認證請求不應消耗額度").isEmpty();

        // 同一 IP 的已登入使用者仍有完整的 10 次額度
        for (int i = 1; i <= 10; i++) {
            mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/comments")
                            .with(fromIp(anonymousIp))
                            .with(bearerToken(reader))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("content", "登入後 " + i))))
                    .andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("搜尋每 IP 每分鐘 120 次：第 121 次回 429；登入者也以 IP 計數（不產生使用者 key）")
    void searchRequest121_returns429_evenWhenLoggedIn() throws Exception {
        String reader = authHelper.createUserWithRole(
                "rl-search@test.com", "Password1!", "rlsearch", "RlSearch", Role.USER);
        String ip = "198.51.100.77";

        for (int i = 1; i <= 120; i++) {
            mockMvc.perform(get("/api/v1/search").param("q", "limit").with(fromIp(ip)).with(bearerToken(reader)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/search").param("q", "limit").with(fromIp(ip)).with(bearerToken(reader)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("A0009"));

        assertThat(redis.keys("ratelimit:search:ip:" + ip)).hasSize(1);
        assertThat(redis.keys("ratelimit:search:u:*")).isEmpty();

        // 換一個 IP 的匿名者不受影響
        mockMvc.perform(get("/api/v1/search").param("q", "limit").with(fromIp("198.51.100.78")))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("search-suggest 獨立計數；帶 Origin 的跨來源 429 同時帶 Retry-After 與 Access-Control-Expose-Headers（真 Security）")
    void suggestLimited_crossOrigin429_exposesRetryAfter() throws Exception {
        String ip = "198.51.100.90";
        String origin = "http://localhost:3000";

        for (int i = 1; i <= 2; i++) {
            mockMvc.perform(get("/api/v1/search/suggest").param("q", "ab").header("Origin", origin).with(fromIp(ip)))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/search/suggest").param("q", "ab").header("Origin", origin).with(fromIp(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(header().string("Access-Control-Allow-Origin", origin))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        org.hamcrest.Matchers.containsString("Retry-After")))
                .andExpect(jsonPath("$.code").value("A0009"));

        // 與 search 規則互不影響（各自計數）
        assertThat(redis.keys("ratelimit:search:ip:" + ip)).isEmpty();
        assertThat(redis.keys("ratelimit:search-suggest:ip:" + ip)).hasSize(1);
    }
}
