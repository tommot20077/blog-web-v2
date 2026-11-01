package dowob.xyz.blog.architecture.fixture.clientip;

import jakarta.servlet.http.HttpServletRequest;

/**
 * {@code ClientIpAccessGuardTest} 白名單反向驗證 fixture：被列入白名單時呼叫 {@code getRemoteAddr()} 應被放行。
 *
 * @author Yuan
 * @version 1.0
 */
public class AllowedRemoteAddrReader {

    /**
     * 直接呼叫。
     *
     * @param request 請求
     * @return remoteAddr
     */
    public String direct(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
