package dowob.xyz.blog.infrastructure.ratelimit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 啟動時驗證所有 {@link RateLimited} 的規則名稱都在 {@code app.rate-limit.rules} 中（fail-fast）。
 *
 * <p>規則名稱打錯或 yaml 沒設定時丟 {@link IllegalStateException} 使 context 啟動失敗，
 * 不會讓端點在沒有任何限流的情況下上線。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class RateLimitRuleValidator implements SmartInitializingSingleton {

    /** 限流設定 */
    private final RateLimitProperties properties;

    /** MVC handler 映射 */
    private final ObjectProvider<RequestMappingHandlerMapping> mappings;

    /**
     * 建構驗證器。
     *
     * @param properties 限流設定
     * @param mappings   MVC handler 映射
     */
    public RateLimitRuleValidator(RateLimitProperties properties,
                                  ObjectProvider<RequestMappingHandlerMapping> mappings) {
        this.properties = properties;
        this.mappings = mappings;
    }

    /**
     * 所有單例建立完成後，掃描全部 handler 並驗證規則。
     */
    @Override
    public void afterSingletonsInstantiated() {
        List<HandlerMethod> all = new ArrayList<>();
        mappings.orderedStream().forEach(mapping -> all.addAll(mapping.getHandlerMethods().values()));
        validate(all);
    }

    /**
     * 驗證給定 handler 上的規則名稱都已設定。
     *
     * @param handlerMethods handler 方法
     * @throws IllegalStateException 有規則名稱不在設定中
     */
    public void validate(Collection<HandlerMethod> handlerMethods) {
        for (HandlerMethod handlerMethod : handlerMethods) {
            RateLimited annotation = AnnotatedElementUtils.findMergedAnnotation(
                    handlerMethod.getMethod(), RateLimited.class);
            if (annotation == null) {
                annotation = AnnotatedElementUtils.findMergedAnnotation(handlerMethod.getBeanType(), RateLimited.class);
            }
            if (annotation != null && !properties.rules().containsKey(annotation.value())) {
                throw new IllegalStateException("限流規則 '" + annotation.value() + "' 未設定（app.rate-limit.rules），"
                        + "使用處：" + handlerMethod.getBeanType().getSimpleName() + "#" + handlerMethod.getMethod().getName());
            }
        }
    }
}
