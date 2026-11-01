package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.model.dto.response.UserProfileResponse;
import dowob.xyz.blog.module.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * UserService 單元測試
 *
 * <p>
 * 使用 Mockito 隔離所有外部依賴，驗證 UserService 的核心業務邏輯：
 * 更新個人資料、修改密碼與刪除帳號。
 * </p>
 *
 * @author Yuan
 * @version 2.1
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    /** Mock：用戶資料存取 */
    @Mock
    private UserRepository userRepository;

    /** Mock：密碼加密器 */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** Mock：Session 撤銷服務 */
    @Mock
    private SessionRevoker sessionRevoker;

    /** 受測物件 */
    @InjectMocks
    private UserService userService;

    /** 測試用用戶 ID */
    private static final Long TEST_USER_ID = 1L;

    /** 測試用用戶 UUID（session 撤銷以 uuid 為鍵） */
    private static final java.util.UUID TEST_USER_UUID =
            java.util.UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 測試用密碼 */
    private static final String TEST_PASSWORD = "password123";

    /** 測試用暱稱 */
    private static final String TEST_NICKNAME = "testUser";

    /* =========================================================================
       updateProfile 測試
       ========================================================================= */

    /**
     * 驗證：updateProfile 應更新暱稱與個人簡介。
     */
    @Test
    @DisplayName("updateProfile → 應更新暱稱與個人簡介")
    void updateProfile_shouldUpdateNicknameAndBio() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(userRepository.existsByNickname("newNickname")).thenReturn(false);

        userService.updateProfile(TEST_USER_UUID, "newNickname", "新的個人簡介", null, null, null, null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getNickname()).isEqualTo("newNickname");
        assertThat(captor.getValue().getBio()).isEqualTo("新的個人簡介");
    }

    /**
     * 驗證：updateProfile 應正確設定 website 與 socialLinks 欄位。
     */
    @Test
    @DisplayName("updateProfile → 含 website 與 socialLinks → 應正確儲存欄位")
    void updateProfile_withWebsiteAndSocialLinks_shouldPersist() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, "簡介", "https://myblog.com",
                "{\"twitter\":\"@user\"}", "https://cdn.example.com/a.png", "Taipei");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getWebsite()).isEqualTo("https://myblog.com");
        assertThat(captor.getValue().getSocialLinks()).isEqualTo("{\"twitter\":\"@user\"}");
    }

    /**
     * 驗證：updateProfile 應正確設定 avatarUrl 與 location 欄位。
     */
    @Test
    @DisplayName("updateProfile → 含 avatarUrl 與 location → 應正確儲存欄位")
    void updateProfile_withAvatarUrlAndLocation_shouldPersist() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, "簡介", null, null,
                "https://cdn.example.com/avatar.png", "Kaohsiung");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarUrl()).isEqualTo("https://cdn.example.com/avatar.png");
        assertThat(captor.getValue().getLocation()).isEqualTo("Kaohsiung");
    }

    /* =========================================================================
       updateProfile — SEC-10 防禦縱深（不依賴 controller 的 @Valid）
       ========================================================================= */

    /**
     * 驗證：website 為惡意協定時直接呼叫 service 也應被拒絕，且在碰 DB 之前拒絕。
     */
    @Test
    @DisplayName("updateProfile → website 為 javascript: → 拒絕且不碰 DB（SEC-10）")
    void updateProfile_javascriptWebsite_shouldRejectBeforeAnyDbAccess() {
        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null,
                "javascript:alert(1)", null, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.PARAM_VALID_ERROR));

        verifyNoInteractions(userRepository);
    }

    /**
     * 驗證：avatarUrl 為 data: 時應被拒絕。
     */
    @Test
    @DisplayName("updateProfile → avatarUrl 為 data: → 拒絕且不儲存（SEC-10）")
    void updateProfile_dataAvatarUrl_shouldReject() {
        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null,
                null, null, "data:image/svg+xml;base64,PHN2Zz4=", null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.PARAM_VALID_ERROR));

        verifyNoInteractions(userRepository);
    }

    /**
     * 驗證：avatarUrl 為非檔案路徑的相對 URL 應被拒絕。
     */
    @Test
    @DisplayName("updateProfile → avatarUrl 為 //evil.com → 拒絕（SEC-10）")
    void updateProfile_protocolRelativeAvatarUrl_shouldReject() {
        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null,
                null, null, "//evil.com/a.png", null))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(userRepository);
    }

    /**
     * 驗證：socialLinks 的 value 含 scheme 時應被拒絕。
     */
    @Test
    @DisplayName("updateProfile → socialLinks value 為 javascript: → 拒絕（SEC-10）")
    void updateProfile_javascriptSocialLink_shouldReject() {
        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null,
                null, "{\"github\":\"javascript:alert(1)\"}", null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.PARAM_VALID_ERROR));

        verifyNoInteractions(userRepository);
    }

    /**
     * 驗證：socialLinks 不是合法 JSON object 時應被拒絕。
     */
    @Test
    @DisplayName("updateProfile → socialLinks 不是 JSON object → 拒絕（SEC-10）")
    void updateProfile_malformedSocialLinks_shouldReject() {
        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null,
                null, "[\"a\"]", null, null))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(userRepository);
    }

    /**
     * 驗證：頭貼為檔案上傳 API 回傳的本站路徑時應成功儲存。
     */
    @Test
    @DisplayName("updateProfile → avatarUrl 為本站檔案內容路徑 → 成功儲存（SEC-10 不破壞頭貼上傳）")
    void updateProfile_fileContentPathAvatar_shouldPersist() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        String path = "/api/v1/files/11111111-2222-3333-4444-555555555555/content";

        userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null, null, null, path, null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarUrl()).isEqualTo(path);
    }

    /**
     * 驗證：前端設定頁實際送出的 socialLinks（E2E 的 myhandle 等 handle 格式）可成功儲存。
     */
    @Test
    @DisplayName("updateProfile → 前端實際 socialLinks（myhandle 等）→ 成功儲存（SEC-10）")
    void updateProfile_frontendSocialLinks_shouldPersist() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        String social = "{\"github\":\"myhandle\",\"twitter\":\"mytwitter\",\"linkedin\":\"mylinkedin\"}";

        userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null, null, social, null, null);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getSocialLinks()).isEqualTo(social);
    }

    /**
     * 驗證：URL 欄位為空字串（清除）時應通過並原樣儲存。
     */
    @Test
    @DisplayName("updateProfile → URL 欄位為空字串 → 通過（清除）")
    void updateProfile_emptyUrlFields_shouldPersist() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null, "", "", "", null);

        verify(userRepository).save(any(User.class));
    }

    /* =========================================================================
       updateNotificationPreferences 測試
       ========================================================================= */

    /**
     * 驗證：updateNotificationPreferences 應更新 5 個通知偏好布林欄位。
     */
    @Test
    @DisplayName("updateNotificationPreferences → 應更新 5 個通知偏好欄位")
    void updateNotificationPreferences_shouldUpdateAllFlags() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        userService.updateNotificationPreferences(TEST_USER_UUID, false, true, false, true, false);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();
        assertThat(saved.isNotificationComment()).isFalse();
        assertThat(saved.isNotificationLike()).isTrue();
        assertThat(saved.isNotificationReview()).isFalse();
        assertThat(saved.isNotificationFollow()).isTrue();
        assertThat(saved.isNotificationNewsletter()).isFalse();
    }

    /**
     * 驗證：updateNotificationPreferences 在用戶不存在時應拋出 BusinessException。
     */
    @Test
    @DisplayName("updateNotificationPreferences → 用戶不存在 → 應拋出 BusinessException")
    void updateNotificationPreferences_userNotFound_shouldThrowBusinessException() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateNotificationPreferences(
                TEST_USER_UUID, true, true, true, true, true))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：updateProfile 使用已存在的暱稱（不屬於自己）應拋出 BusinessException。
     */
    @Test
    @DisplayName("updateProfile → 重複暱稱 → 應拋出 BusinessException")
    void updateProfile_withDuplicateNickname_shouldThrowBusinessException() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(userRepository.existsByNickname("takenNickname")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, "takenNickname", null, null, null, null, null))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：先查通過、寫入（UPDATE）時撞到 nickname 唯一約束（併發改成同一暱稱落敗）→ NICKNAME_DUPLICATED。
     */
    @Test
    @DisplayName("updateProfile → 寫入撞 uq_users_nickname（併發落敗）→ 拋 NICKNAME_DUPLICATED")
    void updateProfile_nicknameUniqueViolationOnSave_throwsNicknameDuplicated() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(buildActiveUser()));
        when(userRepository.existsByNickname("contested")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenThrow(new org.springframework.dao.DuplicateKeyException(
                "update users failed", new java.sql.SQLException(
                "ERROR: duplicate key value violates unique constraint \"uq_users_nickname\"\n"
                        + "  Detail: Key (nickname)=(contested) already exists.", "23505")));

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, "contested", null, null, null, null, null))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getCode()).isEqualTo(UserErrorCode.NICKNAME_DUPLICATED.getCode()));
    }

    /**
     * 驗證：寫入時的其他錯誤（含不認得的約束）不被吞掉，原例外照常拋出。
     */
    @Test
    @DisplayName("updateProfile → 寫入撞到不認得的約束或其他錯誤 → 原例外照常拋出")
    void updateProfile_unknownFailureOnSave_rethrowsOriginal() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(buildActiveUser()));
        org.springframework.dao.DuplicateKeyException unknown = new org.springframework.dao.DuplicateKeyException(
                "update users failed", new java.sql.SQLException(
                "ERROR: duplicate key value violates unique constraint \"users_email_key\"", "23505"));
        when(userRepository.save(any(User.class))).thenThrow(unknown);

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, TEST_NICKNAME, null, null, null, null, null))
                .isSameAs(unknown);
    }

    /**
     * 驗證：updateProfile 在用戶不存在時應拋出 USER_NOT_FOUND BusinessException。
     */
    @Test
    @DisplayName("updateProfile → 用戶不存在 → 應拋出 BusinessException")
    void updateProfile_userNotFound_shouldThrowUserNotFound() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_UUID, "newNickname", null, null, null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    /* =========================================================================
       changePassword 測試
       ========================================================================= */

    /**
     * 驗證：changePassword 使用正確舊密碼應更新密碼雜湊。
     */
    @Test
    @DisplayName("changePassword → 正確舊密碼 → 應更新密碼雜湊")
    void changePassword_withCorrectOldPassword_shouldUpdatePasswordHash() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode("newPassword")).thenReturn("newEncodedPassword");

        userService.changePassword(TEST_USER_UUID, TEST_PASSWORD, "newPassword");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getPasswordHash()).isEqualTo("newEncodedPassword");
    }

    /**
     * 驗證：changePassword 使用錯誤舊密碼應拋出 BusinessException。
     */
    @Test
    @DisplayName("changePassword → 錯誤舊密碼 → 應拋出 BusinessException")
    void changePassword_withWrongOldPassword_shouldThrowBusinessException() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(TEST_USER_UUID, TEST_PASSWORD, "newPassword"))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：changePassword 應遞增 tokenVersion，使舊 JWT Token 失效。
     */
    @Test
    @DisplayName("changePassword → 應遞增 tokenVersion")
    void changePassword_shouldIncrementTokenVersion() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");

        userService.changePassword(TEST_USER_UUID, TEST_PASSWORD, "newPassword");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getTokenVersion()).isEqualTo("v2");
    }

    /**
     * 驗證：changePassword 在用戶不存在時應拋出 BusinessException。
     */
    @Test
    @DisplayName("changePassword → 用戶不存在 → 應拋出 BusinessException")
    void changePassword_userNotFound_shouldThrowBusinessException() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changePassword(TEST_USER_UUID, TEST_PASSWORD, "newPassword"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 驗證：changePassword 應撤銷該用戶所有 session，使既有 Token 立即失效。
     */
    @Test
    @DisplayName("changePassword → 應撤銷該用戶所有 session")
    void changePassword_shouldRevokeAllSessions() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");

        userService.changePassword(TEST_USER_UUID, TEST_PASSWORD, "newPassword");

        verify(sessionRevoker).revokeAllSessions(TEST_USER_UUID);
    }

    /* =========================================================================
       deleteAccount 測試
       ========================================================================= */

    /**
     * 驗證：deleteAccount 使用正確密碼應將帳號狀態設為 DELETED。
     */
    @Test
    @DisplayName("deleteAccount → 正確密碼 → 應將狀態設為 DELETED")
    void deleteAccount_withCorrectPassword_shouldSetStatusDeleted() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(UserStatus.DELETED);
    }

    /**
     * 驗證：deleteAccount 使用錯誤密碼應拋出 BusinessException。
     */
    @Test
    @DisplayName("deleteAccount → 錯誤密碼 → 應拋出 BusinessException")
    void deleteAccount_withWrongPassword_shouldThrowBusinessException() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：deleteAccount 在用戶不存在時應拋出 BusinessException。
     */
    @Test
    @DisplayName("deleteAccount → 用戶不存在 → 應拋出 BusinessException")
    void deleteAccount_userNotFound_shouldThrowBusinessException() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 驗證：deleteAccount 應撤銷該用戶所有 session（清除 auth hash 與 refresh ZSet），
     * 確保所有 Token 立即失效。
     */
    @Test
    @DisplayName("deleteAccount → 應撤銷該用戶所有 session")
    void deleteAccount_shouldRevokeAllSessions() {
        User mockUser = buildActiveUser();
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        userService.deleteAccount(TEST_USER_UUID, TEST_PASSWORD);

        verify(sessionRevoker).revokeAllSessions(TEST_USER_UUID);
    }

    /* =========================================================================
       getUserProfile 測試
       ========================================================================= */

    /**
     * 驗證：getUserProfile 應回傳正確的使用者個人資料。
     */
    @Test
    @DisplayName("getUserProfile → 使用者存在 → 應回傳 UserProfileResponse")
    void getUserProfile_userExists_shouldReturnProfile() {
        UUID testUuid = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2024, 1, 1, 0, 0);
        User mockUser = buildActiveUser();
        mockUser.setUuid(testUuid);
        mockUser.setAvatarUrl("https://example.com/avatar.png");
        mockUser.setLocation("Taipei");
        mockUser.setNotificationComment(false);
        mockUser.setNotificationNewsletter(false);
        mockUser.setEmailVerified(true);
        mockUser.setCreatedAt(createdAt);
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        UserProfileResponse result = userService.getUserProfile(TEST_USER_UUID);

        assertThat(result.uuid()).isEqualTo(testUuid);
        assertThat(result.email()).isEqualTo("test@example.com");
        assertThat(result.nickname()).isEqualTo(TEST_NICKNAME);
        assertThat(result.avatarUrl()).isEqualTo("https://example.com/avatar.png");
        assertThat(result.location()).isEqualTo("Taipei");
        assertThat(result.role()).isEqualTo(Role.USER);
        assertThat(result.emailVerified()).isTrue();
        assertThat(result.createdAt()).isEqualTo(createdAt);
        assertThat(result.notificationComment()).isFalse();
        assertThat(result.notificationLike()).isTrue();
        assertThat(result.notificationReview()).isTrue();
        assertThat(result.notificationFollow()).isTrue();
        assertThat(result.notificationNewsletter()).isFalse();
    }

    /**
     * 驗證：getUserProfile 在使用者不存在時應拋出 BusinessException。
     */
    @Test
    @DisplayName("getUserProfile → 使用者不存在 → 應拋出 BusinessException")
    void getUserProfile_userNotFound_shouldThrowBusinessException() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserProfile(TEST_USER_UUID))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getErrorCode())
                        .isEqualTo(UserErrorCode.USER_NOT_FOUND));
    }

    /* =========================================================================
       測試輔助方法
       ========================================================================= */

    /**
     * 建立一個 ACTIVE 狀態的測試用 User 物件。
     *
     * @return 已設定基本欄位的 User 實體
     */
    private User buildActiveUser() {
        User user = new User();
        user.setId(TEST_USER_ID);
        user.setUuid(TEST_USER_UUID);
        user.setEmail("test@example.com");
        user.setPasswordHash("encodedPassword");
        user.setNickname(TEST_NICKNAME);
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion("v1");
        return user;
    }
}
