package dowob.xyz.blog.common.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RedisKeyConstant} 單元測試
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("RedisKeyConstant 單元測試")
class RedisKeyConstantTest {

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
    @DisplayName("FIELD_ROLE 應為 role")
    void fieldRole_hasExpectedValue() {
        assertThat(RedisKeyConstant.FIELD_ROLE).isEqualTo("role");
    }
}
