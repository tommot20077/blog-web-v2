package dowob.xyz.blog.e2e.flow;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import dowob.xyz.blog.module.search.document.ArticleDocument;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 刪除帳號（匿名化）連動全鏈路 E2E 測試（DATA-02）。
 *
 * <p>user 在匿名化交易 commit 後發出 {@code UserDeletedEvent}，article／reading／search 等模組的 consumer
 * 經<b>真實 RabbitMQ</b> 收到後各自清理。本測試驗證端到端成立：users 列匿名化、草稿被刪而已發布文章保留、
 * 收藏被清除、公開讀取路徑（文章詳情）與搜尋結果（ES 內冗餘的作者暱稱）都顯示匿名暱稱、搜尋歷史被清除。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("刪除帳號連動全鏈路 E2E")
class UserDeleteFlowE2E extends AbstractE2ETest {

    private static final String PASSWORD = "Password1!";
    private static final String ORIGINAL_NICKNAME = "DeleteFlowAuthor";
    private static final String KEYWORD = "userdeleteflowkeyword";

    @Autowired private AuthHelper authHelper;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ElasticsearchOperations esOps;
    @Autowired private StringRedisTemplate redisTemplate;

    /** 清理資料庫與 ES 索引，避免影響其他 E2E。 */
    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
        var indexOps = esOps.indexOps(ArticleDocument.class);
        if (indexOps.exists()) {
            indexOps.delete();
            indexOps.createWithMapping();
        }
    }

    @Test
    @DisplayName("刪除帳號 → 經真實 broker 連動：草稿刪除、已發布保留且作者匿名、收藏與搜尋歷史清除、ES 作者暱稱匿名化")
    void deleteAccount_realBroker_cascadesAcrossModules() throws Exception {
        String token = authHelper.createUserWithRole(
                "delete-flow@test.com", PASSWORD, "deleteflowauthor", ORIGINAL_NICKNAME, Role.AUTHOR);
        String userUuid = jdbcTemplate.queryForObject(
                "SELECT uuid::text FROM users WHERE email = 'delete-flow@test.com'", String.class);

        String publishedUuid = createArticle(token, "Published " + KEYWORD, "body " + KEYWORD, true);
        String draftUuid = createArticle(token, "Draft " + KEYWORD, "draft body", false);
        mockMvc.perform(post("/api/v1/articles/" + publishedUuid + "/bookmark").with(bearerToken(token)))
                .andExpect(status().isOk());
        /* 已發布文章先在 ES 搜得到、作者暱稱為原值；同時產生一筆搜尋歷史 */
        await().atMost(20, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(searchAuthorNickname(token)).isEqualTo(ORIGINAL_NICKNAME));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM user_bookmarks WHERE user_uuid = ?::uuid", Integer.class, userUuid)).isEqualTo(1);
        String historyKey = RedisKeyConstant.getSearchHistoryKey(java.util.UUID.fromString(userUuid));
        assertThat(redisTemplate.hasKey(historyKey)).as("帶 token 的搜尋會寫入搜尋歷史").isTrue();

        mockMvc.perform(delete("/api/v1/users/me")
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isOk());

        /* users 列：同步匿名化（不需等 broker） */
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, nickname, email, username, avatar_url, bio FROM users WHERE uuid = ?::uuid", userUuid);
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat((String) row.get("nickname")).startsWith("已刪除使用者#");
        assertThat((String) row.get("email")).endsWith("@invalid.invalid");
        String anonymousNickname = (String) row.get("nickname");

        /* 非同步連動：article 刪草稿、reading 刪收藏 */
        await().atMost(30, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM articles WHERE uuid = ?::uuid", Integer.class, draftUuid))
                    .as("草稿隨作者刪帳移除").isZero();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM user_bookmarks WHERE user_uuid = ?::uuid", Integer.class, userUuid))
                    .as("收藏被清除").isZero();
            assertThat(redisTemplate.hasKey(historyKey)).as("搜尋歷史被清除").isFalse();
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM articles WHERE uuid = ?::uuid", Integer.class, publishedUuid))
                .as("已發布文章保留").isEqualTo(1);

        /* 公開讀取路徑：文章詳情的作者暱稱自動匿名（UserFacade 讀匿名化後的 users 列） */
        MvcResult detail = mockMvc.perform(get("/api/v1/articles/" + publishedUuid))
                .andExpect(status().isOk()).andReturn();
        assertThat(objectMapper.readTree(detail.getResponse().getContentAsString())
                .get("data").get("authorNickname").asText()).isEqualTo(anonymousNickname);

        /* search：ES 內冗餘的作者暱稱被改寫，搜尋結果不再洩漏原暱稱 */
        await().atMost(30, TimeUnit.SECONDS).pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(searchAuthorNickname(null)).isEqualTo(anonymousNickname));
    }

    /**
     * 建立文章；需要時直接發布（AUTHOR 可自助發布）。
     *
     * @param token   作者 token
     * @param title   標題
     * @param content 內容
     * @param publish 是否發布
     * @return 文章 uuid
     */
    private String createArticle(String token, String title, String content, boolean publish) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/articles")
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.article(title, content))))
                .andExpect(status().isOk()).andReturn();
        String uuid = objectMapper.readTree(created.getResponse().getContentAsString()).get("data").get("uuid").asText();
        if (publish) {
            mockMvc.perform(post("/api/v1/articles/" + uuid + "/publish").with(bearerToken(token)))
                    .andExpect(status().isOk());
        }
        return uuid;
    }

    /**
     * 以關鍵字搜尋並回傳第一筆結果的作者暱稱；沒有結果時為 null。
     *
     * @param token 搜尋者 token（帶 token 會寫入搜尋歷史）；可為 null
     * @return 第一筆結果的 authorNickname
     */
    private String searchAuthorNickname(String token) throws Exception {
        var request = get("/api/v1/search").param("q", KEYWORD);
        MvcResult result = mockMvc.perform(token == null ? request : request.with(bearerToken(token)))
                .andExpect(status().isOk()).andReturn();
        JsonNode records = objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("records");
        return records != null && records.size() > 0 ? records.get(0).get("authorNickname").asText() : null;
    }
}
