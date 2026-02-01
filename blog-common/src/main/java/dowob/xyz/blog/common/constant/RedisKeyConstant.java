package dowob.xyz.blog.common.constant;

import lombok.experimental.UtilityClass;

/**
 * Redis Key 常量與生成工具
 *
 * @author Yuan
 * @version 1.0
 */
@UtilityClass
public class RedisKeyConstant {

    /**
     * 用戶認證快取 (Hash)
     * Key: user:auth:{userId}
     */
    public static final String USER_AUTH_KEY_PREFIX = "user:auth:";

    /**
     * Auth Hash Fields
     */
    public static final String FIELD_VERSION = "version";
    public static final String FIELD_STATUS = "status";
    public static final String FIELD_IS_ACTIVE = "isActive"; // 兼容舊代碼，建議統一

    /**
     * 生成用戶認證 Redis Key
     *
     * @param userId 用戶ID
     * @return Redis Key
     */
    public static String getUserAuthKey(Long userId) {
        return USER_AUTH_KEY_PREFIX + userId;
    }
}
