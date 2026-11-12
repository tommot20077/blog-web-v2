package dowob.xyz.blog.architecture.fixture.principaltype.name;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import java.security.Principal;
import java.util.function.Supplier;

/**
 * {@code PrincipalTypeTest} 規則 (b2) 的反向驗證 fixture——<b>含故意違規</b>，不得被當成範例。
 *
 * <p>principal 換成 record 後，{@code getName()} 會回 {@code AuthUser[id=…, uuid=…]}，
 * 是靜默的語意改變；{@code getUserPrincipal()}／{@code getRemoteUser()} 同理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class NameReadFixture {

    /**
     * 違規：{@link Authentication#getName()}。
     *
     * @param authentication 認證物件
     * @return 名稱
     */
    public String viaAuthentication(Authentication authentication) {
        return authentication.getName();
    }

    /**
     * 違規：{@link Principal#getName()}。
     *
     * @param principal principal
     * @return 名稱
     */
    public String viaPrincipal(Principal principal) {
        return principal.getName();
    }

    /**
     * 違規：具體子類別的 {@code getName()}（bytecode 的呼叫目標型別是子類別）。
     *
     * @param token 認證 token
     * @return 名稱
     */
    public String viaConcreteToken(UsernamePasswordAuthenticationToken token) {
        return token.getName();
    }

    /**
     * 違規：方法參考。
     *
     * @param authentication 認證物件
     * @return 取得名稱的 supplier
     */
    public Supplier<String> viaMethodReference(Authentication authentication) {
        return authentication::getName;
    }

    /**
     * 違規：{@link HttpServletRequest#getUserPrincipal()}。
     *
     * @param request 請求
     * @return principal
     */
    public Principal viaUserPrincipal(HttpServletRequest request) {
        return request.getUserPrincipal();
    }

    /**
     * 違規：{@link HttpServletRequest#getRemoteUser()}。
     *
     * @param request 請求
     * @return 使用者名稱
     */
    public String viaRemoteUser(HttpServletRequest request) {
        return request.getRemoteUser();
    }

    /**
     * 合規對照：HttpServletRequest 的其他方法。
     *
     * @param request 請求
     * @return header 值
     */
    public String compliantHeader(HttpServletRequest request) {
        return request.getHeader("X-Test");
    }

    /**
     * 合規對照：與 Principal 無關、只是同名的 {@code getName()}。
     *
     * @param holder 同名方法的持有者
     * @return 名稱
     */
    public String unrelatedSameName(NameLike holder) {
        return holder.getName();
    }

    /**
     * 與 Principal 無關、卻有同名 {@code getName()} 的型別。
     */
    public static class NameLike {

        /**
         * 同名但不屬於 Principal 的方法。
         *
         * @return 固定字串
         */
        public String getName() {
            return "not-a-principal";
        }
    }
}
