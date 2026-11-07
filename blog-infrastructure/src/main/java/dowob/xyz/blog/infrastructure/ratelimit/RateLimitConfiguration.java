package dowob.xyz.blog.infrastructure.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import dowob.xyz.blog.infrastructure.web.ClientIpResolver;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 應用層限流的 Spring 設定（SEC-23 / A4）：計數器、攔截器、攔截器註冊與啟動時規則驗證。
 *
 * <p>全部以 {@code @Bean} 定義，原因見 {@link RateLimitWebMvcConfigurer}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitConfiguration {

    /**
     * Redis 固定窗口計數器。
     *
     * @param redisTemplate Redis 模板
     * @return 計數器
     */
    @Bean
    public FixedWindowCounter fixedWindowCounter(StringRedisTemplate redisTemplate) {
        return new FixedWindowCounter(redisTemplate);
    }

    /**
     * 限流攔截器。
     *
     * @param properties       限流設定
     * @param counter          計數器
     * @param clientIpResolver client IP 解析器
     * @param objectMapper     JSON 序列化
     * @return 攔截器
     */
    @Bean
    public RateLimitInterceptor rateLimitInterceptor(RateLimitProperties properties, FixedWindowCounter counter,
                                                     ClientIpResolver clientIpResolver, ObjectMapper objectMapper) {
        return new RateLimitInterceptor(properties, counter, clientIpResolver, objectMapper);
    }

    /**
     * 將攔截器註冊進 Spring MVC。
     *
     * @param interceptor 攔截器
     * @return MVC 設定
     */
    @Bean
    public RateLimitWebMvcConfigurer rateLimitWebMvcConfigurer(RateLimitInterceptor interceptor) {
        return new RateLimitWebMvcConfigurer(interceptor);
    }

    /**
     * 啟動時驗證規則名稱（fail-fast）。
     *
     * @param properties 限流設定
     * @param mappings   MVC handler 映射
     * @return 驗證器
     */
    @Bean
    public RateLimitRuleValidator rateLimitRuleValidator(RateLimitProperties properties,
                                                         ObjectProvider<RequestMappingHandlerMapping> mappings) {
        return new RateLimitRuleValidator(properties, mappings);
    }
}
