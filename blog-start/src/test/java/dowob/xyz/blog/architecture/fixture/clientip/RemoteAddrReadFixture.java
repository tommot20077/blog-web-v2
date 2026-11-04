package dowob.xyz.blog.architecture.fixture.clientip;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletRequest;

import java.util.function.Function;

/**
 * {@code ClientIpAccessGuardTest} 的反向驗證 fixture——<b>含故意違規</b>，不得被當成範例。
 *
 * <p>直接讀取 remoteAddr／remoteHost 會繞過 {@code ClientIpResolver} 的信任鏈設定（SEC-01）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class RemoteAddrReadFixture {

    /**
     * 違規：{@link HttpServletRequest#getRemoteAddr()}。
     *
     * @param request 請求
     * @return remoteAddr
     */
    public String viaHttpServletRequest(HttpServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * 違規：父介面 {@link ServletRequest#getRemoteAddr()}。
     *
     * @param request 請求
     * @return remoteAddr
     */
    public String viaServletRequest(ServletRequest request) {
        return request.getRemoteAddr();
    }

    /**
     * 違規：{@link ServletRequest#getRemoteHost()}。
     *
     * @param request 請求
     * @return remoteHost
     */
    public String viaRemoteHost(HttpServletRequest request) {
        return request.getRemoteHost();
    }

    /**
     * 違規：方法參考（沒有 invoke 指令也要抓）。
     *
     * @return 取 remoteAddr 的函式
     */
    public Function<HttpServletRequest, String> viaMethodReference() {
        return HttpServletRequest::getRemoteAddr;
    }

    /**
     * 違規：lambda 內的呼叫。
     *
     * @return 取 remoteAddr 的函式
     */
    public Function<HttpServletRequest, String> viaLambda() {
        return r -> r.getRemoteAddr();
    }

    /**
     * 合規：讀其他 request 屬性（與 client IP 無關）。
     *
     * @param request 請求
     * @return 請求方法
     */
    public String compliantUnrelatedAccessor(HttpServletRequest request) {
        return request.getMethod() + request.getRemotePort();
    }
}
