package dowob.xyz.blog.architecture.fixture.principaltype.reader;

import org.springframework.security.core.Authentication;

import java.util.function.Supplier;

/**
 * {@code PrincipalTypeTest} 規則 (b) 白名單機制的反向驗證 fixture。
 *
 * <p>測試會把本類的全名放進白名單，驗證白名單<b>確實能放行</b>（含 lambda、內部類別，
 * 都歸併到最外層類別），而不放白名單時它又確實會被抓到。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class AllowedPrincipalReader {

    /**
     * 直接呼叫。
     *
     * @param authentication 認證物件
     * @return principal
     */
    public Object direct(Authentication authentication) {
        return authentication.getPrincipal();
    }

    /**
     * 藏在 lambda 內呼叫。
     *
     * @param authentication 認證物件
     * @return 取得 principal 的 supplier
     */
    public Supplier<Object> inLambda(Authentication authentication) {
        return () -> authentication.getPrincipal();
    }

    /**
     * 內部類別，其呼叫要歸併到最外層類別一併放行。
     */
    public static class Inner {

        /**
         * 內部類別中的呼叫。
         *
         * @param authentication 認證物件
         * @return principal
         */
        public Object nested(Authentication authentication) {
            return authentication.getPrincipal();
        }
    }
}
