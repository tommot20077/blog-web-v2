package dowob.xyz.blog.e2e.author;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.SearchIndexCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static dowob.xyz.blog.e2e.support.E2EAssertions.apiSuccess;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 公開作者功能 E2E 測試（真實 PostgreSQL／Redis／RabbitMQ／Elasticsearch，全程匿名呼叫新端點）
 *
 * <p>驗證：{@code GET /api/v1/users/{uuid}/profile} 與 {@code GET /api/v1/authors} 不帶 token 回 200；
 * profile 只計 PUBLISHED、不含 email／username／role；不存在、未驗證、已刪除回相同 404；
 * authors 只列 ACTIVE 且有已發布文章的作者、{@code _} 為字面字元、超過 50 字回 400；
 * 搜尋與推薦回應含 {@code authorUuid}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("公開作者 E2E 測試")
class PublicAuthorE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private SearchIndexCleaner searchIndexCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
        /* 讀寫改走 alias 後不可再 delete＋createWithMapping（會拆掉 alias），改為經 alias 清空文件 */
        searchIndexCleaner.clearArticles();
    }

    // ========== Helper Methods ==========

    private String createAuthor(String key, String nickname) throws Exception {
        return authHelper.createUserWithRole(key + "@test.com", "Password1!", key, nickname, Role.AUTHOR);
    }

    private UUID uuidOfUser(String email) {
        return jdbcTemplate.queryForObject("SELECT uuid FROM users WHERE email = ?", UUID.class, email);
    }

    private String createArticle(String token, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/articles")
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("uuid").asText();
    }

    private String publishArticle(String token, String title, List<String> tags) throws Exception {
        String uuid = createArticle(token, DataBuilder.articleWithTags(title, "內容-" + title, tags));
        mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish").with(bearerToken(token)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());
        return uuid;
    }

    private Set<String> keysOf(JsonNode node) {
        return new TreeSet<>(StreamSupport.stream(
                java.util.Spliterators.spliteratorUnknownSize(node.fieldNames(), 0), false).toList());
    }

    private JsonNode getJson(String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url)).andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    // ========== profile ==========

    @Test
    @DisplayName("匿名取得 ACTIVE 作者的公開資料：只計 PUBLISHED、欄位為白名單、不含 email／username／role")
    void getPublicProfile_anonymous_returnsOnlyPublicFieldsAndPublishedStats() throws Exception {
        String token = createAuthor("pa-profile", "ProfileAuthor");
        UUID authorUuid = uuidOfUser("pa-profile@test.com");
        publishArticle(token, "公開文章一", List.of("Java"));
        publishArticle(token, "公開文章二", List.of("Java"));
        createArticle(token, DataBuilder.article("草稿不計", "草稿內容"));
        jdbcTemplate.update("UPDATE users SET bio = ?, location = ?, website = ?, social_links = ? WHERE uuid = ?",
                "我的簡介", "Taipei", "https://profile.example.com", "{\"github\":\"pa-profile\"}", authorUuid);
        jdbcTemplate.update("UPDATE articles SET view_count = 10, like_count = 3 WHERE author_uuid = ? AND status = 'PUBLISHED'",
                authorUuid);
        jdbcTemplate.update("UPDATE articles SET view_count = 999, like_count = 999 WHERE author_uuid = ? AND status <> 'PUBLISHED'",
                authorUuid);

        MvcResult result = mockMvc.perform(get("/api/v1/users/{uuid}/profile", authorUuid))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.uuid").value(authorUuid.toString()))
                .andExpect(jsonPath("$.data.nickname").value("ProfileAuthor"))
                .andExpect(jsonPath("$.data.bio").value("我的簡介"))
                .andExpect(jsonPath("$.data.location").value("Taipei"))
                .andExpect(jsonPath("$.data.website").value("https://profile.example.com"))
                .andExpect(jsonPath("$.data.socialLinks.github").value("pa-profile"))
                .andExpect(jsonPath("$.data.stats.publishedArticleCount").value(2))
                .andExpect(jsonPath("$.data.stats.totalViewCount").value(20))
                .andExpect(jsonPath("$.data.stats.totalLikeCount").value(6))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(body).get("data");
        assertThat(keysOf(data)).containsExactlyInAnyOrder("uuid", "nickname", "avatarUrl", "bio", "location",
                "website", "socialLinks", "joinedAt", "stats");
        assertThat(keysOf(data.get("socialLinks"))).containsExactlyInAnyOrder("github", "twitter", "linkedin");
        assertThat(body).doesNotContain("pa-profile@test.com").doesNotContain("\"role\"").doesNotContain("AUTHOR")
                .doesNotContain("\"email\"").doesNotContain("\"username\"");
    }

    @Test
    @DisplayName("沒有任何文章的 ACTIVE 使用者 → 200，統計全為 0")
    void getPublicProfile_activeUserWithoutArticles_returnsZeroStats() throws Exception {
        authHelper.registerAndLogin("pa-empty@test.com", "Password1!", "paempty", "EmptyAuthor");

        mockMvc.perform(get("/api/v1/users/{uuid}/profile", uuidOfUser("pa-empty@test.com")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stats.publishedArticleCount").value(0))
                .andExpect(jsonPath("$.data.stats.totalViewCount").value(0))
                .andExpect(jsonPath("$.data.stats.totalLikeCount").value(0));
    }

    @Test
    @DisplayName("不存在、未驗證信箱、已刪除、停權 → 完全相同的 404 A0101 回應（code／message／data 一致）")
    void getPublicProfile_missingPendingDeletedBanned_returnIdentical404() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                DataBuilder.register("pa-pending@test.com", "Password1!", "papending", "PendingUser"))))
                .andExpect(status().isOk());
        authHelper.registerAndLogin("pa-deleted@test.com", "Password1!", "padeleted", "DeletedUser");
        authHelper.registerAndLogin("pa-banned@test.com", "Password1!", "pabanned", "BannedUser");
        jdbcTemplate.update("UPDATE users SET status = 'DELETED' WHERE email = ?", "pa-deleted@test.com");
        jdbcTemplate.update("UPDATE users SET status = 'BANNED' WHERE email = ?", "pa-banned@test.com");

        String expected = null;
        for (UUID uuid : List.of(UUID.randomUUID(), uuidOfUser("pa-pending@test.com"),
                uuidOfUser("pa-deleted@test.com"), uuidOfUser("pa-banned@test.com"))) {
            MvcResult result = mockMvc.perform(get("/api/v1/users/{uuid}/profile", uuid))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("A0101"))
                    .andReturn();
            /* 回應含 timestamp，逐次不同；比較其餘欄位（code／message／data）即可 */
            JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
            String comparable = node.get("code") + "|" + node.get("message") + "|" + node.get("data");
            expected = expected == null ? comparable : expected;
            assertThat(comparable).isEqualTo(expected);
        }
    }

    // ========== authors ==========

    @Test
    @DisplayName("匿名搜尋作者：只列 ACTIVE 且有已發布文章者；草稿作者、已刪除作者不出現；依篇數排序")
    void searchAuthors_anonymous_listsOnlyActiveAuthorsWithPublishedArticles() throws Exception {
        String busy = createAuthor("pa-busy", "BusyWriter");
        String casual = createAuthor("pa-casual", "CasualWriter");
        String draftOnly = createAuthor("pa-draft", "DraftWriter");
        String gone = createAuthor("pa-gone", "GoneWriter");
        publishArticle(busy, "Busy 一", List.of("Java"));
        publishArticle(busy, "Busy 二", List.of("Java"));
        publishArticle(casual, "Casual 一", List.of("Java"));
        createArticle(draftOnly, DataBuilder.article("只有草稿", "草稿"));
        publishArticle(gone, "Gone 一", List.of("Java"));
        jdbcTemplate.update("UPDATE users SET status = 'DELETED' WHERE email = ?", "pa-gone@test.com");

        mockMvc.perform(get("/api/v1/authors"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].uuid").value(uuidOfUser("pa-busy@test.com").toString()))
                .andExpect(jsonPath("$.data[0].nickname").value("BusyWriter"))
                .andExpect(jsonPath("$.data[0].publishedArticleCount").value(2))
                .andExpect(jsonPath("$.data[1].nickname").value("CasualWriter"))
                .andExpect(jsonPath("$.data[1].publishedArticleCount").value(1));
    }

    @Test
    @DisplayName("作者搜尋 keyword：不分大小寫、任意位置；_ 與 % 為字面字元；超過 50 字回 400")
    void searchAuthors_keywordRules() throws Exception {
        String plain = createAuthor("pa-kw-plain", "Plainwriter");
        String underscore = createAuthor("pa-kw-under", "Under_score");
        publishArticle(plain, "Plain 一", List.of("Java"));
        publishArticle(underscore, "Under 一", List.of("Java"));

        mockMvc.perform(get("/api/v1/authors").param("keyword", "  LAINWR  "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].nickname").value("Plainwriter"));
        mockMvc.perform(get("/api/v1/authors").param("keyword", "_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].nickname").value("Under_score"));
        mockMvc.perform(get("/api/v1/authors").param("keyword", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/api/v1/authors").param("keyword", "x".repeat(51)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/authors").param("keyword", "x".repeat(50)))
                .andExpect(status().isOk());
    }

    // ========== search / recommend ==========

    @Test
    @DisplayName("搜尋結果含 authorUuid（直接取自 ES 文件）")
    void search_resultsContainAuthorUuid() throws Exception {
        String token = createAuthor("pa-search", "SearchAuthorX");
        UUID authorUuid = uuidOfUser("pa-search@test.com");
        publishArticle(token, "Elasticsearch authorUuid 驗證", List.of("Java"));

        await().atMost(20, TimeUnit.SECONDS).pollInterval(1, TimeUnit.SECONDS).untilAsserted(() -> {
            JsonNode records = getJson("/api/v1/search?q=Elasticsearch").get("data").get("records");
            assertThat(records.size()).isGreaterThanOrEqualTo(1);
            assertThat(records.get(0).get("authorUuid").asText()).isEqualTo(authorUuid.toString());
            assertThat(records.get(0).get("authorNickname").asText()).isEqualTo("SearchAuthorX");
        });
    }

    @Test
    @DisplayName("相關文章推薦結果含 authorUuid")
    void recommendRelated_resultsContainAuthorUuid() throws Exception {
        String token = createAuthor("pa-rec", "RecommendAuthorX");
        UUID authorUuid = uuidOfUser("pa-rec@test.com");
        String first = publishArticle(token, "推薦測試一", List.of("Recommended"));
        publishArticle(token, "推薦測試二", List.of("Recommended"));

        JsonNode data = getJson("/api/v1/recommend/related/" + first).get("data");

        assertThat(data.size()).isGreaterThanOrEqualTo(1);
        data.forEach(item -> assertThat(item.get("authorUuid").asText()).isEqualTo(authorUuid.toString()));
    }
}
