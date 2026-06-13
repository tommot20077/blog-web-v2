package dowob.xyz.blog.e2e.file;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.e2e.config.AbstractE2ETest;
import dowob.xyz.blog.e2e.support.AuthHelper;
import dowob.xyz.blog.e2e.support.DatabaseCleaner;
import dowob.xyz.blog.e2e.support.E2EAssertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * File 模組 E2E 測試
 *
 * <p>涵蓋檔案上傳、查詢、刪除、配額等完整流程。</p>
 */
@DisplayName("File E2E 測試")
class FileE2E extends AbstractE2ETest {

    @Autowired
    private DatabaseCleaner databaseCleaner;

    @Autowired
    private AuthHelper authHelper;

    @AfterEach
    void tearDown() {
        databaseCleaner.cleanAll();
    }

    @Test
    @DisplayName("AUTHOR 上傳檔案 — 回傳檔案元資料")
    void uploadFile_asAuthor_success() throws Exception {
        // Arrange
        String authorToken = authHelper.createUserWithRole(
                "file-author@test.com", "Password123!", "fileauthor", "FileAuthor", Role.AUTHOR);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg",
                getClass().getClassLoader().getResourceAsStream("test-files/test.jpg"));

        // Act & Assert
        mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(file)
                        .param("usageType", "ARTICLE_COVER")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(E2EAssertions.hasData())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.url").isNotEmpty())
                .andExpect(jsonPath("$.data.size").isNumber())
                .andExpect(jsonPath("$.data.usageType").value("ARTICLE_COVER"));
    }

    @Test
    @DisplayName("上傳後查詢檔案元資料 — 成功取得")
    void getFileMetadata_afterUpload_success() throws Exception {
        // Arrange — 上傳檔案
        String authorToken = authHelper.createUserWithRole(
                "file-meta@test.com", "Password123!", "filemeta", "FileMeta", Role.AUTHOR);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg",
                getClass().getClassLoader().getResourceAsStream("test-files/test.jpg"));

        String uploadResponse = mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(file)
                        .param("usageType", "ARTICLE_COVER")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String fileId = objectMapper.readTree(uploadResponse).get("data").get("id").asText();

        // Act & Assert — 用回傳 ID 查詢檔案元資料（公開端點）
        mockMvc.perform(get("/api/v1/files/{id}", fileId))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(E2EAssertions.hasData())
                .andExpect(jsonPath("$.data.id").value(fileId))
                .andExpect(jsonPath("$.data.originalName").value("test.jpg"))
                .andExpect(jsonPath("$.data.contentType").value("image/jpeg"));
    }

    @Test
    @DisplayName("擁有者刪除檔案 — 刪除後查詢回傳 404")
    void deleteFile_asOwner_success() throws Exception {
        // Arrange — 上傳檔案
        String authorToken = authHelper.createUserWithRole(
                "file-del@test.com", "Password123!", "filedel", "FileDel", Role.AUTHOR);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg",
                getClass().getClassLoader().getResourceAsStream("test-files/test.jpg"));

        String uploadResponse = mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(file)
                        .param("usageType", "ARTICLE_COVER")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String fileId = objectMapper.readTree(uploadResponse).get("data").get("id").asText();

        // Act — 刪除檔案
        mockMvc.perform(delete("/api/v1/files/{id}", fileId)
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess());

        // Assert — 查詢已刪除的檔案，應回傳錯誤碼 A0401
        mockMvc.perform(get("/api/v1/files/{id}", fileId))
                .andExpect(status().isBadRequest())
                .andExpect(E2EAssertions.apiError("A0401"));
    }

    @Test
    @DisplayName("查詢使用者檔案列表 — 上傳後可列出")
    void getUserFiles_success() throws Exception {
        // Arrange — 上傳兩個檔案
        String authorToken = authHelper.createUserWithRole(
                "file-list@test.com", "Password123!", "filelist", "FileList", Role.AUTHOR);

        for (int i = 0; i < 2; i++) {
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test" + i + ".jpg", "image/jpeg",
                    getClass().getClassLoader().getResourceAsStream("test-files/test.jpg"));

            mockMvc.perform(multipart("/api/v1/files/upload")
                            .file(file)
                            .param("usageType", "ARTICLE_CONTENT")
                            .with(AuthHelper.bearerToken(authorToken)))
                    .andExpect(status().isOk())
                    .andExpect(E2EAssertions.apiSuccess());
        }

        // Act & Assert — 查詢使用者檔案列表
        mockMvc.perform(get("/api/v1/users/me/files")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(E2EAssertions.hasData())
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    @DisplayName("查詢使用者配額 — 回傳配額資訊")
    void getUserQuota_success() throws Exception {
        // Arrange
        String authorToken = authHelper.createUserWithRole(
                "file-quota@test.com", "Password123!", "filequota", "FileQuota", Role.AUTHOR);

        // Act & Assert
        mockMvc.perform(get("/api/v1/users/me/quota")
                        .with(AuthHelper.bearerToken(authorToken)))
                .andExpect(status().isOk())
                .andExpect(E2EAssertions.apiSuccess())
                .andExpect(E2EAssertions.hasData())
                .andExpect(jsonPath("$.data.usedBytes").isNumber())
                .andExpect(jsonPath("$.data.limitBytes").isNumber())
                .andExpect(jsonPath("$.data.remainingBytes").isNumber());
    }

    @Test
    @DisplayName("USER 角色上傳檔案 — 無 FILE_UPLOAD 權限回傳 403")
    void uploadFile_asUser_forbidden() throws Exception {
        // Arrange — USER 角色無 FILE_UPLOAD 權限
        String userToken = authHelper.createUserWithRole(
                "file-user@test.com", "Password123!", "fileuser", "FileUser", Role.USER);

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.jpg", "image/jpeg",
                getClass().getClassLoader().getResourceAsStream("test-files/test.jpg"));

        // Act & Assert
        mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(file)
                        .param("usageType", "ARTICLE_COVER")
                        .with(AuthHelper.bearerToken(userToken)))
                .andExpect(status().isForbidden());
    }
}
