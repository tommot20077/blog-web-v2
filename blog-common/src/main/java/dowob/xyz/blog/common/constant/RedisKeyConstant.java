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
     * 用戶 Refresh Token 快取 (String)
     * Key: user:refresh:{userId}
     */
    public static final String USER_REFRESH_KEY_PREFIX = "user:refresh:";

    /**
     * Auth Hash Fields
     */
    public static final String FIELD_VERSION = "version";

    /**
     * Auth Hash 狀態欄位
     */
    public static final String FIELD_STATUS = "status";

    /**
     * 生成用戶認證 Redis Key
     *
     * @param userId 用戶 ID
     * @return Redis Key
     */
    public static String getUserAuthKey(Long userId) {
        return USER_AUTH_KEY_PREFIX + userId;
    }

    /**
     * 生成用戶 Refresh Token Redis Key
     *
     * @param userId 用戶 ID
     * @return Redis Key
     */
    public static String getUserRefreshKey(Long userId) {
        return USER_REFRESH_KEY_PREFIX + userId;
    }
}
