package dowob.xyz.blog.common.api.enums;

import dowob.xyz.blog.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Role 靜態工廠方法單元測試
 *
 * <p>驗證 {@code fromRoleName()} 與 {@code fromSpringSecurityRole()} 的解析與邊界行為。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Role 靜態工廠方法測試")
class RoleTest {

    @Nested
    @DisplayName("fromRoleName() 解析測試")
    class FromRoleNameTest {

        @Test
        @DisplayName("'USER' 應回傳 Role.USER")
        void fromRoleName_user_returnsUser() {
            assertThat(Role.fromRoleName("USER")).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("'AUTHOR' 應回傳 Role.AUTHOR")
        void fromRoleName_author_returnsAuthor() {
            assertThat(Role.fromRoleName("AUTHOR")).isEqualTo(Role.AUTHOR);
        }

        @Test
        @DisplayName("'ADMIN' 應回傳 Role.ADMIN")
        void fromRoleName_admin_returnsAdmin() {
            assertThat(Role.fromRoleName("ADMIN")).isEqualTo(Role.ADMIN);
        }

        @Test
        @DisplayName("null 應拋出 BusinessException")
        void fromRoleName_null_throwsBusinessException() {
            assertThatThrownBy(() -> Role.fromRoleName(null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("不存在的角色 'SUPERUSER' 應拋出 BusinessException")
        void fromRoleName_unknownRole_throwsBusinessException() {
            assertThatThrownBy(() -> Role.fromRoleName("SUPERUSER"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("小寫 'user' 不符合精確比對，應拋出 BusinessException")
        void fromRoleName_lowercaseUser_throwsBusinessException() {
            /** fromRoleName 使用 equals()（非 equalsIgnoreCase()），應嚴格匹配 */
            assertThatThrownBy(() -> Role.fromRoleName("user"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("空字串應拋出 BusinessException")
        void fromRoleName_emptyString_throwsBusinessException() {
            assertThatThrownBy(() -> Role.fromRoleName(""))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("fromSpringSecurityRole() 解析測試")
    class FromSpringSecurityRoleTest {

        @Test
        @DisplayName("'ROLE_USER' 應回傳 Role.USER")
        void fromSpringSecurityRole_roleUser_returnsUser() {
            assertThat(Role.fromSpringSecurityRole("ROLE_USER")).isEqualTo(Role.USER);
        }

        @Test
        @DisplayName("'ROLE_AUTHOR' 應回傳 Role.AUTHOR")
        void fromSpringSecurityRole_roleAuthor_returnsAuthor() {
            assertThat(Role.fromSpringSecurityRole("ROLE_AUTHOR")).isEqualTo(Role.AUTHOR);
        }

        @Test
        @DisplayName("'ROLE_ADMIN' 應回傳 Role.ADMIN")
        void fromSpringSecurityRole_roleAdmin_returnsAdmin() {
            assertThat(Role.fromSpringSecurityRole("ROLE_ADMIN")).isEqualTo(Role.ADMIN);
        }

        @Test
        @DisplayName("null 應拋出 BusinessException")
        void fromSpringSecurityRole_null_throwsBusinessException() {
            assertThatThrownBy(() -> Role.fromSpringSecurityRole(null))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("不存在的 'ROLE_UNKNOWN' 應拋出 BusinessException")
        void fromSpringSecurityRole_unknownRole_throwsBusinessException() {
            assertThatThrownBy(() -> Role.fromSpringSecurityRole("ROLE_UNKNOWN"))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("未加 'ROLE_' 前綴的 'ADMIN' 應拋出 BusinessException")
        void fromSpringSecurityRole_withoutRolePrefix_throwsBusinessException() {
            assertThatThrownBy(() -> Role.fromSpringSecurityRole("ADMIN"))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("getRoleName() / getSpringSecurityRole() 欄位驗證")
    class FieldValidationTest {

        @Test
        @DisplayName("USER.getRoleName() 應為 'USER'")
        void user_getRoleName_isUser() {
            assertThat(Role.USER.getRoleName()).isEqualTo("USER");
        }

        @Test
        @DisplayName("AUTHOR.getSpringSecurityRole() 應為 'ROLE_AUTHOR'")
        void author_getSpringSecurityRole_isRoleAuthor() {
            assertThat(Role.AUTHOR.getSpringSecurityRole()).isEqualTo("ROLE_AUTHOR");
        }

        @Test
        @DisplayName("ADMIN.getSpringSecurityRole() 應為 'ROLE_ADMIN'")
        void admin_getSpringSecurityRole_isRoleAdmin() {
            assertThat(Role.ADMIN.getSpringSecurityRole()).isEqualTo("ROLE_ADMIN");
        }
    }
}
