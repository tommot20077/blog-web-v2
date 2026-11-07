package dowob.xyz.blog.infrastructure.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RateLimitProperties} 綁定與驗證測試。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("RateLimitProperties 綁定與驗證")
class RateLimitPropertiesTest {

    private static RateLimitProperties bind(Map<String, Object> source) {
        return new Binder(new MapConfigurationPropertySource(source))
                .bind("app.rate-limit", Bindable.of(RateLimitProperties.class))
                .orElseGet(() -> new RateLimitProperties(Map.of()));
    }

    @Test
    @DisplayName("limit／window／subject 可由設定綁定；subject 預設 USER")
    void binds() {
        RateLimitProperties props = bind(Map.of(
                "app.rate-limit.rules.comment-write.limit", "10",
                "app.rate-limit.rules.comment-write.window", "1m",
                "app.rate-limit.rules.search.limit", "30",
                "app.rate-limit.rules.search.window", "60s",
                "app.rate-limit.rules.search.subject", "ip"));

        assertThat(props.rules().get("comment-write"))
                .isEqualTo(new RateLimitProperties.Rule(10, Duration.ofMinutes(1), RateLimitProperties.Subject.USER));
        assertThat(props.rules().get("search").subject()).isEqualTo(RateLimitProperties.Subject.IP);
    }

    @Test
    @DisplayName("limit < 1 → 啟動失敗")
    void zeroLimit_fails() {
        assertThatThrownBy(() -> bind(Map.of(
                "app.rate-limit.rules.x.limit", "0",
                "app.rate-limit.rules.x.window", "1m")))
                .isInstanceOf(BindException.class)
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("window 不足 1 秒或缺漏 → 啟動失敗")
    void badWindow_fails() {
        assertThatThrownBy(() -> bind(Map.of(
                "app.rate-limit.rules.x.limit", "5",
                "app.rate-limit.rules.x.window", "0s")))
                .isInstanceOf(BindException.class);
        assertThatThrownBy(() -> bind(Map.of("app.rate-limit.rules.x.limit", "5")))
                .isInstanceOf(BindException.class);
    }
}
