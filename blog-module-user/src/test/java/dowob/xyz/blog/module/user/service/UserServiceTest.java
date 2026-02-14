package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.exception.BusinessException;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

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
 * @version 2.0
 */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    /** Mock：用戶資料存取 */
    @Mock
    private UserRepository userRepository;

    /** Mock：密碼加密器 */
    @Mock
    private PasswordEncoder passwordEncoder;

    /** Mock：Redis 操作模板 */
    @Mock
    private StringRedisTemplate redisTemplate;

    /** Mock：Redis Hash 操作 */
    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    /** 受測物件 */
    @InjectMocks
    private UserService userService;

    /** 測試用用戶 ID */
    private static final Long TEST_USER_ID = 1L;

    /** 測試用密碼 */
    private static final String TEST_PASSWORD = "password123";

    /** 測試用暱稱 */
    private static final String TEST_NICKNAME = "testUser";

    // =========================================================================
    // updateProfile 測試
    // =========================================================================

    /**
     * 驗證：updateProfile 應更新暱稱與個人簡介。
     */
    @Test
    @DisplayName("updateProfile → 應更新暱稱與個人簡介")
    void updateProfile_shouldUpdateNicknameAndBio() {
        User mockUser = buildActiveUser();
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(userRepository.existsByNickname("newNickname")).thenReturn(false);

        userService.updateProfile(TEST_USER_ID, "newNickname", "新的個人簡介", null, null);

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

        userService.updateProfile(TEST_USER_ID, TEST_NICKNAME, "簡介", "https://myblog.com", "{\"twitter\":\"@user\"}");

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getWebsite()).isEqualTo("https://myblog.com");
        assertThat(captor.getValue().getSocialLinks()).isEqualTo("{\"twitter\":\"@user\"}");
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

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_ID, "takenNickname", null, null, null))
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

        assertThatThrownBy(() -> userService.updateProfile(TEST_USER_ID, "newNickname", null, null, null))
                .isInstanceOf(BusinessException.class);
    }

    // =========================================================================
    // changePassword 測試
    // =========================================================================

    /**
     * 驗證：changePassword 使用正確舊密碼應更新密碼雜湊。
     */
    @Test
    @DisplayName("changePassword → 正確舊密碼 → 應更新密碼雜湊")
    void changePassword_withCorrectOldPassword_shouldUpdatePasswordHash() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
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
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
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
     * 驗證：changePassword 應同步更新 Redis Auth Hash 中的版本號，確保 JWT 版本一致。
     */
    @Test
    @DisplayName("changePassword → 應同步更新 Redis user:auth:{id} 中的版本號")
    void changePassword_shouldSyncVersionInRedis() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        User mockUser = buildActiveUser();
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode(anyString())).thenReturn("newEncodedPassword");

        userService.changePassword(TEST_USER_ID, TEST_PASSWORD, "newPassword");

        verify(hashOperations).put(anyString(), eq(RedisKeyConstant.FIELD_VERSION), eq("v2"));
    }

    // =========================================================================
    // deleteAccount 測試
    // =========================================================================

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
     * 驗證：deleteAccount 應清除 Redis 中 user:auth:{id} 與 refresh:token:{id} 兩個快取鍵，
     * 確保所有 Token 立即失效。
     */
    @Test
    @DisplayName("deleteAccount → 應清除 Redis 中的 user:auth:{id} 與 refresh:token:{id} 快取")
    void deleteAccount_shouldClearBothRedisKeys() {
        User mockUser = buildActiveUser();
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));
        when(passwordEncoder.matches(TEST_PASSWORD, mockUser.getPasswordHash())).thenReturn(true);

        userService.deleteAccount(TEST_USER_ID, TEST_PASSWORD);

        verify(redisTemplate, times(2)).delete(anyString());
    }

    // =========================================================================
    // 測試輔助方法
    // =========================================================================

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
