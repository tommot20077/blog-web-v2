package dowob.xyz.blog.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisKeyConstant} 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("RedisKeyConstant 單元測試")
class RedisKeyConstantTest {

    /** 測試用使用者 uuid */
    private static final UUID USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    @DisplayName("TAG_HOT_KEY 應為 tag:hot")
    void tagHotKey_hasExpectedValue() {
        assertThat(RedisKeyConstant.TAG_HOT_KEY).isEqualTo("tag:hot");
    }

    @Test
    @DisplayName("TAG_AUTOCOMPLETE_KEY 應為 tag:autocomplete")
    void tagAutocompleteKey_hasExpectedValue() {
        assertThat(RedisKeyConstant.TAG_AUTOCOMPLETE_KEY).isEqualTo("tag:autocomplete");
    }

    @Test
    @DisplayName("getTagDetailKey 應回傳 tag:{slug}")
    void getTagDetailKey_returnsTagPrefixedSlug() {
        assertThat(RedisKeyConstant.getTagDetailKey("spring")).isEqualTo("tag:spring");
    }

    @Test
    @DisplayName("SEARCH_HOT_KEY 應為 search:hot")
    void searchHotKey_hasExpectedValue() {
        assertThat(RedisKeyConstant.SEARCH_HOT_KEY).isEqualTo("search:hot");
    }

    @Test
    @DisplayName("SEARCH_HISTORY_PREFIX 應為 search:history:")
    void searchHistoryPrefix_hasExpectedValue() {
        assertThat(RedisKeyConstant.SEARCH_HISTORY_PREFIX).isEqualTo("search:history:");
    }

    @Test
    @DisplayName("getSearchHistoryKey 應回傳 search:history:{userId}")
    void getSearchHistoryKey_returnsHistoryKey() {
        assertThat(RedisKeyConstant.getSearchHistoryKey(42L)).isEqualTo("search:history:42");
    }

    @Test
    @DisplayName("SEARCH_REINDEX_AT_KEY 應為 search:reindex:at")
    void searchReindexAtKey_hasExpectedValue() {
        assertThat(RedisKeyConstant.SEARCH_REINDEX_AT_KEY).isEqualTo("search:reindex:at");
    }

    @Test
    @DisplayName("RECOMMEND_TRENDING_PREFIX 應為 recommend:trending:")
    void recommendTrendingPrefix_hasExpectedValue() {
        assertThat(RedisKeyConstant.RECOMMEND_TRENDING_PREFIX).isEqualTo("recommend:trending:");
    }

    @Test
    @DisplayName("getTrendingKey 應回傳 recommend:trending:{period}")
    void getTrendingKey_returnsTrendingKey() {
        assertThat(RedisKeyConstant.getTrendingKey("24h")).isEqualTo("recommend:trending:24h");
    }

    @Test
    @DisplayName("RECOMMEND_RELATED_PREFIX 應為 recommend:related:")
    void recommendRelatedPrefix_hasExpectedValue() {
        assertThat(RedisKeyConstant.RECOMMEND_RELATED_PREFIX).isEqualTo("recommend:related:");
    }

    @Test
    @DisplayName("getRelatedKey 應回傳 recommend:related:{articleUuid}")
    void getRelatedKey_returnsRelatedKey() {
        assertThat(RedisKeyConstant.getRelatedKey("abc-123")).isEqualTo("recommend:related:abc-123");
    }

    @Test
    @DisplayName("LOCK_TRENDING_REFRESH 應為 lock:trending-refresh")
    void lockTrendingRefresh_hasExpectedValue() {
        assertThat(RedisKeyConstant.LOCK_TRENDING_REFRESH).isEqualTo("lock:trending-refresh");
    }

    @Test
    @DisplayName("FIELD_ID 應為 id（auth hash 的使用者主鍵欄位）")
    void fieldId_hasExpectedValue() {
        assertThat(RedisKeyConstant.FIELD_ID).isEqualTo("id");
    }

    @Test
    @DisplayName("FIELD_ROLE 應為 role")
    void fieldRole_hasExpectedValue() {
        assertThat(RedisKeyConstant.FIELD_ROLE).isEqualTo("role");
    }

    @Test
    @DisplayName("getUserAuthKey 應回傳 user:auth:{userUuid}")
    void getUserAuthKey_returnsAuthKey() {
        assertThat(RedisKeyConstant.getUserAuthKey(USER_UUID)).isEqualTo("user:auth:" + USER_UUID);
    }

    @Test
    @DisplayName("getUserRefreshKey 應回傳 user:refresh:{userUuid}")
    void getUserRefreshKey_returnsRefreshKey() {
        assertThat(RedisKeyConstant.getUserRefreshKey(USER_UUID)).isEqualTo("user:refresh:" + USER_UUID);
    }

    @Test
    @DisplayName("getForgotPwdMinKey 應回傳 rate:forgot-pwd:min:{email}")
    void getForgotPwdMinKey_returnsMinuteRateLimitKey() {
        assertThat(RedisKeyConstant.getForgotPwdMinKey("test@example.com"))
                .isEqualTo("rate:forgot-pwd:min:test@example.com");
    }

    @Test
    @DisplayName("getForgotPwdDayKey 應回傳 rate:forgot-pwd:day:{email}")
    void getForgotPwdDayKey_returnsDayRateLimitKey() {
        assertThat(RedisKeyConstant.getForgotPwdDayKey("test@example.com"))
                .isEqualTo("rate:forgot-pwd:day:test@example.com");
    }

    @Test
    @DisplayName("getLoginFailKey 應回傳 login:fail:{userUuid}")
    void getLoginFailKey_returnsLoginFailKey() {
        assertThat(RedisKeyConstant.getLoginFailKey(USER_UUID)).isEqualTo("login:fail:" + USER_UUID);
    }

    @Test
    @DisplayName("getResendVerifyMinKey 應回傳 rate:resend-verify:min:{email}")
    void getResendVerifyMinKey_returnsMinuteRateLimitKey() {
        assertThat(RedisKeyConstant.getResendVerifyMinKey("user@blog.com"))
                .isEqualTo("rate:resend-verify:min:user@blog.com");
    }

    @Test
    @DisplayName("getResendVerifyDayKey 應回傳 rate:resend-verify:day:{email}")
    void getResendVerifyDayKey_returnsDayRateLimitKey() {
        assertThat(RedisKeyConstant.getResendVerifyDayKey("user@blog.com"))
                .isEqualTo("rate:resend-verify:day:user@blog.com");
    }

    /* =========================================================================
       IP 層級登入/註冊限流（Task 12）
       ========================================================================= */

    @Test
    @DisplayName("LOGIN_IP_PREFIX 應為 auth:login:ip:")
    void loginIpPrefix_hasExpectedValue() {
        assertThat(RedisKeyConstant.LOGIN_IP_PREFIX).isEqualTo("auth:login:ip:");
    }

    @Test
    @DisplayName("getLoginIpKey 應回傳 auth:login:ip:{ip}")
    void getLoginIpKey_returnsLoginIpRateLimitKey() {
        assertThat(RedisKeyConstant.getLoginIpKey("203.0.113.5"))
                .isEqualTo("auth:login:ip:203.0.113.5");
    }

    @Test
    @DisplayName("REGISTER_IP_PREFIX 應為 auth:register:ip:")
    void registerIpPrefix_hasExpectedValue() {
        assertThat(RedisKeyConstant.REGISTER_IP_PREFIX).isEqualTo("auth:register:ip:");
    }

    @Test
    @DisplayName("getRegisterIpKey 應回傳 auth:register:ip:{ip}")
    void getRegisterIpKey_returnsRegisterIpRateLimitKey() {
        assertThat(RedisKeyConstant.getRegisterIpKey("203.0.113.5"))
                .isEqualTo("auth:register:ip:203.0.113.5");
    }

    @Test
    @DisplayName("LOGIN_IP_MAX 應為 20（NAT 友善的較寬上限）")
    void loginIpMax_hasExpectedValue() {
        assertThat(RedisKeyConstant.LOGIN_IP_MAX).isEqualTo(20);
    }

    @Test
    @DisplayName("LOGIN_IP_TTL_MINUTES 應為 15")
    void loginIpTtlMinutes_hasExpectedValue() {
        assertThat(RedisKeyConstant.LOGIN_IP_TTL_MINUTES).isEqualTo(15L);
    }

    @Test
    @DisplayName("REGISTER_IP_MAX 應為 10")
    void registerIpMax_hasExpectedValue() {
        assertThat(RedisKeyConstant.REGISTER_IP_MAX).isEqualTo(10);
    }

    @Test
    @DisplayName("REGISTER_IP_TTL_MINUTES 應為 60（每小時窗口）")
    void registerIpTtlMinutes_hasExpectedValue() {
        assertThat(RedisKeyConstant.REGISTER_IP_TTL_MINUTES).isEqualTo(60L);
    }

    @Test
    @DisplayName("getReconciliationLockKey 應回傳 lock:reconciliation:{module}:{table}")
    void getReconciliationLockKey_containsModuleAndTable() {
        assertThat(RedisKeyConstant.getReconciliationLockKey("article", "articles"))
                .isEqualTo("lock:reconciliation:article:articles");
    }

    @Test
    @DisplayName("getReconciliationCursorKey 應回傳 reconciliation:cursor:{module}:{table}")
    void getReconciliationCursorKey_containsModuleAndTable() {
        assertThat(RedisKeyConstant.getReconciliationCursorKey("article", "articles"))
                .isEqualTo("reconciliation:cursor:article:articles");
    }
}
