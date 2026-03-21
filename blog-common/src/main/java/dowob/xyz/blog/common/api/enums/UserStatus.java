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
     * 封禁
     */
    BANNED,

    /**
     * 已刪除
     */
    DELETED

    ;

    /**
     * 從字串解析用戶狀態枚舉（不區分大小寫）
     *
     * @param status 狀態字串
     * @return 對應的 {@link UserStatus} 枚舉值
     * @throws BusinessException 當 status 為 null 或無法匹配時拋出
     */
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


    /**
     * 判斷用戶是否可用（ACTIVE 或 PENDING_VERIFICATION 狀態為可用）
     *
     * @return 若用戶狀態為 ACTIVE 或 PENDING_VERIFICATION 回傳 true，否則 false
     */
    public boolean isAvailable() {
        return this == ACTIVE || this == PENDING_VERIFICATION;
    }


}
