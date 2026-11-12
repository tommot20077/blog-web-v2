package dowob.xyz.blog.module.user.validation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SafeHttpUrlValidator} 單元測試（SEC-10）
 *
 * <p>驗證個人資料 URL 欄位的協定白名單：只允許 http/https 的絕對網址，
 * 擋下 javascript:／data:／vbscript:、相對路徑、protocol-relative、空白與控制字元混淆、編碼混淆與超長。</p>
 *
 * <p><b>protocol-relative（{@code //evil.com}）判定</b>：視為相對 URL 拒絕。它雖然不是 script 協定，
 * 但協定由頁面決定、目的地任意，且個人資料欄位本來就該是明確的絕對網址。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class SafeHttpUrlValidatorTest {

    /** 網站欄位長度上限（對應 users.website VARCHAR(255)） */
    private static final int MAX = 255;

    /**
     * 以預設設定（不允許檔案路徑）判斷。
     */
    private static boolean safe(String value) {
        return SafeHttpUrlValidator.isSafe(value, MAX, false);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null 與空字串視為清除欄位 → 通過")
    void nullOrEmpty_isAllowed(String value) {
        assertThat(safe(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://example.com",
            "https://example.com",
            "HTTP://EXAMPLE.COM",
            "HtTpS://Example.com/Path",
            "https://example.com:8443/a/b",
            "https://example.com/a/b?x=1&y=%E4%B8%AD#frag",
            "https://cdn.example.com/avatar.png",
            "http://localhost:8080/x",
            "http://[::1]:8080/x",
            "https://münchen.example/"
    })
    @DisplayName("合法 http/https 絕對網址（含大寫、port、path、query、IPv6、IDN）→ 通過")
    void httpAndHttps_areAllowed(String value) {
        assertThat(safe(value)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "javascript:alert(1)",
            "JaVaScRiPt:alert(1)",
            "JAVASCRIPT:alert(1)",
            "data:text/html,<script>alert(1)</script>",
            "DATA:image/svg+xml;base64,PHN2Zz4=",
            "vbscript:msgbox(1)",
            "ftp://example.com/file",
            "file:///etc/passwd",
            "mailto:a@b.com",
            "blob:https://example.com/uuid"
    })
    @DisplayName("非 http/https 協定（javascript／data／vbscript 等，含大小寫混用）→ 拒絕")
    void dangerousSchemes_areRejected(String value) {
        assertThat(safe(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/avatar.png",
            "avatar.png",
            "../etc/passwd",
            "example.com",
            "www.example.com/path",
            "//evil.com",
            "//evil.com/x.png",
            "/api/v1/files/11111111-2222-3333-4444-555555555555/content"
    })
    @DisplayName("相對路徑與 protocol-relative（//host）→ 拒絕（預設不允許檔案路徑）")
    void relativeUrls_areRejected(String value) {
        assertThat(safe(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            " https://example.com",
            "https://example.com ",
            "\thttps://example.com",
            "\nhttps://example.com",
            "https://example.com\n",
            "https://exa mple.com",
            "https://example.com/a b",
            "java\tscript:alert(1)",
            "java\nscript:alert(1)",
            "java\rscript:alert(1)",
            "java\u0000script:alert(1)",
            "\u0001javascript:alert(1)",
            "\u00a0https://example.com",
            "\u2003https://example.com",
            "https://example.com/\u200b",
            " "
    })
    @DisplayName("前導/結尾空白、tab、換行、NUL、Unicode 空白等混淆 → 拒絕")
    void whitespaceAndControlCharacters_areRejected(String value) {
        assertThat(safe(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "%6A%61%76%61%73%63%72%69%70%74:alert(1)",
            "%6Aavascript:alert(1)",
            "http%3A//evil.com",
            "https%3A%2F%2Fevil.com",
            "&#106;avascript:alert(1)",
            "\\\\evil.com",
            "https:\\\\evil.com"
    })
    @DisplayName("百分比編碼／HTML 實體／反斜線混淆 → 拒絕")
    void encodingObfuscation_isRejected(String value) {
        assertThat(safe(value)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://",
            "http:///path",
            "http:example.com",
            "https:",
            "https://[::1",
            "https://user:pass@example.com",
            "https://google.com@evil.com"
    })
    @DisplayName("無 host、opaque URI、無法解析、含 userinfo 的網址 → 拒絕")
    void malformedOrUserInfo_isRejected(String value) {
        assertThat(safe(value)).isFalse();
    }

    @Test
    @DisplayName("剛好等於長度上限 → 通過；超過 1 字元 → 拒絕")
    void lengthBoundary() {
        String prefix = "https://example.com/";
        String atLimit = prefix + "a".repeat(MAX - prefix.length());
        assertThat(atLimit).hasSize(MAX);
        assertThat(safe(atLimit)).isTrue();
        assertThat(safe(atLimit + "a")).isFalse();
    }

    @Test
    @DisplayName("長度上限以參數為準（頭貼 512）")
    void lengthLimitFollowsParameter() {
        String url = "https://example.com/" + "a".repeat(300);
        assertThat(SafeHttpUrlValidator.isSafe(url, 255, false)).isFalse();
        assertThat(SafeHttpUrlValidator.isSafe(url, 512, false)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/files/11111111-2222-3333-4444-555555555555/content",
            "/api/v1/files/AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE/content"
    })
    @DisplayName("allowFileContentPath=true → 本站檔案內容路徑通過（檔案上傳 API 的回傳格式）")
    void fileContentPath_allowedWhenEnabled(String value) {
        assertThat(SafeHttpUrlValidator.isSafe(value, 512, true)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/files/not-a-uuid/content",
            "/api/v1/files/11111111-2222-3333-4444-555555555555/content/extra",
            "/api/v1/files/11111111-2222-3333-4444-555555555555/content?x=1",
            "/api/v1/files/11111111-2222-3333-4444-555555555555/content\n",
            "//api/v1/files/11111111-2222-3333-4444-555555555555/content",
            "/api/v1/users/me",
            "//evil.com",
            "javascript:alert(1)",
            "/avatar.png"
    })
    @DisplayName("allowFileContentPath=true → 只放行精確的檔案內容路徑，其餘相對路徑仍拒絕")
    void fileContentPath_otherRelativeStillRejected(String value) {
        assertThat(SafeHttpUrlValidator.isSafe(value, 512, true)).isFalse();
    }
}
