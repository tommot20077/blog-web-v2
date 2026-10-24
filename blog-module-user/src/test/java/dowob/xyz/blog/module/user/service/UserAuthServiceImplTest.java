package dowob.xyz.blog.module.user.service;

import dowob.xyz.blog.common.api.enums.Role;
import dowob.xyz.blog.common.api.enums.UserStatus;
import dowob.xyz.blog.infrastructure.security.AuthState;
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
 * UserAuthServiceImpl 單元測試
 *
 * <p>
 * 驗證 {@link UserAuthServiceImpl#findAuthStateByUuid} 在用戶存在、不存在與欄位為 null 時的行為。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@ExtendWith(MockitoExtension.class)
class UserAuthServiceImplTest {

    /** Mock：用戶資料存取 */
    @Mock
    private UserRepository userRepository;

    /** 受測物件 */
    @InjectMocks
    private UserAuthServiceImpl userAuthServiceImpl;

    /** 測試用用戶 ID */
    private static final Long TEST_USER_ID = 1L;

    /** 測試用用戶 UUID */
    private static final UUID TEST_USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    /** 測試用電子信箱 */
    private static final String TEST_EMAIL = "test@example.com";

    /* =========================================================================
       findAuthStateByUuid 測試
       ========================================================================= */

    /**
     * 驗證：以 uuid 查到用戶時，回傳含 id、uuid、status、role、tokenVersion 的認證狀態。
     */
    @Test
    @DisplayName("findAuthStateByUuid → 用戶存在 → 應回傳 id、uuid、status、role、tokenVersion")
    void findAuthStateByUuid_userExists_shouldReturnAllFields() {
        User mockUser = buildUser(UserStatus.ACTIVE, "v3");
        mockUser.setRole(Role.AUTHOR);
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(mockUser));

        Optional<AuthState> state = userAuthServiceImpl.findAuthStateByUuid(TEST_USER_UUID);

        assertThat(state).contains(new AuthState(TEST_USER_ID, TEST_USER_UUID, UserStatus.ACTIVE, Role.AUTHOR, "v3"));
    }

    /**
     * 驗證：非 ACTIVE 的狀態原樣回傳，是否放行由呼叫端決定。
     */
    @Test
    @DisplayName("findAuthStateByUuid → BANNED 用戶 → 狀態原樣回傳（政策由呼叫端決定）")
    void findAuthStateByUuid_bannedUser_shouldReturnStatusAsIs() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(buildUser(UserStatus.BANNED, "v1")));

        Optional<AuthState> state = userAuthServiceImpl.findAuthStateByUuid(TEST_USER_UUID);

        assertThat(state).get().extracting(AuthState::status).isEqualTo(UserStatus.BANNED);
    }

    /**
     * 驗證：查無用戶時回傳 empty，不拋例外。
     */
    @Test
    @DisplayName("findAuthStateByUuid → 用戶不存在 → 應回傳 empty")
    void findAuthStateByUuid_userNotFound_shouldReturnEmpty() {
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.empty());

        assertThat(userAuthServiceImpl.findAuthStateByUuid(TEST_USER_UUID)).isEmpty();
    }

    /**
     * 驗證：users.token_version 欄位可為 NULL，遇到 null 時不得建出缺欄位的 AuthState，改回 empty。
     */
    @Test
    @DisplayName("findAuthStateByUuid → tokenVersion 為 null → 應回傳 empty（不建 AuthState）")
    void findAuthStateByUuid_nullTokenVersion_shouldReturnEmpty() {
        User user = buildUser(UserStatus.ACTIVE, null);
        when(userRepository.findByUuid(TEST_USER_UUID)).thenReturn(Optional.of(user));

        assertThat(userAuthServiceImpl.findAuthStateByUuid(TEST_USER_UUID)).isEmpty();
    }

    /* =========================================================================
       測試輔助方法
       ========================================================================= */

    /**
     * 建立指定狀態與版本的測試用 User 物件。
     *
     * @param status       用戶狀態
     * @param tokenVersion Token 版本號
     * @return User 實體
     */
    private User buildUser(UserStatus status, String tokenVersion) {
        User user = new User();
        user.setId(TEST_USER_ID);
        user.setUuid(TEST_USER_UUID);
        user.setEmail(TEST_EMAIL);
        user.setNickname("testUser");
        user.setRole(Role.USER);
        user.setStatus(status);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
