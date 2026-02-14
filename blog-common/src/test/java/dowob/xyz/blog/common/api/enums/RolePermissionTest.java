package dowob.xyz.blog.common.api.enums;

import dowob.xyz.blog.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Role → Permission 對應邏輯單元測試
 *
 * <p>
 * 依 plan2.md §7.2 驗證各角色擁有正確的 12 個細粒度權限集合，
 * 以及 {@code hasPermission()} 的邊界行為。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Role 權限對應測試（12-Permission 模型）")
class RolePermissionTest {

    /** ===== USER 角色測試 ===== */

    @Test
    @DisplayName("USER 擁有 COMMENT_WRITE 權限")
    void user_hasCommentWrite() {
        assertThat(Role.USER.hasPermission(Permission.COMMENT_WRITE)).isTrue();
    }

    @Test
    @DisplayName("USER 擁有 COMMENT_DELETE 權限")
    void user_hasCommentDelete() {
        assertThat(Role.USER.hasPermission(Permission.COMMENT_DELETE)).isTrue();
    }

    @Test
    @DisplayName("USER 不擁有 ARTICLE_CREATE 權限")
    void user_doesNotHaveArticleCreate() {
        assertThat(Role.USER.hasPermission(Permission.ARTICLE_CREATE)).isFalse();
    }

    @Test
    @DisplayName("USER 不擁有 FILE_UPLOAD 權限")
    void user_doesNotHaveFileUpload() {
        assertThat(Role.USER.hasPermission(Permission.FILE_UPLOAD)).isFalse();
    }

    @Test
    @DisplayName("USER 不擁有任何 ADMIN 專屬權限")
    void user_doesNotHaveAdminPermissions() {
        assertThat(Role.USER.hasPermission(Permission.TAG_MANAGE)).isFalse();
        assertThat(Role.USER.hasPermission(Permission.USER_MANAGE)).isFalse();
        assertThat(Role.USER.hasPermission(Permission.USER_BAN)).isFalse();
        assertThat(Role.USER.hasPermission(Permission.SYSTEM_CONFIG)).isFalse();
        assertThat(Role.USER.hasPermission(Permission.SYSTEM_ANNOUNCE)).isFalse();
    }

    /** ===== AUTHOR 角色測試 ===== */

    @Test
    @DisplayName("AUTHOR 繼承 USER 的 COMMENT_WRITE、COMMENT_DELETE 權限")
    void author_inheritsUserPermissions() {
        assertThat(Role.AUTHOR.hasPermission(Permission.COMMENT_WRITE)).isTrue();
        assertThat(Role.AUTHOR.hasPermission(Permission.COMMENT_DELETE)).isTrue();
    }

    @Test
    @DisplayName("AUTHOR 擁有 ARTICLE_CREATE、ARTICLE_EDIT、ARTICLE_DELETE、ARTICLE_PIN 權限")
    void author_hasArticlePermissions() {
        assertThat(Role.AUTHOR.hasPermission(Permission.ARTICLE_CREATE)).isTrue();
        assertThat(Role.AUTHOR.hasPermission(Permission.ARTICLE_EDIT)).isTrue();
        assertThat(Role.AUTHOR.hasPermission(Permission.ARTICLE_DELETE)).isTrue();
        assertThat(Role.AUTHOR.hasPermission(Permission.ARTICLE_PIN)).isTrue();
    }

    @Test
    @DisplayName("AUTHOR 擁有 FILE_UPLOAD 權限")
    void author_hasFileUpload() {
        assertThat(Role.AUTHOR.hasPermission(Permission.FILE_UPLOAD)).isTrue();
    }

    @Test
    @DisplayName("AUTHOR 不擁有 ADMIN 專屬權限")
    void author_doesNotHaveAdminPermissions() {
        assertThat(Role.AUTHOR.hasPermission(Permission.TAG_MANAGE)).isFalse();
        assertThat(Role.AUTHOR.hasPermission(Permission.USER_MANAGE)).isFalse();
        assertThat(Role.AUTHOR.hasPermission(Permission.USER_BAN)).isFalse();
        assertThat(Role.AUTHOR.hasPermission(Permission.SYSTEM_CONFIG)).isFalse();
        assertThat(Role.AUTHOR.hasPermission(Permission.SYSTEM_ANNOUNCE)).isFalse();
    }

    /** ===== ADMIN 角色測試 ===== */

    @Test
    @DisplayName("ADMIN 擁有全部 12 個 Permission")
    void admin_hasAllPermissions() {
        for (Permission permission : Permission.values()) {
            assertThat(Role.ADMIN.hasPermission(permission))
                    .as("ADMIN 應擁有 %s", permission)
                    .isTrue();
        }
    }

    /** ===== 邊界條件 ===== */

    @Test
    @DisplayName("hasPermission(null) 應拋出 BusinessException")
    void hasPermission_null_throwsBusinessException() {
        assertThatThrownBy(() -> Role.USER.hasPermission(null))
                .isInstanceOf(BusinessException.class);
    }
}
