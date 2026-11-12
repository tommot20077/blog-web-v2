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
    @DisplayName("兩個欄位皆非 null → 建立成功並可讀回")
    void constructor_allFieldsPresent_succeeds() {
        AuthUser user = new AuthUser(USER_UUID, Role.AUTHOR);

        assertThat(user.uuid()).isEqualTo(USER_UUID);
        assertThat(user.role()).isEqualTo(Role.AUTHOR);
    }

    @Test
    @DisplayName("Z1：登入身分只有 uuid 與 role 兩個元件，不再攜帶任何資料庫主鍵")
    void recordComponents_areOnlyUuidAndRole() {
        assertThat(AuthUser.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("uuid", "role");
    }

    @Test
    @DisplayName("uuid 為 null → NullPointerException")
    void constructor_nullUuid_throwsNullPointerException() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AuthUser(null, Role.USER))
                .withMessageContaining("uuid");
    }

    @Test
    @DisplayName("role 為 null → NullPointerException")
    void constructor_nullRole_throwsNullPointerException() {
        assertThatNullPointerException()
                .isThrownBy(() -> new AuthUser(USER_UUID, null))
                .withMessageContaining("role");
    }
}
