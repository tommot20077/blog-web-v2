package dowob.xyz.blog.e2e.article;

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
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static dowob.xyz.blog.e2e.support.AuthHelper.bearerToken;
import static dowob.xyz.blog.e2e.support.E2EAssertions.apiSuccess;
import static dowob.xyz.blog.e2e.support.E2EAssertions.hasData;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Category E2E 測試
 *
 * <p>
 * 驗證分類模組完整的 HTTP 流程，涵蓋管理員 CRUD 與公開查詢端點。
 * </p>
 */
@DisplayName("Category E2E 測試")
class CategoryE2E extends AbstractE2ETest {

    @Autowired
    private AuthHelper authHelper;

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    // ========== Helper Methods ==========

    /**
     * 管理員建立分類並回傳 UUID
     */
    private String createCategoryAndGetUuid(String adminToken, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/admin/categories")
                        .with(bearerToken(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("data").get("uuid").asText();
    }

    /**
     * 建立包含 description 和 sortOrder 的分類請求
     */
    private Map<String, Object> categoryWithDetails(String name, String slug,
                                                     String description, int sortOrder) {
        Map<String, Object> map = DataBuilder.category(name, slug);
        map.put("description", description);
        map.put("sortOrder", sortOrder);
        return map;
    }

    // ========== Test Scenarios ==========

    @Test
    @DisplayName("匿名使用者可列出所有分類")
    void listCategories_public_success() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "admin@test.com", "Password1!", "admin1", "Admin", Role.ADMIN);

        // 建立兩個分類
        createCategoryAndGetUuid(adminToken,
                categoryWithDetails("技術", "tech", "技術相關文章", 1));
        createCategoryAndGetUuid(adminToken,
                categoryWithDetails("生活", "life", "生活類文章", 2));

        // 匿名查詢
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(hasData())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("透過 slug 取得分類詳情成功")
    void getCategoryBySlug_success() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "admin@test.com", "Password1!", "admin1", "Admin", Role.ADMIN);

        createCategoryAndGetUuid(adminToken,
                categoryWithDetails("後端開發", "backend", "後端開發相關", 1));

        mockMvc.perform(get("/api/v1/categories/backend"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.name").value("後端開發"))
                .andExpect(jsonPath("$.data.slug").value("backend"))
                .andExpect(jsonPath("$.data.description").value("後端開發相關"));
    }

    @Test
    @DisplayName("管理員建立分類成功")
    void adminCreateCategory_success() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "admin@test.com", "Password1!", "admin1", "Admin", Role.ADMIN);

        Map<String, Object> body = categoryWithDetails("前端開發", "frontend", "前端開發相關", 3);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .with(bearerToken(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(hasData())
                .andExpect(jsonPath("$.data.uuid").exists())
                .andExpect(jsonPath("$.data.name").value("前端開發"))
                .andExpect(jsonPath("$.data.slug").value("frontend"))
                .andExpect(jsonPath("$.data.description").value("前端開發相關"))
                .andExpect(jsonPath("$.data.sortOrder").value(3));
    }

    @Test
    @DisplayName("管理員更新分類成功")
    void adminUpdateCategory_success() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "admin@test.com", "Password1!", "admin1", "Admin", Role.ADMIN);

        String uuid = createCategoryAndGetUuid(adminToken,
                categoryWithDetails("舊名稱", "old-slug", "舊描述", 1));

        Map<String, Object> updateBody = new LinkedHashMap<>();
        updateBody.put("name", "新名稱");
        updateBody.put("description", "新描述");
        updateBody.put("sortOrder", 5);

        mockMvc.perform(put("/api/v1/admin/categories/" + uuid)
                        .with(bearerToken(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateBody)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.name").value("新名稱"))
                .andExpect(jsonPath("$.data.description").value("新描述"))
                .andExpect(jsonPath("$.data.sortOrder").value(5));
    }

    @Test
    @DisplayName("管理員刪除分類成功")
    void adminDeleteCategory_success() throws Exception {
        String adminToken = authHelper.createUserWithRole(
                "admin@test.com", "Password1!", "admin1", "Admin", Role.ADMIN);

        String uuid = createCategoryAndGetUuid(adminToken,
                categoryWithDetails("待刪除", "to-delete", "將被刪除", 1));

        // 刪除
        mockMvc.perform(delete("/api/v1/admin/categories/" + uuid)
                        .with(bearerToken(adminToken)))
                .andExpect(status().isOk())
                .andExpect(apiSuccess());

        // 確認列表中已不存在
        mockMvc.perform(get("/api/v1/categories"))
                .andExpect(status().isOk())
                .andExpect(apiSuccess())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("一般使用者建立分類被拒絕，回傳 403")
    void adminCreateCategory_asUser_forbidden() throws Exception {
        String userToken = authHelper.createUserWithRole(
                "user@test.com", "Password1!", "user1", "User", Role.USER);

        Map<String, Object> body = categoryWithDetails("非法分類", "illegal", "不該成功", 1);

        mockMvc.perform(post("/api/v1/admin/categories")
                        .with(bearerToken(userToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
    }
}
