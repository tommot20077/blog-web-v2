package dowob.xyz.blog.architecture.fixture.principaltype.inject;

import org.springframework.security.core.annotation.CurrentSecurityContext;

/**
 * {@code PrincipalTypeTest} 規則 (c) 的反向驗證 fixture——<b>含故意違規</b>，不得被當成範例。
 *
 * <p>本類刻意不使用直接的 {@code @AuthenticationPrincipal}，以免干擾規則 (a) 的 fixture 斷言。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class InjectionFixture {

    /**
     * 違規：直接使用 {@code @CurrentSecurityContext}。
     *
     * @param principal 當前登入者
     * @return 佔位字串
     */
    public String currentSecurityContext(
            @CurrentSecurityContext(expression = "authentication.principal") Object principal) {
        return String.valueOf(principal);
    }

    /**
     * 違規：使用被 {@code @AuthenticationPrincipal} meta 標註的自訂註解。
     *
     * @param userId 當前登入者主鍵
     * @return 佔位字串
     */
    public String customPrincipal(@CustomPrincipal Long userId) {
        return String.valueOf(userId);
    }

    /**
     * 違規：使用被 {@code @CurrentSecurityContext} meta 標註的自訂註解。
     *
     * @param principal 當前登入者
     * @return 佔位字串
     */
    public String customSecurityContext(@CustomSecurityContext Object principal) {
        return String.valueOf(principal);
    }

    /**
     * 合規對照：與登入身分無關的自訂註解。
     *
     * @param value 一般參數
     * @return 佔位字串
     */
    public String unrelated(@UnrelatedMarker String value) {
        return value;
    }

    /**
     * 合規對照：沒有任何註解。
     *
     * @param value 一般參數
     * @return 佔位字串
     */
    public String plain(String value) {
        return value;
    }
}
