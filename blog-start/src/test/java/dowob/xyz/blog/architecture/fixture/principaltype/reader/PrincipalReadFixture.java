package dowob.xyz.blog.architecture.fixture.principaltype.reader;

import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code PrincipalTypeTest} 規則 (b) 的反向驗證 fixture——<b>含故意違規</b>，不得被當成範例。
 *
 * <p>涵蓋 {@code getPrincipal()} 的各種呼叫形式：介面、具體子類別、SecurityContextHolder 鏈式、
 * lambda、方法參考；並附合規與「同名但非 Authentication」的對照。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class PrincipalReadFixture {

    /**
     * 違規：經由 {@link Authentication} 介面呼叫。
     *
     * @param authentication 認證物件
     * @return principal
     */
    public Object viaInterface(Authentication authentication) {
        return authentication.getPrincipal();
    }

    /**
     * 違規：經由具體子類別呼叫（bytecode 的呼叫目標型別是子類別，不是 Authentication）。
     *
     * @param token 認證 token
     * @return principal
     */
    public Object viaConcreteToken(UsernamePasswordAuthenticationToken token) {
        return token.getPrincipal();
    }

    /**
     * 違規：由 SecurityContextHolder 鏈式取得。
     *
     * @return principal
     */
    public Object viaContextHolder() {
        return SecurityContextHolder.getContext().getAuthentication().getPrincipal();
    }

    /**
     * 違規：藏在 lambda 內（呼叫端是編譯器產生的 {@code lambda$...} 方法）。
     *
     * @param authentication 認證物件
     * @return 取得 principal 的 supplier
     */
    public Supplier<Object> viaLambda(Authentication authentication) {
        return () -> authentication.getPrincipal();
    }

    /**
     * 違規：方法參考（沒有 invoke 指令，只有 invokedynamic 的 method handle）。
     *
     * @param authentication 認證物件
     * @return 取得 principal 的 supplier
     */
    public Supplier<Object> viaMethodReference(Authentication authentication) {
        return authentication::getPrincipal;
    }

    /**
     * 合規對照：不碰 principal。
     *
     * @param authentication 認證物件
     * @return 名稱
     */
    public String compliantGetName(Authentication authentication) {
        return authentication.getName();
    }

    /**
     * 合規對照：經由 {@link SecurityUtils#currentAuthUser(Authentication)}。
     *
     * @param authentication 認證物件
     * @return 已登入使用者
     */
    public Optional<AuthUser> compliantViaUtils(Authentication authentication) {
        return SecurityUtils.currentAuthUser(authentication);
    }

    /**
     * 合規對照：呼叫與 Authentication 無關、只是同名的 {@code getPrincipal()}，不得誤判。
     *
     * @param holder 同名方法的持有者
     * @return 其 getPrincipal 的結果
     */
    public Object unrelatedSameName(PrincipalLike holder) {
        return holder.getPrincipal();
    }

    /**
     * 與 Authentication 無關、卻有同名 {@code getPrincipal()} 的型別。
     */
    public static class PrincipalLike {

        /**
         * 同名但不屬於 Authentication 的方法。
         *
         * @return 固定字串
         */
        public Object getPrincipal() {
            return "not-an-authentication";
        }
    }
}
