package dowob.xyz.blog.module.user.facade;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.module.user.model.User;
import dowob.xyz.blog.module.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * UserFacadeImpl 單元測試
 *
 * <p>
 * 驗證 {@link UserFacadeImpl} 跨模組查詢方法的行為：
 * {@code getUserUuidById} 與 {@code getUserNicknameById}。
 * 測試涵蓋用戶存在（回傳正確值）與不存在（回傳 Optional.empty()）兩種情境。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class UserFacadeImplTest {

    /** Mock：用戶資料存取 */
    @Mock
    private UserRepository userRepository;

    /** 受測物件 */
    @InjectMocks
    private UserFacadeImpl userFacadeImpl;

    /** 測試用用戶 ID */
    private static final Long TEST_USER_ID = 1L;

    // =========================================================================
    // getUserUuidById 測試
    // =========================================================================

    /**
     * 驗證：用戶存在時應回傳含 UUID 的 Optional。
     */
    @Test
    @DisplayName("getUserUuidById → 用戶存在 → 應回傳 Optional<UUID>")
    void getUserUuidById_userExists_shouldReturnUuid() {
        UUID expectedUuid = UUID.randomUUID();
        User mockUser = buildUser(expectedUuid, "testUser");
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        Optional<UUID> result = userFacadeImpl.getUserUuidById(TEST_USER_ID);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo(expectedUuid);
    }

    /**
     * 驗證：用戶不存在時應回傳 Optional.empty()。
     */
    @Test
    @DisplayName("getUserUuidById → 用戶不存在 → 應回傳 Optional.empty()")
    void getUserUuidById_userNotFound_shouldReturnEmpty() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        Optional<UUID> result = userFacadeImpl.getUserUuidById(TEST_USER_ID);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // getUserNicknameById 測試
    // =========================================================================

    /**
     * 驗證：用戶存在時應回傳含暱稱的 Optional。
     */
    @Test
    @DisplayName("getUserNicknameById → 用戶存在 → 應回傳 Optional<String> 含暱稱")
    void getUserNicknameById_userExists_shouldReturnNickname() {
        User mockUser = buildUser(UUID.randomUUID(), "myNickname");
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.of(mockUser));

        Optional<String> result = userFacadeImpl.getUserNicknameById(TEST_USER_ID);

        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("myNickname");
    }

    /**
     * 驗證：用戶不存在時應回傳 Optional.empty()。
     */
    @Test
    @DisplayName("getUserNicknameById → 用戶不存在 → 應回傳 Optional.empty()")
    void getUserNicknameById_userNotFound_shouldReturnEmpty() {
        when(userRepository.findById(TEST_USER_ID)).thenReturn(Optional.empty());

        Optional<String> result = userFacadeImpl.getUserNicknameById(TEST_USER_ID);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // 測試輔助方法
    // =========================================================================

    /**
     * 建立測試用 User 物件。
     *
     * @param uuid     對外公開的 UUID
     * @param nickname 暱稱
     * @return User 實體
     */
    private User buildUser(UUID uuid, String nickname) {
        User user = new User();
        user.setId(TEST_USER_ID);
        user.setUuid(uuid);
        user.setEmail("test@example.com");
        user.setNickname(nickname);
        user.setRole(Role.USER);
        user.setStatus(UserStatus.ACTIVE);
        user.setTokenVersion("v1");
        return user;
    }
}
