package dowob.xyz.blog.module.user.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link PublicSocialLinks} 單元測試：公開輸出時對已儲存 JSON 的寬鬆解析。
 *
 * <p>寫入端的嚴格規則在 {@code SafeSocialLinksValidator}（SEC-10，value 為帳號名稱）；本類驗證讀取端：
 * 不合格的值略過、永不拋例外，github／twitter／linkedin 三個 key 恆存在。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class PublicSocialLinksTest {

    @Test
    @DisplayName("三個已知平台都有合格值 → 原樣輸出（帳號名稱，不組網址）")
    void from_allKnownPlatforms_returnsHandles() {
        Map<String, String> result = PublicSocialLinks.from(
                "{\"github\":\"yuan-dev\",\"twitter\":\"yuan_dev\",\"linkedin\":\"yuan-lin\"}");

        assertThat(result).containsEntry("github", "yuan-dev")
                .containsEntry("twitter", "yuan_dev")
                .containsEntry("linkedin", "yuan-lin");
    }

    @Test
    @DisplayName("未填的已知平台 key 仍存在且為 null（契約的 socialLinks 三個 key 恆存在）")
    void from_missingKnownPlatforms_keysPresentWithNull() {
        Map<String, String> result = PublicSocialLinks.from("{\"github\":\"yuan-dev\"}");

        assertThat(result).containsEntry("github", "yuan-dev");
        assertThat(result).containsKeys("twitter", "linkedin");
        assertThat(result.get("twitter")).isNull();
        assertThat(result.get("linkedin")).isNull();
    }

    @Test
    @DisplayName("空字串代表未填 → 該 key 輸出 null")
    void from_emptyStringValue_isNull() {
        Map<String, String> result = PublicSocialLinks.from("{\"github\":\"\",\"twitter\":\"x\"}");

        assertThat(result.get("github")).isNull();
        assertThat(result).containsEntry("twitter", "x");
    }

    @Test
    @DisplayName("非三個平台的 key 只要符合 [a-z0-9_]{1,32} 就照實輸出")
    void from_otherValidKeys_arePassedThrough() {
        Map<String, String> result = PublicSocialLinks.from("{\"mastodon\":\"yuan\",\"bluesky_1\":\"yuan.dev\"}");

        assertThat(result).containsEntry("mastodon", "yuan").containsEntry("bluesky_1", "yuan.dev");
    }

    @Test
    @DisplayName("不合格的 key（大寫、連字號、過長）略過，不影響其他項目")
    void from_invalidKeys_areSkipped() {
        String longKey = "a".repeat(33);
        Map<String, String> result = PublicSocialLinks.from(
                "{\"GitHub\":\"x\",\"my-site\":\"x\",\"" + longKey + "\":\"x\",\"github\":\"ok\"}");

        assertThat(result).doesNotContainKeys("GitHub", "my-site", longKey).containsEntry("github", "ok");
    }

    @Test
    @DisplayName("不合格的 value（網址、scheme、空白、HTML、雙 @）略過 → 該 key 為 null")
    void from_unsafeValues_areSkipped() {
        Map<String, String> result = PublicSocialLinks.from("""
                {"github":"https://github.com/x","twitter":"javascript:alert(1)","linkedin":"a b",
                 "k1":"<script>","k2":"@@x","k3":"@","k4":"a/b","k5":"ａ：ｂ"}""");

        assertThat(result.get("github")).isNull();
        assertThat(result.get("twitter")).isNull();
        assertThat(result.get("linkedin")).isNull();
        assertThat(result).doesNotContainKeys("k1", "k2", "k3", "k4", "k5");
    }

    @Test
    @DisplayName("非字串的 value（數字、null、物件、陣列）略過")
    void from_nonStringValues_areSkipped() {
        Map<String, String> result = PublicSocialLinks.from(
                "{\"github\":123,\"twitter\":null,\"linkedin\":{\"a\":1},\"k1\":[\"x\"],\"k2\":true}");

        assertThat(result.get("github")).isNull();
        assertThat(result.get("twitter")).isNull();
        assertThat(result.get("linkedin")).isNull();
        assertThat(result).doesNotContainKeys("k1", "k2");
    }

    @Test
    @DisplayName("單一前導 @ 保留（相容 Twitter 寫法，儲存時不剝除）")
    void from_leadingAt_isKept() {
        assertThat(PublicSocialLinks.from("{\"twitter\":\"@yuan\"}")).containsEntry("twitter", "@yuan");
    }

    @Test
    @DisplayName("null、空白、亂碼、非物件 JSON、超長字串 → 永不拋例外，三個已知 key 全為 null")
    void from_garbageInput_neverThrowsAndReturnsNullKnownKeys() {
        String oversized = "{\"github\":\"" + "a".repeat(3000) + "\"}";
        for (String input : new String[]{null, "", "   ", "{{{", "[]", "\"x\"", "123", "null", "{\"github\":", oversized}) {
            assertThatCode(() -> PublicSocialLinks.from(input)).doesNotThrowAnyException();
            Map<String, String> result = PublicSocialLinks.from(input);
            assertThat(result).containsKeys("github", "twitter", "linkedin");
            assertThat(result.values()).containsOnlyNulls();
        }
    }

    @Test
    @DisplayName("合格項目最多輸出 10 筆（與寫入端上限一致，防止舊資料放大回應）")
    void from_moreThanTenValidEntries_capsAtTen() {
        String json = IntStream.range(0, 15)
                .mapToObj(i -> "\"k" + i + "\":\"v" + i + "\"")
                .collect(Collectors.joining(",", "{", "}"));

        long nonNull = PublicSocialLinks.from(json).values().stream().filter(v -> v != null).count();

        assertThat(nonNull).isEqualTo(10);
    }
}
