package dowob.xyz.blog.module.article.controller;

import com.fasterxml.jackson.databind.JsonNode;
import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文章內容更新的樂觀鎖版本（RACE-05 / 決策 B2）整合測試，使用真 PostgreSQL。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("文章樂觀鎖 version（IT）")
class ArticleOptimisticVersionIT extends ArticleWebITBase {

    @Test
    @DisplayName("GET /edit 與建立回應帶 version；存檔成功的回應帶遞增後的新 version，可連續存檔")
    void responsesCarryVersion_andChainedSavesWork() throws Exception {
        stubUserFacade();
        String uuid = createArticle("原始內容");

        long loaded = readEditVersion(uuid);

        JsonNode first = putArticle(uuid, Map.of("content", "第一次存檔", "version", loaded), 200);
        long afterFirst = first.path("data").path("version").asLong();
        assertThat(afterFirst).isEqualTo(loaded + 1);

        /* 前端拿回應的新 version 繼續存第二次，不必重新載入 */
        JsonNode second = putArticle(uuid, Map.of("content", "第二次存檔", "version", afterFirst), 200);
        assertThat(second.path("data").path("version").asLong()).isEqualTo(afterFirst + 1);
        assertThat(readEditVersion(uuid)).isEqualTo(afterFirst + 1);
    }

    @Test
    @DisplayName("帶舊 version → 409 A0208，DB 內容不變")
    void staleVersion_returns409_andDbUnchanged() throws Exception {
        stubUserFacade();
        String uuid = createArticle("原始內容");
        long loaded = readEditVersion(uuid);
        putArticle(uuid, Map.of("content", "別處已存的內容", "version", loaded), 200);

        JsonNode rejected = putArticle(uuid, Map.of("content", "過期的覆蓋", "title", "過期標題", "version", loaded), 409);

        assertThat(rejected.path("code").asText()).isEqualTo("A0208");
        var stored = articleRepository.findByUuid(UUID.fromString(uuid)).orElseThrow();
        assertThat(stored.getContent()).isEqualTo("別處已存的內容");
        assertThat(stored.getTitle()).isEqualTo("版本測試");
        assertThat(stored.getVersion()).isEqualTo(loaded + 1);
    }

    @Test
    @DisplayName("不帶 version → 行為與現行相同（後蓋前仍成功）")
    void absentVersion_keepsLegacyBehaviour() throws Exception {
        stubUserFacade();
        String uuid = createArticle("原始內容");

        putArticle(uuid, Map.of("content", "A"), 200);
        JsonNode second = putArticle(uuid, Map.of("content", "B"), 200);

        assertThat(second.path("data").path("content").asText()).isEqualTo("B");
        assertThat(articleRepository.findByUuid(UUID.fromString(uuid)).orElseThrow().getContent()).isEqualTo("B");
    }

    @Test
    @DisplayName("兩個請求帶同一個 version 先後存檔 → 第一個 200，第二個 409")
    void twoRequestsWithSameVersion_secondGets409() throws Exception {
        stubUserFacade();
        String uuid = createArticle("原始內容");
        long loaded = readEditVersion(uuid);

        putArticle(uuid, Map.of("content", "先到的", "version", loaded), 200);
        JsonNode second = putArticle(uuid, Map.of("content", "後到的", "version", loaded), 409);

        assertThat(second.path("code").asText()).isEqualTo("A0208");
        assertThat(articleRepository.findByUuid(UUID.fromString(uuid)).orElseThrow().getContent())
                .isEqualTo("先到的");
    }

    @Test
    @DisplayName("狀態轉換端點（submit）回應也帶 version，且與 DB 一致")
    void transitionResponse_carriesVersion() throws Exception {
        stubUserFacade();
        String uuid = createArticle("原始內容");

        String body = mockMvc.perform(post("/api/v1/articles/" + uuid + "/submit")
                        .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").isNumber())
                .andReturn().getResponse().getContentAsString();

        long reported = objectMapper.readTree(body).path("data").path("version").asLong();
        assertThat(reported).isEqualTo(articleRepository.findByUuid(UUID.fromString(uuid)).orElseThrow().getVersion());
    }

    private String createArticle(String content) throws Exception {
        CreateArticleRequest request = new CreateArticleRequest();
        request.setTitle("版本測試");
        request.setContent(content);
        String body = mockMvc.perform(post("/api/v1/articles")
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").isNumber())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("uuid").asText();
    }

    private long readEditVersion(String uuid) throws Exception {
        String body = mockMvc.perform(get("/api/v1/articles/" + uuid + "/edit")
                        .with(asUser(AUTHOR_ID, Role.AUTHOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").isNumber())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("version").asLong();
    }

    private JsonNode putArticle(String uuid, Map<String, Object> payload, int expectedStatus) throws Exception {
        String body = mockMvc.perform(put("/api/v1/articles/" + uuid)
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(payload)))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }
}
