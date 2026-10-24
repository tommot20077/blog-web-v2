package dowob.xyz.blog.architecture.fixture.principaltype.name;

import org.springframework.security.core.Authentication;

/**
 * {@code PrincipalTypeTest} 規則 (b2) 白名單機制的反向驗證 fixture：測試會把本類放進白名單驗證放行。
 *
 * @author Yuan
 * @version 1.0
 */
public class AllowedNameReader {

    /**
     * 呼叫 {@link Authentication#getName()}。
     *
     * @param authentication 認證物件
     * @return 名稱
     */
    public String name(Authentication authentication) {
        return authentication.getName();
    }
}
