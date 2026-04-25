package dowob.xyz.blog.e2e.tag;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DataBuilder;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.E2EAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tag 模組 E2E 測試
 *
 * <p>涵蓋標籤建議、熱門標籤、標籤詳情、追蹤/取消追蹤、管理員更新與刪除。</p>
 */
@DisplayName("Tag E2E 測試")
class TagE2E extends AbstractE2ETest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    /**
     * 建立 AUTHOR 使用者，建立帶標籤的文章並發布，等待 RabbitMQ consumer 建立標籤。
     *
     * @param authorToken AUTHOR 的 JWT token
     * @param tagNames    標籤名稱列表
     */
    private void createArticleWithTagsAndPublish(String authorToken, List<String> tagNames) throws Exception {
        // 建立帶標籤的文章
        String createBody = objectMapper.writeValueAsString(
                DataBuilder.articleWithTags("Tag Test Article", "Content for tag test", tagNames));

        String createResponse = mockMvc.perform(post("/api/v1/articles")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody)
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String articleUuid = objectMapper.readTree(createResponse).get("data").get("uuid").asText();

        // 發布文章（DRAFT → PUBLISHED）— 觸發 ArticlePublishedEvent → TagUsageConsumer
        mockMvc.perform(post("/api/v1/articles/{uuid}/publish", articleUuid)
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // 等待 RabbitMQ consumer 處理完成，標籤寫入 DB
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tags", Integer.class);
            assertThat(count).isGreaterThanOrEqualTo(tagNames.size());
        });
    }

    @Test
    @DisplayName("標籤自動補全 — 根據前綴回傳匹配結果")
    void suggestTags_withPrefix_returnsMatches() throws Exception {
        // Arrange — 透過文章建立標籤
        String authorToken = authHelper.createUserWithRole(
                "tag-suggest@test.com", "password123", "tagsuggest", "TagSuggest", Role.AUTHOR);
        createArticleWithTagsAndPublish(authorToken, List.of("Spring Boot", "Spring Cloud"));

        // Act & Assert
        mockMvc.perform(get("/api/v1/tags/suggest")
                        .param("q", "spring")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(E2EAssertions.hasData())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("取得熱門標籤列表 — 成功回傳")
    void getHotTags_success() throws Exception {
        // Arrange — 透過文章建立標籤
        String authorToken = authHelper.createUserWithRole(
                "tag-hot@test.com", "password123", "taghot", "TagHot", Role.AUTHOR);
        createArticleWithTagsAndPublish(authorToken, List.of("Java", "Kotlin"));

        // Act & Assert
        mockMvc.perform(get("/api/v1/tags/hot")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(E2EAssertions.hasData());
    }

    @Test
    @DisplayName("依 Slug 取得標籤詳情 — 成功回傳")
    void getTagBySlug_success() throws Exception {
        // Arrange — 透過文章建立標籤
        String authorToken = authHelper.createUserWithRole(
                "tag-slug@test.com", "password123", "tagslug", "TagSlug", Role.AUTHOR);
        createArticleWithTagsAndPublish(authorToken, List.of("Docker"));

        // 從 DB 取得標籤 slug
        String slug = jdbcTemplate.queryForObject(
                "SELECT slug FROM tags WHERE name = ?", String.class, "docker");

        // Act & Assert
        mockMvc.perform(get("/api/v1/tags/{slug}", slug))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(E2EAssertions.hasData())
                .andExpect(jsonPath("$.data.slug").value(slug));
    }

    @Test
    @DisplayName("使用者追蹤標籤 — 成功")
    void followTag_asUser_success() throws Exception {
        // Arrange — 建立標籤
        String authorToken = authHelper.createUserWithRole(
                "tag-follow-author@test.com", "password123", "tagfollowauthor", "TagFollowAuthor", Role.AUTHOR);
        createArticleWithTagsAndPublish(authorToken, List.of("React"));

        // 建立一般使用者
        String userToken = authHelper.createUserWithRole(
                "tag-follow-user@test.com", "password123", "tagfollowuser", "TagFollowUser", Role.USER);

        // 從 DB 取得標籤 ID
        UUID tagId = jdbcTemplate.queryForObject(
                "SELECT id FROM tags WHERE name = ?", UUID.class, "react");

        // Act & Assert
        mockMvc.perform(post("/api/v1/tags/{id}/follow", tagId)
                        .with(AuthHelper.bearerToken(userToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());
    }

    @Test
    @DisplayName("使用者取消追蹤標籤 — 追蹤後取消成功")
    void unfollowTag_asUser_success() throws Exception {
        // Arrange — 建立標籤
        String authorToken = authHelper.createUserWithRole(
                "tag-unfollow-author@test.com", "password123", "tagunfollowauthor", "TagUnfollowAuthor", Role.AUTHOR);
        createArticleWithTagsAndPublish(authorToken, List.of("Vue"));

        // 建立使用者並追蹤
        String userToken = authHelper.createUserWithRole(
                "tag-unfollow-user@test.com", "password123", "tagunfollowuser", "TagUnfollowUser", Role.USER);

        UUID tagId = jdbcTemplate.queryForObject(
                "SELECT id FROM tags WHERE name = ?", UUID.class, "vue");

        mockMvc.perform(post("/api/v1/tags/{id}/follow", tagId)
                        .with(AuthHelper.bearerToken(userToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // Act — 取消追蹤
        mockMvc.perform(delete("/api/v1/tags/{id}/follow", tagId)
                        .with(AuthHelper.bearerToken(userToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());
    }

    @Test
    @DisplayName("管理員更新標籤 — 成功更新描述與顏色")
    void adminUpdateTag_success() throws Exception {
        // Arrange — 用 ADMIN 建立標籤（透過文章）
        String adminToken = authHelper.createUserWithRole(
                "tag-admin-update@test.com", "password123", "tagadminupdate", "TagAdminUpdate", Role.ADMIN);
        createArticleWithTagsAndPublish(adminToken, List.of("Kubernetes"));

        UUID tagId = jdbcTemplate.queryForObject(
                "SELECT id FROM tags WHERE name = ?", UUID.class, "kubernetes");

        Map<String, Object> updateRequest = new LinkedHashMap<>();
        updateRequest.put("description", "Container orchestration platform");
        updateRequest.put("color", "#326CE5");

        // Act & Assert
        mockMvc.perform(put("/api/v1/admin/tags/{id}", tagId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(E2EAssertions.hasData())
                .andExpect(jsonPath("$.data.description").value("Container orchestration platform"))
                .andExpect(jsonPath("$.data.color").value("#326CE5"));
    }

    @Test
    @DisplayName("管理員刪除標籤 — 刪除後查詢回傳錯誤")
    void adminDeleteTag_success() throws Exception {
        // Arrange — 用 ADMIN 建立標籤
        String adminToken = authHelper.createUserWithRole(
                "tag-admin-del@test.com", "password123", "tagadmindel", "TagAdminDel", Role.ADMIN);
        createArticleWithTagsAndPublish(adminToken, List.of("Terraform"));

        UUID tagId = jdbcTemplate.queryForObject(
                "SELECT id FROM tags WHERE name = ?", UUID.class, "terraform");

        String slug = jdbcTemplate.queryForObject(
                "SELECT slug FROM tags WHERE id = ?", String.class, tagId);

        // 先清除文章引用和 usageCount（否則業務邏輯阻止刪除仍在使用的標籤）
        jdbcTemplate.update("DELETE FROM article_tags WHERE tag_id = ?", tagId);
        jdbcTemplate.update("UPDATE tags SET usage_count = 0 WHERE id = ?", tagId);

        // Act — 刪除標籤
        mockMvc.perform(delete("/api/v1/admin/tags/{id}", tagId)
                        .with(AuthHelper.bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // Assert — 查詢已刪除的標籤，應回傳錯誤碼 A0301
        mockMvc.perform(get("/api/v1/tags/{slug}", slug))
                .andExpect(status().isBadRequest())
                .andExpect(E2EAssertions.apiError("A0301"));
    }

    @Test
    @DisplayName("非管理員更新標籤 — 回傳 403")
    void adminUpdateTag_asUser_forbidden() throws Exception {
        // Arrange — 用 AUTHOR 建立標籤
        String authorToken = authHelper.createUserWithRole(
                "tag-forbidden-author@test.com", "password123", "tagforbiddenauthor", "TagForbiddenAuthor", Role.AUTHOR);
        createArticleWithTagsAndPublish(authorToken, List.of("Ansible"));

        UUID tagId = jdbcTemplate.queryForObject(
                "SELECT id FROM tags WHERE name = ?", UUID.class, "ansible");

        // 建立一般使用者
        String userToken = authHelper.createUserWithRole(
                "tag-forbidden-user@test.com", "password123", "tagforbiddenuser", "TagForbiddenUser", Role.USER);

        Map<String, Object> updateRequest = new LinkedHashMap<>();
        updateRequest.put("description", "Should not be allowed");

        // Act & Assert — USER 角色無 SYSTEM_CONFIG 權限
        mockMvc.perform(put("/api/v1/admin/tags/{id}", tagId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest))
                        .with(AuthHelper.bearerToken(userToken)))
                .andExpect(status().isForbidden());
    }
}
