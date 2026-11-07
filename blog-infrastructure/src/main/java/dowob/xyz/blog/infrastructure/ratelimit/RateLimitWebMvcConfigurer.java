package dowob.xyz.blog.infrastructure.ratelimit;

import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 註冊 {@link RateLimitInterceptor}。
 *
 * <p>本類別與其依賴都由 {@link RateLimitConfiguration} 以 {@code @Bean} 建立，而不是 {@code @Component} 掃描：
 * {@code @WebMvcTest} 會掃描 {@code WebMvcConfigurer} 與 {@code HandlerInterceptor} 型別的 {@code @Component}，
 * 但不會處理被掃到的一般 {@code @Configuration}；若用 {@code @Component}，既有 controller 切片測試就會因缺
 * {@link RateLimitProperties}／Redis 而無法啟動。切片測試中沒有限流攔截器（也不需要），完整 context 才有。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class RateLimitWebMvcConfigurer implements WebMvcConfigurer {

    /** 攔截器 */
    private final RateLimitInterceptor interceptor;

    /**
     * 建構。
     *
     * @param interceptor 攔截器
     */
    public RateLimitWebMvcConfigurer(RateLimitInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    /**
     * 註冊攔截器（全部路徑；是否限流由 handler 上的 {@link RateLimited} 決定）。
     *
     * @param registry 攔截器登錄表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor);
    }
}
