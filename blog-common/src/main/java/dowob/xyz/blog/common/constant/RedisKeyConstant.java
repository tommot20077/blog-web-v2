package dowob.xyz.blog.common.constant;

import lombok.experimental.UtilityClass;

import java.util.UUID;

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
     * Key: user:auth:{userUuid}
     */
    public static final String USER_AUTH_KEY_PREFIX = "user:auth:";

    /**
     * 用戶 Refresh Token 快取 (String)
     * Key: user:refresh:{userUuid}
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
     * @param userUuid 用戶 UUID
     * @return Redis Key
     */
    public static String getUserAuthKey(UUID userUuid) {
        return USER_AUTH_KEY_PREFIX + userUuid;
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
     * @param userUuid 用戶 UUID
     * @return Redis Key
     */
    public static String getUserRefreshKey(UUID userUuid) {
        return USER_REFRESH_KEY_PREFIX + userUuid;
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
     * Key: login:fail:{userUuid}
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

    /** 用戶認證快取 TTL（天），與 Refresh Token 生命週期對齊 */
    public static final long USER_AUTH_TTL_DAYS = 7;

    /** 搜尋歷史 TTL（天） */
    public static final long SEARCH_HISTORY_TTL_DAYS = 30;

    /** 文章瀏覽計數安全網 TTL（小時），FlushJob 正常清理，此為失敗殘留的保護 */
    public static final long ARTICLE_VIEWS_TTL_HOURS = 24;

    /**
     * 生成登入失敗計數 Redis Key
     *
     * @param userUuid 用戶 UUID
     * @return Redis Key
     */
    public static String getLoginFailKey(UUID userUuid) {
        return LOGIN_FAIL_PREFIX + userUuid;
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
     * 信箱驗證碼 Key 前綴 (String)
     * Key: auth:email-verify:code:{email}
     */
    public static final String EMAIL_VERIFY_CODE_PREFIX = "auth:email-verify:code:";

    /** 信箱驗證碼 TTL（分鐘） */
    public static final long EMAIL_VERIFY_CODE_TTL_MINUTES = 10L;

    /**
     * 信箱驗證碼失敗計數 Key 前綴 (String)
     * Key: auth:email-verify:fail:{email}
     */
    public static final String EMAIL_VERIFY_CODE_FAIL_PREFIX = "auth:email-verify:fail:";

    /** 信箱驗證碼最大允許失敗次數，超過此值將拒絕進一步驗證（防暴力破解） */
    public static final int EMAIL_VERIFY_CODE_MAX_ATTEMPTS = 5;

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

    /**
     * 生成信箱驗證碼 Redis Key
     *
     * @param email 用戶信箱
     * @return Redis Key
     */
    public static String getEmailVerifyCodeKey(String email) {
        return EMAIL_VERIFY_CODE_PREFIX + email;
    }

    /**
     * 生成信箱驗證碼失敗計數 Redis Key
     *
     * @param email 用戶信箱
     * @return Redis Key
     */
    public static String getEmailVerifyCodeFailKey(String email) {
        return EMAIL_VERIFY_CODE_FAIL_PREFIX + email;
    }

    /** ===================== IP 層級登入/註冊限流 ===================== */

    /**
     * 登入 IP 層級限流 Key 前綴 (String)
     * Key: auth:login:ip:{ip}
     * <p>以 client IP 計數登入嘗試，與 {@link #LOGIN_FAIL_PREFIX}（user.id 帳號鎖定）並存，
     * 用於防範跨帳號的分散撞庫與帳號枚舉。</p>
     */
    public static final String LOGIN_IP_PREFIX = "auth:login:ip:";

    /**
     * 註冊 IP 層級限流 Key 前綴 (String)
     * Key: auth:register:ip:{ip}
     */
    public static final String REGISTER_IP_PREFIX = "auth:register:ip:";

    /**
     * 單一 IP 在限流窗口內允許的最大登入嘗試次數。
     * <p>設定較寬（20）以避免 NAT / 反向代理下多個正常用戶共用對外 IP 時被誤鎖。</p>
     */
    public static final int LOGIN_IP_MAX = 20;

    /** 登入 IP 限流窗口（分鐘） */
    public static final long LOGIN_IP_TTL_MINUTES = 15L;

    /**
     * 單一 IP 在限流窗口內允許的最大註冊次數。
     * <p>註冊為低頻操作，採較嚴格上限（10）抑制大量假帳號建立。</p>
     */
    public static final int REGISTER_IP_MAX = 10;

    /** 註冊 IP 限流窗口（分鐘），等同每小時 */
    public static final long REGISTER_IP_TTL_MINUTES = 60L;

    /**
     * 生成登入 IP 層級限流 Redis Key
     *
     * @param ip client IP
     * @return Redis Key
     */
    public static String getLoginIpKey(String ip) {
        return LOGIN_IP_PREFIX + ip;
    }

    /**
     * 生成註冊 IP 層級限流 Redis Key
     *
     * @param ip client IP
     * @return Redis Key
     */
    public static String getRegisterIpKey(String ip) {
        return REGISTER_IP_PREFIX + ip;
    }

    /** ===================== Tag ===================== */

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

    /** ===================== Search ===================== */

    /**
     * 熱門搜尋詞 ZSet Key
     * Key: search:hot
     */
    public static final String SEARCH_HOT_KEY = "search:hot";

    /**
     * 個人搜尋歷史 List Key 前綴
     * Key: search:history:{userUuid}
     */
    public static final String SEARCH_HISTORY_PREFIX = "search:history:";

    /**
     * 生成個人搜尋歷史 Redis Key
     *
     * @param userUuid 用戶 UUID
     * @return Redis Key
     */
    public static String getSearchHistoryKey(UUID userUuid) {
        return SEARCH_HISTORY_PREFIX + userUuid;
    }

    /**
     * 搜尋索引全量重建時間戳 Key (String，值為 ISO-8601 時間字串)
     * Key: search:reindex:at
     * <p>由 {@code SearchServiceImpl.reindexAll()} 於重建完成後寫入，
     * 供 Admin 後台查詢索引狀態時讀取「最後重建時間」；從未重建過時該 Key 不存在。</p>
     */
    public static final String SEARCH_REINDEX_AT_KEY = "search:reindex:at";

    /** ===================== Recommend ===================== */

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

    /**
     * 孤兒校正分散式鎖 Key 前綴
     * Key: lock:reconciliation:{module}:{table}
     */
    public static final String LOCK_RECONCILIATION_PREFIX = "lock:reconciliation:";

    /**
     * 孤兒校正續跑游標 Key 前綴（String，值為最後處理的 UUID）
     * Key: reconciliation:cursor:{module}:{table}
     */
    public static final String RECONCILIATION_CURSOR_PREFIX = "reconciliation:cursor:";

    /**
     * 生成孤兒校正分散式鎖 Key
     *
     * @param module 模組名稱
     * @param table  表名
     * @return Redis Key
     */
    public static String getReconciliationLockKey(String module, String table) {
        return LOCK_RECONCILIATION_PREFIX + module + ":" + table;
    }

    /**
     * 生成孤兒校正續跑游標 Key
     *
     * @param module 模組名稱
     * @param table  表名
     * @return Redis Key
     */
    public static String getReconciliationCursorKey(String module, String table) {
        return RECONCILIATION_CURSOR_PREFIX + module + ":" + table;
    }

    /** ===================== Auth Hash Fields ===================== */

    /**
     * Auth Hash 角色欄位
     */
    public static final String FIELD_ROLE = "role";

    /**
     * Auth Hash 使用者主鍵欄位（過渡欄位，供 {@code AuthUser.id()} 使用；收尾階段移除）
     */
    public static final String FIELD_ID = "id";

    /** ===================== Reading Progress（批 2 / V14）===================== */

    /**
     * 閱讀進度 Hash Key 前綴
     * Key: reading:progress:{userUuid}:{articleUuid}
     * 欄位：progress、lastHeading、updatedAt（使用者送出該進度的 JVM epoch ms，Lua 單調寫入的版本）
     *
     * <p>M2-c 起使用者與文章一律以公開 UUID 識別；舊格式 {@code {Long userId}:{articleUuid}} 的 key 不遷移
     * （無正式環境，TTL 到期自然消失），其 dirty 成員會被 flush job 視為無法解析而移除。</p>
     */
    public static final String READING_PROGRESS_PREFIX = "reading:progress:";

    /**
     * 待 flush dirty Set Key
     * Members: "{userUuid}:{articleUuid}"
     */
    public static final String READING_DIRTY_KEY = "reading:dirty";

    /** Reading progress Redis key TTL（天）*/
    public static final long READING_PROGRESS_TTL_DAYS = 3L;

    /** 視為已讀完的進度門檻（>= 0.95 觸發單調 UPSERT DB，並把快取設成完讀值但不標 dirty、不 DEL hash）*/
    public static final java.math.BigDecimal READING_PROGRESS_COMPLETED_THRESHOLD = new java.math.BigDecimal("0.95");
}
