package dowob.xyz.blog.e2e.flow;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 刪除系列的連動全鏈路 E2E 測試（M6-b）
 *
 * <p>
 * V32 起 {@code articles.series_uuid} 沒有資料庫 FK，「刪除 series → 文章脫離系列」由 series 發出
 * {@code SeriesDeletedEvent}、article 的 consumer 經<b>真實 RabbitMQ</b> 收到後清空歸屬。
 * 本測試驗證這條非同步鏈路端到端成立：文章留存、{@code series_uuid} 與 {@code series_position} 清空、
 * {@code version} 遞增（過期的整筆 save 會撞樂觀鎖，不會把 ghost 歸屬寫回）、{@code updated_at} 不動。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("刪除系列連動全鏈路 E2E")
class SeriesDeleteFlowE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    @DisplayName("刪除系列 → 經真實 broker 的 consumer 清空成員文章歸屬：文章留存、version 遞增、updated_at 不動")
    void deleteSeries_realBroker_clearsArticleMembership() throws Exception {
        String admin = authHelper.createUserWithRole(
                "series-delete-admin@test.com", "Admin123!", "seriesdeleteadmin", "Series Delete Admin", Role.ADMIN);

        String articleUuid = createPublishedArticle(admin);
        String seriesUuid = createSeries(admin, "series-delete-flow");
        mockMvc.perform(put("/api/v1/series/" + seriesUuid + "/articles/" + articleUuid)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"position\":1}")
                        .with(AuthHelper.bearerToken(admin)))
                .andExpect(status().isOk());
        assertThat(seriesUuidOf(articleUuid)).isEqualTo(seriesUuid);
        Integer versionBefore = jdbcTemplate.queryForObject(
                "SELECT version FROM articles WHERE uuid = ?::uuid", Integer.class, articleUuid);
        Timestamp updatedAtBefore = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM articles WHERE uuid = ?::uuid", Timestamp.class, articleUuid);

        mockMvc.perform(delete("/api/v1/series/" + seriesUuid).with(AuthHelper.bearerToken(admin)))
                .andExpect(status().isOk());

        await().atMost(20, SECONDS).pollInterval(500, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(seriesUuidOf(articleUuid)).isNull());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT series_position FROM articles WHERE uuid = ?::uuid", Integer.class, articleUuid)).isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM articles WHERE uuid = ?::uuid", Integer.class, articleUuid))
                .as("清空歸屬遞增 version，讓過期的整筆 save 撞樂觀鎖").isEqualTo(versionBefore + 1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT updated_at FROM articles WHERE uuid = ?::uuid", Timestamp.class, articleUuid))
                .as("updated_at 與改造前相同，不動").isEqualTo(updatedAtBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM articles", Integer.class))
                .as("刪除語意不變：文章留存").isEqualTo(1);
    }

    /** 建立並發布一篇文章，回傳其 uuid。 */
    private String createPublishedArticle(String token) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Series delete flow article");
        body.put("content", "hello world content");
        String response = mockMvc.perform(post("/api/v1/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(AuthHelper.bearerToken(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String articleUuid = objectMapper.readTree(response).get("data").get("uuid").asText();
        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/submit").with(AuthHelper.bearerToken(token)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/publish").with(AuthHelper.bearerToken(token)))
                .andExpect(status().isOk());
        return articleUuid;
    }

    /** 建立系列，回傳其 uuid。 */
    private String createSeries(String token, String slug) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Series delete flow");
        body.put("slug", slug);
        String response = mockMvc.perform(post("/api/v1/series")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body))
                        .with(AuthHelper.bearerToken(token)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(response).get("data");
        return data.get("uuid").asText();
    }

    /** 讀取文章目前的 series_uuid（文字形式；NULL 時為 null）。 */
    private String seriesUuidOf(String articleUuid) {
        return jdbcTemplate.queryForObject(
                "SELECT series_uuid::text FROM articles WHERE uuid = ?::uuid", String.class, articleUuid);
    }
}
