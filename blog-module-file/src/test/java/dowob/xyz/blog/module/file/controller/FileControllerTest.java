package dowob.xyz.blog.module.file.controller;

import dowob.xyz.blog.common.api.enums.Role;
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
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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
        return new AuthUser(12345L, UUID.randomUUID(), role);
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

    /** 內容導向：登入者以 AuthUser.uuid() 作為 requesterId，且回 302 + Location */
    @Test
    @DisplayName("getFileContent_authenticated_usesAuthUserUuidAsRequesterId")
    void getFileContent_authenticated_usesAuthUserUuidAsRequesterId() {
        AuthUser user = authUser(Role.AUTHOR);
        UUID fileId = UUID.randomUUID();
        FileMetadata metadata = new FileMetadata();
        when(fileService.getFileMetadata(fileId)).thenReturn(metadata);
        when(fileService.canRead(metadata, user.uuid(), false)).thenReturn(true);
        when(fileService.generatePresignedUrl(metadata)).thenReturn("http://storage/presigned");
        MockHttpServletResponse response = new MockHttpServletResponse();

        fileController.getFileContent(fileId, user, authenticationOf(user), response);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FOUND);
        assertThat(response.getHeader("Location")).isEqualTo("http://storage/presigned");
        verify(fileService).canRead(metadata, user.uuid(), false);
    }

    /** 內容導向：匿名（user 為 null）時 requesterId 為 null */
    @Test
    @DisplayName("getFileContent_anonymous_passesNullRequesterId")
    void getFileContent_anonymous_passesNullRequesterId() {
        UUID fileId = UUID.randomUUID();
        FileMetadata metadata = new FileMetadata();
        when(fileService.getFileMetadata(fileId)).thenReturn(metadata);
        when(fileService.canRead(metadata, null, false)).thenReturn(true);
        when(fileService.generatePresignedUrl(metadata)).thenReturn("http://storage/presigned");
        MockHttpServletResponse response = new MockHttpServletResponse();

        fileController.getFileContent(fileId, null, null, response);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FOUND);
        verify(fileService).canRead(metadata, null, false);
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
