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
     * 文章瀏覽計數暫存 Key 前綴 (String)
     * Key: article:views:{articleUuid}
     * <p>用於批次累積瀏覽增量，由 {@code ViewCountFlushJob} 定期刷入 DB。</p>
     */
    public static final String ARTICLE_VIEWS_PREFIX = "article:views:";

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

    // ===================== Tag =====================

    /**
     * 熱門標籤 ZSet Key
     * Key: tag:hot
     */
    public static final String TAG_HOT_KEY = "tag:hot";

    /**
     * 標籤自動補全 ZSet Key
     * Key: tag:autocomplete
     */
    public static final String TAG_AUTOCOMPLETE_KEY = "tag:autocomplete";

    /**
     * 標籤詳情 Hash Key 前綴
     * Key: tag:{slug}
     */
    public static final String TAG_DETAIL_PREFIX = "tag:";

    /**
     * 生成標籤詳情 Redis Key
     *
     * @param slug 標籤 slug
     * @return Redis Key
     */
    public static String getTagDetailKey(String slug) {
        return TAG_DETAIL_PREFIX + slug;
    }

    // ===================== Search =====================

    /**
     * 熱門搜尋詞 ZSet Key
     * Key: search:hot
     */
    public static final String SEARCH_HOT_KEY = "search:hot";

    /**
     * 個人搜尋歷史 List Key 前綴
     * Key: search:history:{userId}
     */
    public static final String SEARCH_HISTORY_PREFIX = "search:history:";

    /**
     * 生成個人搜尋歷史 Redis Key
     *
     * @param userId 用戶 ID
     * @return Redis Key
     */
    public static String getSearchHistoryKey(Long userId) {
        return SEARCH_HISTORY_PREFIX + userId;
    }

    // ===================== Recommend =====================

    /**
     * 熱門排行 ZSet Key 前綴
     * Key: recommend:trending:{period}
     */
    public static final String RECOMMEND_TRENDING_PREFIX = "recommend:trending:";

    /**
     * 生成熱門排行 Redis Key
     *
     * @param period 排行週期（如 24h、7d、30d）
     * @return Redis Key
     */
    public static String getTrendingKey(String period) {
        return RECOMMEND_TRENDING_PREFIX + period;
    }

    /**
     * 相關文章推薦快取 Key 前綴
     * Key: recommend:related:{articleUuid}
     */
    public static final String RECOMMEND_RELATED_PREFIX = "recommend:related:";

    /**
     * 生成相關文章推薦快取 Redis Key
     *
     * @param articleUuid 文章 UUID 字串
     * @return Redis Key
     */
    public static String getRelatedKey(String articleUuid) {
        return RECOMMEND_RELATED_PREFIX + articleUuid;
    }

    /**
     * 熱門排行更新分散式鎖 Key
     * Key: lock:trending-refresh
     */
    public static final String LOCK_TRENDING_REFRESH = "lock:trending-refresh";

    // ===================== Auth Hash Fields =====================

    /**
     * Auth Hash 角色欄位
     */
    public static final String FIELD_ROLE = "role";
}
