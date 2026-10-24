package dowob.xyz.blog.common.security;

import dowob.xyz.blog.common.api.enums.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * AuthUser 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("AuthUser 單元測試")
class AuthUserTest {

    private static final UUID USER_UUID = UUID.fromString("00000000-0000-0000-0000-00000000000a");

    @Test
    @DisplayName("三個欄位皆非 null → 建立成功並可讀回")
    void constructor_allFieldsPresent_succeeds() {
        AuthUser user = new AuthUser(7L, USER_UUID, Role.AUTHOR);

        assertThat(user.id()).isEqualTo(7L);
        assertThat(user.uuid()).isEqualTo(USER_UUID);
        assertThat(user.role()).isEqualTo(Role.AUTHOR);
    }

    @Test
    @DisplayName("id 為 null → NullPointerException（過渡期各模組仍依賴 id）")
    void constructor_nullId_throwsNullPointerException() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AuthUser(null, USER_UUID, Role.USER))
                .withMessageContaining("id");
    }

    @Test
    @DisplayName("uuid 為 null → NullPointerException")
    void constructor_nullUuid_throwsNullPointerException() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AuthUser(1L, null, Role.USER))
                .withMessageContaining("uuid");
    }

    @Test
    @DisplayName("role 為 null → NullPointerException")
    void constructor_nullRole_throwsNullPointerException() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AuthUser(1L, USER_UUID, null))
                .withMessageContaining("role");
    }
}
