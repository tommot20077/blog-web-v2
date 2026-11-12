package dowob.xyz.blog.module.user.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SafeSocialLinksValidator} 單元測試（SEC-10，A 案：value 為帳號名稱而非網址）
 *
 * <p>規則：合法 JSON object；項目數 ≤ 10；key 符合 {@code [a-z0-9_]{1,32}}；value 為字串、長度 ≤ 100，
 * 不含控制字元／空白／{@code /}／{@code :}（因此不可能構成任何 scheme）；空字串代表未填，允許；
 * 一個前導 {@code @} 允許（相容 Twitter 寫法，儲存時不剝除）；JSON null value 拒絕。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class SafeSocialLinksValidatorTest {

    /**
     * 建立 n 個項目的 JSON object。
     */
    private static String objectWithEntries(int n) {
        return IntStream.range(0, n)
                .mapToObj(i -> "\"k" + i + "\":\"v" + i + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null 與空字串（清除）→ 通過")
    void nullOrEmpty_isAllowed(String json) {
        assertThat(SafeSocialLinksValidator.isSafe(json)).isTrue();
    }

    @Test
    @DisplayName("前端 E2E 實際送出的格式（myhandle／mytwitter／mylinkedin）→ 通過")
    void frontendE2ePayload_isAccepted() {
        assertThat(SafeSocialLinksValidator.isSafe(
                "{\"github\":\"myhandle\",\"twitter\":\"mytwitter\",\"linkedin\":\"mylinkedin\"}")).isTrue();
    }

    @Test
    @DisplayName("前端只填一個平台、其餘為空字串（JSON.stringify 的實際輸出）→ 通過")
    void frontendPartiallyFilledPayload_isAccepted() {
        assertThat(SafeSocialLinksValidator.isSafe(
                "{\"github\":\"\",\"twitter\":\"\",\"linkedin\":\"\"}")).isTrue();
        assertThat(SafeSocialLinksValidator.isSafe(
                "{\"github\":\"octocat\",\"twitter\":\"\",\"linkedin\":\"\"}")).isTrue();
    }

    @Test
    @DisplayName("Swagger 範例 {\"twitter\":\"@user\"}：單一前導 @ → 通過")
    void leadingAt_isAccepted() {
        assertThat(SafeSocialLinksValidator.isSafe("{\"twitter\":\"@user\"}")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"twitter\":\"@\"}",
            "{\"twitter\":\"@@user\"}",
            "{\"twitter\":\"us@er\"}",
            "{\"twitter\":\"user@\"}"
    })
    @DisplayName("@ 只允許單一前導且其後須有內容（單獨 @、重複 @、中間／結尾 @）→ 拒絕")
    void misplacedAt_isRejected(String json) {
        assertThat(SafeSocialLinksValidator.isSafe(json)).isFalse();
    }

    @Test
    @DisplayName("空 object {} → 通過；0 個項目")
    void emptyObject_isAccepted() {
        assertThat(SafeSocialLinksValidator.isSafe("{}")).isTrue();
    }

    @Test
    @DisplayName("非 ASCII 帳號名稱（中文）→ 通過")
    void nonAsciiHandle_isAccepted() {
        assertThat(SafeSocialLinksValidator.isSafe("{\"linkedin\":\"王小明\"}")).isTrue();
    }

    @Test
    @DisplayName("項目數 10 → 通過；11 → 拒絕")
    void entryCountLimit() {
        assertThat(SafeSocialLinksValidator.isSafe(objectWithEntries(10))).isTrue();
        assertThat(SafeSocialLinksValidator.isSafe(objectWithEntries(11))).isFalse();
    }

    @Test
    @DisplayName("key 長度 32 → 通過；33 → 拒絕")
    void keyLengthLimit() {
        assertThat(SafeSocialLinksValidator.isSafe("{\"" + "a".repeat(32) + "\":\"x\"}")).isTrue();
        assertThat(SafeSocialLinksValidator.isSafe("{\"" + "a".repeat(33) + "\":\"x\"}")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"\":\"x\"}",
            "{\"GitHub\":\"x\"}",
            "{\"git-hub\":\"x\"}",
            "{\"git hub\":\"x\"}",
            "{\"git.hub\":\"x\"}",
            "{\"__proto__x\\n\":\"x\"}",
            "{\"推特\":\"x\"}"
    })
    @DisplayName("key 不符 [a-z0-9_]{1,32}（空、大寫、連字號、空白、點、換行、非 ASCII）→ 拒絕")
    void invalidKeys_areRejected(String json) {
        assertThat(SafeSocialLinksValidator.isSafe(json)).isFalse();
    }

    @Test
    @DisplayName("value 長度 100 → 通過；101 → 拒絕")
    void valueLengthLimit() {
        assertThat(SafeSocialLinksValidator.isSafe("{\"github\":\"" + "a".repeat(100) + "\"}")).isTrue();
        assertThat(SafeSocialLinksValidator.isSafe("{\"github\":\"" + "a".repeat(101) + "\"}")).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"github\":\"javascript:alert(1)\"}",
            "{\"github\":\"JaVaScRiPt:alert(1)\"}",
            "{\"github\":\"data:text/html,x\"}",
            "{\"github\":\"https://github.com/octocat\"}",
            "{\"github\":\"github.com/octocat\"}",
            "{\"github\":\"//evil.com\"}",
            "{\"github\":\"a:b\"}",
            "{\"github\":\"a/b\"}",
            "{\"github\":\"\\uff1a\"}",
            "{\"github\":\"javascript\\uff1aalert(1)\"}",
            "{\"github\":\"a\\uff0fb\"}"
    })
    @DisplayName("value 含 : 或 /（含全形 ：／，NFKC 正規化後）→ 拒絕，因此不可能構成 scheme 或網址")
    void valuesWithSchemeOrPathCharacters_areRejected(String json) {
        assertThat(SafeSocialLinksValidator.isSafe(json)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"github\":\"my handle\"}",
            "{\"github\":\" handle\"}",
            "{\"github\":\"handle \"}",
            "{\"github\":\"a\\tb\"}",
            "{\"github\":\"a\\nb\"}",
            "{\"github\":\"a\\rb\"}",
            "{\"github\":\"a\\u0000b\"}",
            "{\"github\":\"a\\u0007b\"}",
            "{\"github\":\"a\\u00a0b\"}",
            "{\"github\":\"a\\u2003b\"}",
            "{\"github\":\" \"}"
    })
    @DisplayName("value 含空白或控制字元（space、tab、換行、NUL、NBSP、Unicode 空白）→ 拒絕")
    void valuesWithWhitespaceOrControl_areRejected(String json) {
        assertThat(SafeSocialLinksValidator.isSafe(json)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"github\":\"a\\\\b\"}",
            "{\"github\":\"<script>\"}",
            "{\"github\":\"a>b\"}",
            "{\"github\":\"a\\\"b\"}"
    })
    @DisplayName("value 含反斜線或 HTML 敏感字元（\\ < > \"）→ 拒絕")
    void valuesWithHtmlSensitiveCharacters_areRejected(String json) {
        assertThat(SafeSocialLinksValidator.isSafe(json)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"github\":123}",
            "{\"github\":true}",
            "{\"github\":1.5}",
            "{\"github\":{\"a\":\"b\"}}",
            "{\"github\":[\"a\"]}",
            "{\"github\":null}"
    })
    @DisplayName("非字串 value（數字、布林、物件、陣列）與 JSON null → 拒絕（未填請用空字串）")
    void nonStringValuesAndNull_areRejected(String json) {
        assertThat(SafeSocialLinksValidator.isSafe(json)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[\"a\"]",
            "\"text\"",
            "123",
            "true",
            "null",
            "not json",
            "{",
            "{\"github\":\"a\"",
            "{'github':'a'}",
            "{\"github\":\"a\"} trailing",
            "{\"github\":\"a\"}{\"x\":\"y\"}",
            "   "
    })
    @DisplayName("非 JSON object（陣列、純量、null、壞掉的 JSON、尾隨垃圾、純空白）→ 拒絕")
    void nonObjectOrMalformedJson_isRejected(String json) {
        assertThat(SafeSocialLinksValidator.isSafe(json)).isFalse();
    }

    @Test
    @DisplayName("重複 key（{\"a\":\"x\",\"a\":\"y\"}）→ 拒絕，避免解析器差異造成繞過")
    void duplicateKeys_areRejected() {
        assertThat(SafeSocialLinksValidator.isSafe("{\"github\":\"x\",\"github\":\"y\"}")).isFalse();
    }

    @Test
    @DisplayName("原始 JSON 過長（> 2000 字元）→ 在解析前直接拒絕")
    void oversizedRawJson_isRejected() {
        String padded = "{\"github\":\"a\"}" + " ".repeat(2000);
        assertThat(SafeSocialLinksValidator.isSafe(padded)).isFalse();
    }
}
