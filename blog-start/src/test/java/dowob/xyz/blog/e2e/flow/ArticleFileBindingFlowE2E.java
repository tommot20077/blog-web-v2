package dowob.xyz.blog.e2e.flow;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 「檔案 → 文章」綁定改走 MQ 的全鏈路 E2E（W5A／Q12）。
 *
 * <p>article 在儲存／還原後發出 {@code ArticleContentChangedEvent}，file 模組經<b>真實 RabbitMQ</b> 消費後依文章最新內文
 * 回填 {@code file_metadata.article_uuid}。article 模組不再同步呼叫 file。本測試驗證：儲存後綁定被回填、
 * 更新後換圖（完整替換）、版本還原後綁定回到還原內容，以及發布後匿名讀者可經 {@code /content} 取得圖片位元組
 * （後端串流，public 快取）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("檔案綁定改走 MQ 全鏈路 E2E (W5A／Q12)")
class ArticleFileBindingFlowE2E extends AbstractE2ETest {

    private static final String PASSWORD = "Password123!";

    @Autowired private AuthHelper authHelper;
    @Autowired private DatabaseCleaner databaseCleaner;
    @Autowired private JdbcTemplate jdbcTemplate;

    /** 清理資料庫，避免影響其他 E2E。 */
    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    private String uploadImage(String token) throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.jpg", "image/jpeg",
                getClass().getClassLoader().getResourceAsStream("test-files/test.jpg"));
        MvcResult result = mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(file)
                        .param("usageType", "ARTICLE_CONTENT")
                        .with(bearerToken(token)))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data").get("id").asText();
    }

    private static String contentWith(String fileId) {
        return "# 文章\n![圖](/api/v1/files/" + fileId + "/content)\n";
    }

    private String createArticle(String token, String content) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/articles")
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(DataBuilder.article("binding-flow", content))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(created.getResponse().getContentAsString()).get("data").get("uuid").asText();
    }

    private String boundArticleOf(String fileId) {
        return jdbcTemplate.queryForObject(
                "SELECT article_uuid::text FROM file_metadata WHERE id = ?::uuid", String.class, fileId);
    }

    private void awaitBound(String fileId, String articleUuid) {
        await().atMost(30, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(boundArticleOf(fileId)).isEqualTo(articleUuid));
    }

    private void awaitUnbound(String fileId) {
        await().atMost(30, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(boundArticleOf(fileId)).isNull());
    }

    @Test
    @DisplayName("儲存文章 → file 模組經 MQ 回填綁定；更新換圖 → 新圖綁定、舊圖解除")
    void saveAndUpdate_backfillsAndReplacesBindingViaMq() throws Exception {
        String token = authHelper.createUserWithRole(
                "binding-flow@test.com", PASSWORD, "bindingflow", "BindingFlow", Role.AUTHOR);
        String imageA = uploadImage(token);
        String imageB = uploadImage(token);
        assertThat(boundArticleOf(imageA)).as("上傳時尚未綁定任何文章").isNull();

        String articleUuid = createArticle(token, contentWith(imageA));

        awaitBound(imageA, articleUuid);
        assertThat(boundArticleOf(imageB)).as("未被內文引用的圖不綁定").isNull();

        mockMvc.perform(put("/api/v1/articles/" + articleUuid)
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", contentWith(imageB)))))
                .andExpect(status().isOk());

        awaitBound(imageB, articleUuid);
        awaitUnbound(imageA);
    }

    @Test
    @DisplayName("版本還原 → RESTORED 事件經 MQ 回填：還原內容引用的圖重新綁定、目前內容的圖解除")
    void restoreVersion_rebindsToRestoredContentViaMq() throws Exception {
        String token = authHelper.createUserWithRole(
                "binding-restore@test.com", PASSWORD, "bindingrestore", "BindingRestore", Role.AUTHOR);
        String imageA = uploadImage(token);
        String imageB = uploadImage(token);
        String articleUuid = createArticle(token, contentWith(imageA));
        awaitBound(imageA, articleUuid);

        MvcResult manual = mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/versions/manual")
                        .with(bearerToken(token)))
                .andExpect(status().isOk()).andReturn();
        String versionUuid = objectMapper.readTree(manual.getResponse().getContentAsString())
                .get("data").get("uuid").asText();

        mockMvc.perform(put("/api/v1/articles/" + articleUuid)
                        .with(bearerToken(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("content", contentWith(imageB)))))
                .andExpect(status().isOk());
        awaitBound(imageB, articleUuid);
        awaitUnbound(imageA);

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/versions/" + versionUuid + "/restore")
                        .with(bearerToken(token)))
                .andExpect(status().isOk());

        awaitBound(imageA, articleUuid);
        awaitUnbound(imageB);
    }

    @Test
    @DisplayName("發布後綁定回填完成 → 匿名讀者經 /content 取得圖片位元組（後端串流，public 快取）；草稿期間匿名 403")
    void published_anonymousReaderStreamsBoundImage() throws Exception {
        String token = authHelper.createUserWithRole(
                "binding-public@test.com", PASSWORD, "bindingpublic", "BindingPublic", Role.AUTHOR);
        String image = uploadImage(token);
        byte[] expected = getClass().getClassLoader().getResourceAsStream("test-files/test.jpg").readAllBytes();
        String articleUuid = createArticle(token, contentWith(image));
        awaitBound(image, articleUuid);

        mockMvc.perform(get("/api/v1/files/" + image + "/content")).andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/articles/" + articleUuid + "/publish").with(bearerToken(token)))
                .andExpect(status().isOk());

        MvcResult streamed = mockMvc.perform(get("/api/v1/files/" + image + "/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", "public, max-age=86400"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        assertThat(streamed.getResponse().getContentAsByteArray()).isEqualTo(expected);
    }

    @Test
    @DisplayName("刪除文章 → ArticleDeletedEvent 經 MQ 到 file 模組：只被該文章引用的圖，metadata 消失、/content 查無（DATA-07）；未綁定的圖不受影響")
    void deleteArticle_cleansBoundFilesViaMq() throws Exception {
        String token = authHelper.createUserWithRole(
                "binding-delete@test.com", PASSWORD, "bindingdelete", "BindingDelete", Role.AUTHOR);
        String image = uploadImage(token);
        String unrelated = uploadImage(token);
        String articleUuid = createArticle(token, contentWith(image));
        awaitBound(image, articleUuid);

        mockMvc.perform(delete("/api/v1/articles/" + articleUuid).with(bearerToken(token)))
                .andExpect(status().isOk());

        await().atMost(30, TimeUnit.SECONDS).pollInterval(300, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> assertThat(fileRows(image)).as("綁定的圖應被清除").isZero());
        mockMvc.perform(get("/api/v1/files/" + image + "/content").with(bearerToken(token)))
                .andExpect(status().is4xxClientError());
        assertThat(fileRows(unrelated)).as("從未綁定的圖不在清理範圍").isEqualTo(1);
    }

    private int fileRows(String fileId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM file_metadata WHERE id = ?::uuid", Integer.class, fileId);
    }
}
