package dowob.xyz.blog.module.file.controller;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.exception.HttpStatusBusinessException;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.module.file.model.FileMetadata;
import dowob.xyz.blog.module.file.model.UsageType;
import dowob.xyz.blog.module.file.service.FileService;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link FileController} 單元測試
 *
 * <p>驗證 controller 直接以 {@link AuthUser#uuid()} 作為上傳者／請求者識別，
 * 不再經由 user 模組把 Long id 換成 UUID（跨模組識別全面 UUID 化，M5）。
 * {@code @InjectMocks} 只提供 {@link FileService}；若 controller 仍依賴 {@code UserFacade}，
 * 該依賴會是 null 而於呼叫時失敗。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FileController 單元測試")
class FileControllerTest {

    @Mock
    private FileService fileService;

    @InjectMocks
    private FileController fileController;

    /** 建立已登入身分；刻意讓 {@code id} 與 {@code uuid} 毫無關聯，避免 controller 偷用 id 推導 */
    private static AuthUser authUser(Role role) {
        return new AuthUser(UUID.randomUUID(), role);
    }

    /** 建立與 {@link AuthUser} 對應的 Authentication（角色 authority 與 principal 同源） */
    private static Authentication authenticationOf(AuthUser user) {
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority(user.role().getSpringSecurityRole())));
    }

    /** 上傳：uploaderId 為 AuthUser.uuid() */
    @Test
    @DisplayName("uploadFile_authenticated_usesAuthUserUuidAsUploaderId")
    void uploadFile_authenticated_usesAuthUserUuidAsUploaderId() {
        AuthUser user = authUser(Role.AUTHOR);
        MockMultipartFile file = new MockMultipartFile("file", "a.jpg", "image/jpeg", new byte[]{1});
        UUID articleUuid = UUID.randomUUID();

        fileController.uploadFile(file, UsageType.ARTICLE_CONTENT, articleUuid, user, authenticationOf(user));

        verify(fileService).uploadFile(file, UsageType.ARTICLE_CONTENT, user.uuid(), "AUTHOR", articleUuid);
    }

    /** 取得 metadata：登入者以 AuthUser.uuid() 作為 requesterId */
    @Test
    @DisplayName("getFileMetadata_authenticated_usesAuthUserUuidAsRequesterId")
    void getFileMetadata_authenticated_usesAuthUserUuidAsRequesterId() {
        AuthUser user = authUser(Role.USER);
        UUID fileId = UUID.randomUUID();
        FileMetadata metadata = new FileMetadata();
        when(fileService.getFileMetadata(fileId)).thenReturn(metadata);
        when(fileService.canRead(metadata, user.uuid(), false)).thenReturn(true);

        var response = fileController.getFileMetadata(fileId, user, authenticationOf(user));

        assertThat(response.getData()).isSameAs(metadata);
        verify(fileService).canRead(metadata, user.uuid(), false);
    }

    /** 取得 metadata：匿名（user 為 null）時 requesterId 為 null */
    @Test
    @DisplayName("getFileMetadata_anonymous_passesNullRequesterId")
    void getFileMetadata_anonymous_passesNullRequesterId() {
        UUID fileId = UUID.randomUUID();
        FileMetadata metadata = new FileMetadata();
        when(fileService.getFileMetadata(fileId)).thenReturn(metadata);
        when(fileService.canRead(metadata, null, false)).thenReturn(true);

        var response = fileController.getFileMetadata(fileId, null, null);

        assertThat(response.getData()).isSameAs(metadata);
        verify(fileService).canRead(metadata, null, false);
    }

    /** 建立含 id／contentType／size 的 metadata，供 /content 串流測試使用 */
    private static FileMetadata contentMetadata(UUID fileId, byte[] bytes) {
        FileMetadata metadata = new FileMetadata();
        metadata.setId(fileId);
        metadata.setContentType("image/png");
        metadata.setSize((long) bytes.length);
        return metadata;
    }

    /** W5A：登入者以 AuthUser.uuid() 作為 requesterId，後端串流 200 + 內容，非公開 → private, no-store */
    @Test
    @DisplayName("getFileContent_authenticatedNonPublic_streamsBytesWithPrivateNoStore")
    void getFileContent_authenticatedNonPublic_streamsBytesWithPrivateNoStore() throws Exception {
        AuthUser user = authUser(Role.AUTHOR);
        UUID fileId = UUID.randomUUID();
        byte[] bytes = {9, 8, 7, 6, 5};
        FileMetadata metadata = contentMetadata(fileId, bytes);
        when(fileService.getFileMetadata(fileId)).thenReturn(metadata);
        when(fileService.canRead(metadata, user.uuid(), false)).thenReturn(true);
        when(fileService.isPubliclyReadable(metadata)).thenReturn(false);
        when(fileService.openContent(metadata)).thenReturn(new ByteArrayInputStream(bytes));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fileController.getFileContent(fileId, user, authenticationOf(user), null, response);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(response.getContentType()).isEqualTo("image/png");
        assertThat(response.getHeader("Content-Length")).isEqualTo("5");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Content-Disposition")).isEqualTo("inline");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("private, no-store");
        assertThat(response.getHeader("ETag")).isNotBlank();
        assertThat(response.getHeader("Location")).isNull();
        verify(fileService).canRead(metadata, user.uuid(), false);
    }

    /** W5A：匿名（user 為 null）時 requesterId 為 null；能讀即為公開 → public, max-age=86400 */
    @Test
    @DisplayName("getFileContent_anonymous_passesNullRequesterIdAndIsPublicCacheable")
    void getFileContent_anonymous_passesNullRequesterIdAndIsPublicCacheable() throws Exception {
        UUID fileId = UUID.randomUUID();
        byte[] bytes = {1, 2, 3};
        FileMetadata metadata = contentMetadata(fileId, bytes);
        when(fileService.getFileMetadata(fileId)).thenReturn(metadata);
        when(fileService.canRead(metadata, null, false)).thenReturn(true);
        when(fileService.openContent(metadata)).thenReturn(new ByteArrayInputStream(bytes));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fileController.getFileContent(fileId, null, null, null, response);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(response.getContentAsByteArray()).isEqualTo(bytes);
        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=86400");
        verify(fileService).canRead(metadata, null, false);
    }

    /** W5A：登入者讀取公開檔案（例如作者看自己已發布文章的圖）→ 仍為 public 快取 */
    @Test
    @DisplayName("getFileContent_authenticatedPubliclyReadable_isPublicCacheable")
    void getFileContent_authenticatedPubliclyReadable_isPublicCacheable() throws Exception {
        AuthUser user = authUser(Role.USER);
        UUID fileId = UUID.randomUUID();
        byte[] bytes = {1};
        FileMetadata metadata = contentMetadata(fileId, bytes);
        when(fileService.getFileMetadata(fileId)).thenReturn(metadata);
        when(fileService.canRead(metadata, user.uuid(), false)).thenReturn(true);
        when(fileService.isPubliclyReadable(metadata)).thenReturn(true);
        when(fileService.openContent(metadata)).thenReturn(new ByteArrayInputStream(bytes));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fileController.getFileContent(fileId, user, authenticationOf(user), null, response);

        assertThat(response.getHeader("Cache-Control")).isEqualTo("public, max-age=86400");
    }

    /** W5A：If-None-Match 命中 ETag → 304，且不開啟物件儲存、不寫 body（授權仍先判斷） */
    @Test
    @DisplayName("getFileContent_ifNoneMatchHitsEtag_returns304WithoutBody")
    void getFileContent_ifNoneMatchHitsEtag_returns304WithoutBody() throws Exception {
        UUID fileId = UUID.randomUUID();
        FileMetadata metadata = contentMetadata(fileId, new byte[]{1});
        when(fileService.getFileMetadata(fileId)).thenReturn(metadata);
        when(fileService.canRead(metadata, null, false)).thenReturn(true);
        MockHttpServletResponse first = new MockHttpServletResponse();
        when(fileService.openContent(metadata)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        fileController.getFileContent(fileId, null, null, null, first);
        String etag = first.getHeader("ETag");
        MockHttpServletResponse response = new MockHttpServletResponse();

        fileController.getFileContent(fileId, null, null, etag, response);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_NOT_MODIFIED);
        assertThat(response.getContentAsByteArray()).isEmpty();
        assertThat(response.getHeader("ETag")).isEqualTo(etag);
        verify(fileService, times(1)).openContent(metadata);
    }

    /** W5A：If-None-Match 不吻合 → 正常 200 */
    @Test
    @DisplayName("getFileContent_ifNoneMatchMismatch_returns200")
    void getFileContent_ifNoneMatchMismatch_returns200() throws Exception {
        UUID fileId = UUID.randomUUID();
        FileMetadata metadata = contentMetadata(fileId, new byte[]{1});
        when(fileService.getFileMetadata(fileId)).thenReturn(metadata);
        when(fileService.canRead(metadata, null, false)).thenReturn(true);
        when(fileService.openContent(metadata)).thenReturn(new ByteArrayInputStream(new byte[]{1}));
        MockHttpServletResponse response = new MockHttpServletResponse();

        fileController.getFileContent(fileId, null, null, "\"other\"", response);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    /** W5A：無權限者即使帶了正確 ETag 也必須 403（授權先於 304，不洩漏存在性以外的資訊） */
    @Test
    @DisplayName("getFileContent_forbiddenEvenWithMatchingEtag_throws403")
    void getFileContent_forbiddenEvenWithMatchingEtag_throws403() {
        UUID fileId = UUID.randomUUID();
        FileMetadata metadata = contentMetadata(fileId, new byte[]{1});
        when(fileService.getFileMetadata(fileId)).thenReturn(metadata);
        when(fileService.canRead(metadata, null, false)).thenReturn(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> fileController.getFileContent(fileId, null, null, "*", response))
                .isInstanceOf(HttpStatusBusinessException.class)
                .extracting(e -> ((HttpStatusBusinessException) e).getStatus())
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(fileService, never()).openContent(metadata);
    }

    /** 刪除：requesterId 為 AuthUser.uuid()；ADMIN 角色傳遞 isAdmin = true */
    @Test
    @DisplayName("deleteFile_admin_usesAuthUserUuidAsRequesterIdAndIsAdminTrue")
    void deleteFile_admin_usesAuthUserUuidAsRequesterIdAndIsAdminTrue() {
        AuthUser user = authUser(Role.ADMIN);
        UUID fileId = UUID.randomUUID();

        fileController.deleteFile(fileId, user, authenticationOf(user));

        verify(fileService).deleteFile(fileId, user.uuid(), true);
    }

    /** 我的檔案：uploaderId 為 AuthUser.uuid() */
    @Test
    @DisplayName("getUserFiles_authenticated_usesAuthUserUuidAsUploaderId")
    void getUserFiles_authenticated_usesAuthUserUuidAsUploaderId() {
        AuthUser user = authUser(Role.USER);
        Pageable pageable = PageRequest.of(0, 10);
        List<FileMetadata> files = List.of(new FileMetadata());
        when(fileService.getUserFiles(user.uuid(), pageable)).thenReturn(files);

        var response = fileController.getUserFiles(user, pageable);

        assertThat(response.getData()).isSameAs(files);
    }

    /** 配額：uploaderId 為 AuthUser.uuid()，角色取自 Authentication */
    @Test
    @DisplayName("getQuota_authenticated_usesAuthUserUuidAsUploaderId")
    void getQuota_authenticated_usesAuthUserUuidAsUploaderId() {
        AuthUser user = authUser(Role.AUTHOR);

        fileController.getQuota(user, authenticationOf(user));

        verify(fileService).getQuota(eq(user.uuid()), eq("AUTHOR"));
    }
}
