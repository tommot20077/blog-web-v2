package dowob.xyz.blog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Redis 逾時設定測試。
 *
 * <p>Lettuce 預設指令逾時 60 秒、連線逾時 10 秒；Redis 掛掉時請求執行緒會被拖住很久。
 * 此測試以真實的 {@code application.yaml} 載入環境，驗證 {@code spring.data.redis} 的
 * {@code timeout}／{@code connect-timeout} 預設為 2 秒，且可由環境變數
 * {@code REDIS_TIMEOUT}／{@code REDIS_CONNECT_TIMEOUT} 覆寫。不需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Redis 逾時設定")
class RedisTimeoutConfigTest {

    private static final String TIMEOUT_KEY = "spring.data.redis.timeout";
    private static final String CONNECT_TIMEOUT_KEY = "spring.data.redis.connect-timeout";

    @Test
    @DisplayName("application.yaml 預設指令逾時與連線逾時皆為 2 秒")
    void applicationYaml_defaultsBothTimeoutsToTwoSeconds() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> {
                    Binder binder = Binder.get(context.getEnvironment());
                    assertThat(binder.bind(TIMEOUT_KEY, Duration.class).orElse(null))
                            .isEqualTo(Duration.ofSeconds(2));
                    assertThat(binder.bind(CONNECT_TIMEOUT_KEY, Duration.class).orElse(null))
                            .isEqualTo(Duration.ofSeconds(2));
                });
    }

    @Test
    @DisplayName("REDIS_TIMEOUT／REDIS_CONNECT_TIMEOUT 可覆寫預設值")
    void environmentVariables_overrideTimeouts() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("REDIS_TIMEOUT=5s", "REDIS_CONNECT_TIMEOUT=500ms")
                .run(context -> {
                    Binder binder = Binder.get(context.getEnvironment());
                    assertThat(binder.bind(TIMEOUT_KEY, Duration.class).orElse(null))
                            .isEqualTo(Duration.ofSeconds(5));
                    assertThat(binder.bind(CONNECT_TIMEOUT_KEY, Duration.class).orElse(null))
                            .isEqualTo(Duration.ofMillis(500));
                });
    }
}
