package dowob.xyz.blog.module.article.controller;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.module.article.model.dto.request.CreateArticleRequest;
import dowob.xyz.blog.module.article.model.dto.request.UpdateArticleRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文章內文上限（SEC-25 / 決策 A5：200,000 字元）的 HTTP 層整合測試
 *
 * <p>驗證邊界：200,000 字元成功、200,001 字元回 400，且超限時 DB 內容不變。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("文章內文上限 200,000 字元（IT）")
class ArticleContentLimitIT extends ArticleWebITBase {

    private static final int LIMIT = 200_000;

    @Test
    @DisplayName("POST 建立：內文剛好 200,000 字元 → 200")
    void create_atLimit_succeeds() throws Exception {
        stubUserFacade();
        mockMvc.perform(post("/api/v1/articles")
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("a".repeat(LIMIT)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.uuid").exists());
    }

    @Test
    @DisplayName("POST 建立：內文 200,001 字元 → 400，且沒有寫入任何文章")
    void create_overLimit_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/articles")
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("a".repeat(LIMIT + 1)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
        assertThat(articleRepository.count()).isZero();
    }

    @Test
    @DisplayName("PUT 更新：內文剛好 200,000 字元 → 200；200,001 字元 → 400 且 DB 內容不變")
    void update_boundary() throws Exception {
        stubUserFacade();
        String createResponse = mockMvc.perform(post("/api/v1/articles")
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest("原始內容"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String uuid = objectMapper.readTree(createResponse).path("data").path("uuid").asText();

        UpdateArticleRequest over = new UpdateArticleRequest();
        over.setContent("b".repeat(LIMIT + 1));
        mockMvc.perform(put("/api/v1/articles/" + uuid)
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(over)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("400"));
        assertThat(articleRepository.findByUuid(UUID.fromString(uuid)).orElseThrow().getContent())
                .isEqualTo("原始內容");

        UpdateArticleRequest exact = new UpdateArticleRequest();
        exact.setContent("c".repeat(LIMIT));
        mockMvc.perform(put("/api/v1/articles/" + uuid)
                        .with(asUser(AUTHOR_ID, Role.AUTHOR))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(exact)))
                .andExpect(status().isOk());
        assertThat(articleRepository.findByUuid(UUID.fromString(uuid)).orElseThrow().getContent())
                .hasSize(LIMIT);
    }

    private CreateArticleRequest createRequest(String content) {
        CreateArticleRequest request = new CreateArticleRequest();
        request.setTitle("上限測試");
        request.setContent(content);
        return request;
    }
}
