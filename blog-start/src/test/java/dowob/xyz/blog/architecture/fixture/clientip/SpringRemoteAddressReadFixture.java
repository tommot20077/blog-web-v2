package dowob.xyz.blog.architecture.fixture.clientip;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.web.authentication.WebAuthenticationDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;

import java.util.function.Function;

/**
 * {@code ClientIpAccessGuardTest} 的反向驗證 fixture——<b>含故意違規</b>，不得被當成範例。
 *
 * <p>Spring 另外提供兩條不經過 {@code ServletRequest#getRemoteAddr()} 呼叫點的路徑取得 remote 位址
 * （底層仍是同一個 remoteAddr，繞過 {@code ClientIpResolver} 的信任鏈設定，SEC-01）：
 * {@code ServerHttpRequest#getRemoteAddress()} 與 {@code WebAuthenticationDetails#getRemoteAddress()}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class SpringRemoteAddressReadFixture {

    /**
     * 違規：{@code new ServletServerHttpRequest(r).getRemoteAddress()}（呼叫在子型別上）。
     *
     * @param request 請求
     * @return remote 位址字串
     */
    public String viaServletServerHttpRequest(HttpServletRequest request) {
        return String.valueOf(new ServletServerHttpRequest(request).getRemoteAddress());
    }

    /**
     * 違規：介面 {@link ServerHttpRequest#getRemoteAddress()}。
     *
     * @param request 請求
     * @return remote 位址字串
     */
    public String viaServerHttpRequestInterface(ServerHttpRequest request) {
        return String.valueOf(request.getRemoteAddress());
    }

    /**
     * 違規：{@code new WebAuthenticationDetailsSource().buildDetails(r).getRemoteAddress()}。
     *
     * @param request 請求
     * @return remote 位址
     */
    public String viaAuthenticationDetailsSource(HttpServletRequest request) {
        return new WebAuthenticationDetailsSource().buildDetails(request).getRemoteAddress();
    }

    /**
     * 違規：{@code new WebAuthenticationDetails(r).getRemoteAddress()}。
     *
     * @param request 請求
     * @return remote 位址
     */
    public String viaAuthenticationDetailsConstructor(HttpServletRequest request) {
        return new WebAuthenticationDetails(request).getRemoteAddress();
    }

    /**
     * 違規：方法參考。
     *
     * @return 取 remote 位址的函式
     */
    public Function<WebAuthenticationDetails, String> viaMethodReference() {
        return WebAuthenticationDetails::getRemoteAddress;
    }

    /**
     * 合規：{@code WebAuthenticationDetails#getSessionId} 與 {@code ServerHttpRequest#getURI} 與 client IP 無關。
     *
     * @param details 認證細節
     * @param request 請求
     * @return 無關欄位
     */
    public String compliantUnrelatedAccessors(WebAuthenticationDetails details, ServerHttpRequest request) {
        return details.getSessionId() + request.getURI();
    }
}
