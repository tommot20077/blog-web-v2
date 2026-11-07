package dowob.xyz.blog.infrastructure.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RateLimitRuleValidator} 測試：規則名稱打錯或 yaml 沒設定時啟動即失敗（fail-fast）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("RateLimitRuleValidator 啟動驗證")
class RateLimitRuleValidatorTest {

    @RestController
    static class Ok {
        @PostMapping("/ok")
        @RateLimited("known")
        public void handler() {
        }

        @PostMapping("/plain")
        public void plain() {
        }
    }

    @RestController
    static class Typo {
        @PostMapping("/typo")
        @RateLimited("komment-write")
        public void handler() {
        }
    }

    private static RateLimitProperties propsWith(String... names) {
        Map<String, RateLimitProperties.Rule> rules = new java.util.HashMap<>();
        for (String n : names) {
            rules.put(n, new RateLimitProperties.Rule(5, Duration.ofMinutes(1), RateLimitProperties.Subject.USER));
        }
        return new RateLimitProperties(rules);
    }

    private static HandlerMethod hm(Object bean, String name) throws NoSuchMethodException {
        return new HandlerMethod(bean, bean.getClass().getMethod(name));
    }

    @Test
    @DisplayName("規則存在 → 通過；沒有標註的 handler 不檢查")
    void knownRule_passes() throws Exception {
        RateLimitRuleValidator validator = new RateLimitRuleValidator(propsWith("known"), null);

        assertThatCode(() -> validator.validate(List.of(hm(new Ok(), "handler"), hm(new Ok(), "plain"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("規則名稱不在設定 → IllegalStateException，訊息含規則名稱與 handler")
    void unknownRule_failsFast() throws Exception {
        RateLimitRuleValidator validator = new RateLimitRuleValidator(propsWith("known"), null);

        assertThatThrownBy(() -> validator.validate(List.of(hm(new Typo(), "handler"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("komment-write")
                .hasMessageContaining("Typo#handler");
    }

    @Configuration
    @EnableWebMvc
    static class WebConfig {
        @Bean
        Ok ok() {
            return new Ok();
        }

        @Bean
        Typo typo() {
            return new Typo();
        }
    }

    @Test
    @DisplayName("真實 Spring context：規則缺漏時 context 啟動失敗")
    void context_missingRule_failsToStart() {
        new WebApplicationContextRunner()
                .withUserConfiguration(WebConfig.class)
                .withBean(RateLimitProperties.class, () -> propsWith("known"))
                .withBean(RateLimitRuleValidator.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("komment-write");
                });
    }

    @Test
    @DisplayName("真實 Spring context：規則齊全時正常啟動")
    void context_allRulesPresent_starts() {
        new WebApplicationContextRunner()
                .withUserConfiguration(WebConfig.class)
                .withBean(RateLimitProperties.class, () -> propsWith("known", "komment-write"))
                .withBean(RateLimitRuleValidator.class)
                .run(context -> assertThat(context).hasNotFailed());
    }
}
