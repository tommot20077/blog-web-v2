package dowob.xyz.blog.infrastructure.config;

import dowob.xyz.blog.common.api.request.PageQueryArgumentResolver;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * Spring MVC 設定。
 *
 * <p>目前只負責註冊自訂的 controller 參數解析器。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /**
     * 註冊自訂參數解析器。
     *
     * <p>{@link PageQueryArgumentResolver}：分頁參數超出範圍回 400。自訂解析器排在 Spring 內建的
     * model attribute 解析器之前，故會取代 {@code PageQuery} 原本的 record 建構子綁定——
     * 後者在建構子丟例外時會成為 500。</p>
     *
     * @param resolvers Spring 提供的解析器清單
     */
    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new PageQueryArgumentResolver());
    }
}
