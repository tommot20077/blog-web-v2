package dowob.xyz.blog.common.api.enums;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 用戶角色枚舉
 *
 * @author Yuan
 * @version 1.0
 */


@Getter
@RequiredArgsConstructor
public enum Role {

    /**
     * 一般用戶
     */
    USER("ROLE_USER"),

    /**
     * 作者 (可發布文章)
     */
    AUTHOR("ROLE_AUTHOR"),

    /**
     * 管理員
     */
    ADMIN("ROLE_ADMIN")

    ;
    private final String roleName = this.name();
    private final String springSecurityRole;

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
