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
     * 忘記密碼每分鐘限速 Key 前綴 (String)
     * Key: rate:forgot-pwd:min:{email}
     */
    public static final String FORGOT_PWD_MIN_PREFIX = "rate:forgot-pwd:min:";

    /**
     * 忘記密碼每日限速 Key 前綴 (String)
     * Key: rate:forgot-pwd:day:{email}
     */
    public static final String FORGOT_PWD_DAY_PREFIX = "rate:forgot-pwd:day:";

    /**
     * 生成用戶 Refresh Token Redis Key
     *
     * @param userId 用戶 ID
     * @return Redis Key
     */
    public static String getUserRefreshKey(Long userId) {
        return USER_REFRESH_KEY_PREFIX + userId;
    }

    /**
     * 生成忘記密碼每分鐘限速 Redis Key
     *
     * @param email 用戶信箱
     * @return Redis Key
     */
    public static String getForgotPwdMinKey(String email) {
        return FORGOT_PWD_MIN_PREFIX + email;
    }

    /**
     * 生成忘記密碼每日限速 Redis Key
     *
     * @param email 用戶信箱
     * @return Redis Key
     */
    public static String getForgotPwdDayKey(String email) {
        return FORGOT_PWD_DAY_PREFIX + email;
    }

    /**
     * 登入失敗計數 Key 前綴 (String)
     * Key: login:fail:{userId}
     */
    public static final String LOGIN_FAIL_PREFIX = "login:fail:";

    /**
     * 登入失敗最大允許次數，超過此值帳號將被暫時鎖定
     */
    public static final int LOGIN_FAIL_MAX = 5;

    /**
     * 帳號鎖定持續時間（分鐘）
     */
    public static final long LOGIN_FAIL_TTL_MINUTES = 15;

    /**
     * 生成登入失敗計數 Redis Key
     *
     * @param userId 用戶 ID
     * @return Redis Key
     */
    public static String getLoginFailKey(Long userId) {
        return LOGIN_FAIL_PREFIX + userId;
    }

    /**
     * 重發驗證信每分鐘限速 Key 前綴 (String)
     * Key: rate:resend-verify:min:{email}
     */
    public static final String RESEND_VERIFY_MIN_PREFIX = "rate:resend-verify:min:";

    /**
     * 重發驗證信每日限速 Key 前綴 (String)
     * Key: rate:resend-verify:day:{email}
     */
    public static final String RESEND_VERIFY_DAY_PREFIX = "rate:resend-verify:day:";

    /**
     * 生成重發驗證信每分鐘限速 Redis Key
     *
     * @param email 用戶信箱
     * @return Redis Key
     */
    public static String getResendVerifyMinKey(String email) {
        return RESEND_VERIFY_MIN_PREFIX + email;
    }

    /**
     * 生成重發驗證信每日限速 Redis Key
     *
     * @param email 用戶信箱
     * @return Redis Key
     */
    public static String getResendVerifyDayKey(String email) {
        return RESEND_VERIFY_DAY_PREFIX + email;
    }
}
