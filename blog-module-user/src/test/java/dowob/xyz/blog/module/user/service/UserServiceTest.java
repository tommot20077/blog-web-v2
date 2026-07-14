package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(userRepository.existsByNickname("newNickname")).thenReturn(false);

        userService.updateProfile(TEST_USER_ID, "newNickname", "新的個人簡介", null, null, null, null);

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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        userService.updateProfile(TEST_USER_ID, TEST_NICKNAME, "簡介", "https://myblog.com",
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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        userService.updateProfile(TEST_USER_ID, TEST_NICKNAME, "簡介", null, null,
                "https://cdn.example.com/avatar.png", "Kaohsiung");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getAvatarUrl()).isEqualTo("https://cdn.example.com/avatar.png");
        assertThat(captor.getValue().getLocation()).isEqualTo("Kaohsiung");
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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        userService.updateNotificationPreferences(TEST_USER_ID, false, true, false, true, false);

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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateNotificationPreferences(
                TEST_USER_ID, true, true, true, true, true))
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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(userRepository.existsByNickname("takenNickname")).thenReturn(true);

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_ID, "takenNickname", null, null, null, null, null))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：updateProfile 在用戶不存在時應拋出 USER_NOT_FOUND BusinessException。
     */
    @Test
    @DisplayName("updateProfile → 用戶不存在 → 應拋出 BusinessException")
    void updateProfile_userNotFound_shouldThrowUserNotFound() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_ID, "newNickname", null, null, null, null, null))
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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode("newPassword")).thenReturn("newEncodedPassword");

        userService.changePassword(TEST_USER_ID, TEST_PASSWORD, "newPassword");

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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(TEST_USER_ID, TEST_PASSWORD, "newPassword"))
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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");

        userService.changePassword(TEST_USER_ID, TEST_PASSWORD, "newPassword");

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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.changePassword(TEST_USER_ID, TEST_PASSWORD, "newPassword"))
                .isInstanceOf(BusinessException.class);
    }

    /**
     * 驗證：changePassword 應撤銷該用戶所有 session，使既有 Token 立即失效。
     */
    @Test
    @DisplayName("changePassword → 應撤銷該用戶所有 session")
    void changePassword_shouldRevokeAllSessions() {
        User mockUser = buildActiveUser();
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");

        userService.changePassword(TEST_USER_ID, TEST_PASSWORD, "newPassword");

        verify(sessionRevoker).revokeAllSessions(TEST_USER_ID);
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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        userService.deleteAccount(TEST_USER_ID, TEST_PASSWORD);

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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteAccount(TEST_USER_ID, TEST_PASSWORD))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    /**
     * 驗證：deleteAccount 在用戶不存在時應拋出 BusinessException。
     */
    @Test
    @DisplayName("deleteAccount → 用戶不存在 → 應拋出 BusinessException")
    void deleteAccount_userNotFound_shouldThrowBusinessException() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.deleteAccount(TEST_USER_ID, TEST_PASSWORD))
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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        userService.deleteAccount(TEST_USER_ID, TEST_PASSWORD);

        verify(sessionRevoker).revokeAllSessions(TEST_USER_ID);
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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        UserProfileResponse result = userService.getUserProfile(TEST_USER_ID);

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
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUserProfile(TEST_USER_ID))
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
        user.setEmail("test@example.com");
        user.setPasswordHash("encodedPassword");
        user.setNickname(TEST_NICKNAME);
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion("v1");
        return user;
    }
}
