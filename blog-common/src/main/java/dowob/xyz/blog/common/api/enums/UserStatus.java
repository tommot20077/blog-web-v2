package dowob.xyz.blog.common.api.enums;

import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.errorcode.UserErrorCode;
import dowob.xyz.blog.common.exception.BusinessException;

/**
 * 用戶狀態枚舉
 *
 * @author Yuan
 * @version 1.0
 */
public enum UserStatus {
    /**
     * 等待驗證
     */
    PENDING_VERIFICATION,

    /**
     * 活躍
     */
    ACTIVE,

    /**
     * 停權
     */
    SUSPENDED,

    /**
     * 已刪除
     */
    DELETED

    ;

    public static UserStatus fromString(String status) {
        if (status == null) {
            throw new BusinessException(CommonErrorCode.REQUEST_PARAM_MISSING);
        }

        for (UserStatus userStatus : UserStatus.values()) {
            if (userStatus.name().equalsIgnoreCase(status)) {
                return userStatus;
            }
        }


        throw new BusinessException(UserErrorCode.INVALID_USER_STATUS);
    }


    public boolean isAvailable() {
        return this == ACTIVE || this == PENDING_VERIFICATION;
    }


}
