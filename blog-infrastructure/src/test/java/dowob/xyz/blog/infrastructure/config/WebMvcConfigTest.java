package dowob.xyz.blog.infrastructure.config;

import dowob.xyz.blog.common.api.request.PageQueryArgumentResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebMvcConfig 單元測試
 *
 * <p>驗證 {@link PageQueryArgumentResolver} 有被註冊。未註冊時 {@code PageQuery} 參數會退回
 * Spring 的 record 建構子綁定，超出範圍時 constructor 的例外被包成 {@code BeanInstantiationException}
 * 而成為 500，而非契約規定的 400。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("WebMvcConfig 單元測試")
class WebMvcConfigTest {

    @Test
    @DisplayName("註冊 PageQueryArgumentResolver")
    void addArgumentResolvers_registersPageQueryResolver() {
        List<HandlerMethodArgumentResolver> resolvers = new ArrayList<>();

        new WebMvcConfig().addArgumentResolvers(resolvers);

        assertThat(resolvers).hasAtLeastOneElementOfType(PageQueryArgumentResolver.class);
    }
}
