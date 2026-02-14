package dowob.xyz.blog.common.api.enums;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import lombok.Getter;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 用戶角色枚舉
 *
 * <p>
 * 定義系統中所有角色，並靜態對應各角色擁有的 {@link Permission} 集合。
 * 角色階層：ADMIN ⊇ AUTHOR ⊇ USER。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Getter
public enum Role {

    /**
     * 一般用戶：可發表留言與刪除自己的留言
     */
    USER("ROLE_USER", EnumSet.of(
            Permission.COMMENT_WRITE,
            Permission.COMMENT_DELETE
    )),

    /**
     * 作者：繼承 USER 所有權限，並可進行文章操作與檔案上傳
     */
    AUTHOR("ROLE_AUTHOR", EnumSet.of(
            Permission.COMMENT_WRITE,
            Permission.COMMENT_DELETE,
            Permission.ARTICLE_CREATE,
            Permission.ARTICLE_EDIT,
            Permission.ARTICLE_DELETE,
            Permission.ARTICLE_PIN,
            Permission.FILE_UPLOAD
    )),

    /**
     * 管理員：擁有所有權限
     */
    ADMIN("ROLE_ADMIN", EnumSet.allOf(Permission.class))

    ;

    /**
     * 角色名稱（等同 {@link Enum#name()}）
     */
    private final String roleName = this.name();

    /**
     * Spring Security 識別用的角色字串，格式為 {@code ROLE_XXX}
     */
    private final String springSecurityRole;

    /**
     * 此角色擁有的靜態權限集合（不可變）
     */
    private final Set<Permission> permissions;

    /**
     * 建構子
     *
     * @param springSecurityRole Spring Security 識別用角色字串
     * @param permissions        此角色擁有的權限集合
     */
    Role(String springSecurityRole, Set<Permission> permissions) {
        this.springSecurityRole = springSecurityRole;
        this.permissions = Collections.unmodifiableSet(permissions);
    }

    /**
     * 判斷此角色是否擁有指定權限
     *
     * @param permission 要檢查的權限，不可為 null
     * @return 若擁有此權限則回傳 {@code true}，否則 {@code false}
     * @throws BusinessException 當 permission 為 null 時拋出
     */
    public boolean hasPermission(Permission permission) {
        if (permission == null) {
            throw new BusinessException(CommonErrorCode.REQUEST_PARAM_MISSING);
        }
        return permissions.contains(permission);
    }

    /**
     * 根據角色名稱（枚舉 name）查找對應角色
     *
     * @param roleName 角色名稱字串（如 {@code "USER"}）
     * @return 對應的 {@link Role}
     * @throws BusinessException 找不到時拋出
     */
    public static Role fromRoleName(String roleName) {
        if (roleName == null) {
            throw new BusinessException(CommonErrorCode.REQUEST_PARAM_MISSING);
        }

        for (Role role : Role.values()) {
            if (role.getRoleName().equals(roleName)) {
                return role;
            }
        }

        throw new BusinessException(UserErrorCode.INVALID_USER_ROLE);
    }

    /**
     * 根據 Spring Security 角色字串（如 {@code "ROLE_AUTHOR"}）查找對應角色
     *
     * @param springSecurityRole Spring Security 格式角色字串
     * @return 對應的 {@link Role}
     * @throws BusinessException 找不到時拋出
     */
    public static Role fromSpringSecurityRole(String springSecurityRole) {
        if (springSecurityRole == null) {
            throw new BusinessException(CommonErrorCode.REQUEST_PARAM_MISSING);
        }

        for (Role role : Role.values()) {
            if (role.getSpringSecurityRole().equals(springSecurityRole)) {
                return role;
            }
        }

        throw new BusinessException(UserErrorCode.INVALID_USER_ROLE);
    }
}
