package dowob.xyz.blog.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.common.api.errorcode.CommonErrorCode;
import dowob.xyz.blog.common.api.response.ApiResponse;
import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.common.security.AuthUser;
import dowob.xyz.blog.common.util.SecurityUtils;
import dowob.xyz.blog.infrastructure.ratelimit.FixedWindowCounter.Hit;
import dowob.xyz.blog.infrastructure.web.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 應用層限流攔截器（SEC-23 / A4）。
 *
 * <p><b>為何用 HandlerInterceptor 而非 AOP</b>：攔截器在 controller 方法（含參數綁定與 {@code @Valid}）執行前
 * 就能直接寫出 429 與 {@code Retry-After}，不必丟例外再回頭改 header；取得 {@link HandlerMethod} 即可讀
 * {@link RateLimited}，不需要代理，也不受 controller 是否被 CGLIB 代理影響。</p>
 *
 * <p><b>已知限制（multipart）</b>：{@code DispatcherServlet} 在進入攔截器<b>之前</b>會先執行 {@code checkMultipart}，
 * 所以上傳端點的 multipart body 在被限流前就已經解析（可能已寫入暫存檔）。限流擋得住「後續的 controller／service／
 * MinIO 處理」，但擋不住 multipart 解析本身的成本；這部分由 multipart 大小上限與 nginx／ingress 的 body 上限負責。</p>
 *
 * <p><b>與 Spring Security 的順序</b>：攔截器屬 Spring MVC 層，一定在 Security 過濾器鏈<b>之後</b>：
 * 未認證的寫入請求先被 Security 擋成 401，不會消耗限流額度（認證通過但 {@code @PreAuthorize} 之後才拒絕的 403 會消耗額度，屬可接受）。</p>
 *
 * <p>計數主體：規則 {@code subject=USER} 時已登入者以 userUuid、匿名者以 client IP
 * （{@link ClientIpResolver}）；{@code subject=IP} 一律以 IP。key 為
 * {@code ratelimit:{rule}:{u|ip}:{id}}。</p>
 *
 * <p><b>Redis 故障</b>：不吞例外（fail-closed），與 auth 的 IP／帳號限流一致；
 * 防止有人把 Redis 打掛後順便繞過限流。已登入請求本來就依賴 Redis（{@code AuthStateLoader}）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    /** 限流設定 */
    private final RateLimitProperties properties;

    /** 固定窗口計數器 */
    private final FixedWindowCounter counter;

    /** client IP 解析器（全專案取得 client IP 的唯一入口） */
    private final ClientIpResolver clientIpResolver;

    /** 429 body 序列化 */
    private final ObjectMapper objectMapper;

    /**
     * 建構攔截器。
     *
     * @param properties       限流設定
     * @param counter          固定窗口計數器
     * @param clientIpResolver client IP 解析器
     * @param objectMapper     JSON 序列化
     */
    public RateLimitInterceptor(RateLimitProperties properties, FixedWindowCounter counter,
                                ClientIpResolver clientIpResolver, ObjectMapper objectMapper) {
        this.properties = properties;
        this.counter = counter;
        this.clientIpResolver = clientIpResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * 在 handler 執行前計數；超限時直接回 429 並中止。
     *
     * @param request  HTTP 請求
     * @param response HTTP 回應
     * @param handler  handler（非 {@link HandlerMethod} 或未標 {@link RateLimited} 時直接放行）
     * @return {@code true} 放行；{@code false} 已回 429
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RateLimited annotation = findAnnotation(handlerMethod);
        if (annotation == null) {
            return true;
        }

        String ruleName = annotation.value();
        RateLimitProperties.Rule rule = properties.rules().get(ruleName);
        if (rule == null) {
            // 正常情況下 RateLimitRuleValidator 已在啟動時擋下；這裡是最後防線，寧可 500 也不靜默不限流
            throw new IllegalStateException("找不到限流規則 '" + ruleName + "'（app.rate-limit.rules）");
        }

        String key = resolveKey(ruleName, rule, request);
        Hit hit = counter.hit(key, rule.window());
        if (hit.count() <= rule.limit()) {
            return true;
        }

        log.warn("Rate limit exceeded: rule={} key={} count={} limit={}", ruleName, key, hit.count(), rule.limit());
        writeTooManyRequests(response, hit.retryAfterSeconds());
        return false;
    }

    /**
     * 取得 handler 上的規則標註，方法優先，其次類別。
     *
     * @param handlerMethod handler
     * @return 標註；沒有則為 {@code null}
     */
    private static RateLimited findAnnotation(HandlerMethod handlerMethod) {
        RateLimited onMethod = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getMethod(), RateLimited.class);
        if (onMethod != null) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RateLimited.class);
    }

    /**
     * 組出計數 key：{@code ratelimit:{rule}:{u|ip}:{id}}。
     *
     * @param ruleName 規則名稱
     * @param rule     規則內容
     * @param request  HTTP 請求
     * @return Redis key
     */
    private String resolveKey(String ruleName, RateLimitProperties.Rule rule, HttpServletRequest request) {
        if (rule.subject() == RateLimitProperties.Subject.USER) {
            Optional<AuthUser> user = SecurityUtils.currentAuthUser(
                    SecurityContextHolder.getContext().getAuthentication());
            if (user.isPresent()) {
                return RedisKeyConstant.getRateLimitKey(ruleName, RedisKeyConstant.RATE_LIMIT_SUBJECT_USER,
                        user.get().uuid().toString());
            }
        }
        return RedisKeyConstant.getRateLimitKey(ruleName, RedisKeyConstant.RATE_LIMIT_SUBJECT_IP,
                clientIpResolver.resolve(request));
    }

    /**
     * 寫出 429 回應（統一 {@link ApiResponse} 結構，帶 {@code Retry-After} 秒數）。
     *
     * @param response          HTTP 回應
     * @param retryAfterSeconds 建議重試等待秒數
     */
    private void writeTooManyRequests(HttpServletResponse response, long retryAfterSeconds) {
        response.setStatus(429);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(Math.max(1L, retryAfterSeconds)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        try {
            objectMapper.writeValue(response.getWriter(), ApiResponse.failed(CommonErrorCode.TOO_MANY_REQUESTS));
        } catch (IOException e) {
            // 客戶端已斷線；狀態碼與 header 已設定，body 寫不出去無需處理
            log.debug("Failed to write 429 body: {}", e.getMessage());
        }
    }
}
