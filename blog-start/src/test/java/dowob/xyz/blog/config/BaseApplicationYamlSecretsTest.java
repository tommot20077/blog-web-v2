package dowob.xyz.blog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基底 {@code application.yaml} 不得內建可用憑證的守衛（SEC-12）。
 *
 * <p>只載入基底 yaml、不啟用任何 profile、不給任何 secret 環境變數，驗證：
 * (1) 所有憑證類屬性解析後皆為空；(2) 真的以 {@link SpringApplication} 啟動會失敗，
 * 且原因是缺必要憑證而非「悄悄用預設憑證連線」。不需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("基底 application.yaml：不得內建可用憑證")
class BaseApplicationYamlSecretsTest {

    /** 憑證類屬性鍵（屬性名中含這些字樣者，基底 yaml 解析後必須為空）。 */
    private static final Pattern CREDENTIAL_KEY =
            Pattern.compile("(?i).*(password|secret|access-key|private-key|username|token|api-key).*");

    /** 只載入基底 yaml 的隔離環境：沒有系統屬性、沒有環境變數，也沒有任何 profile。 */
    private static MockEnvironment baseYamlOnlyEnvironment() throws IOException {
        MockEnvironment env = new MockEnvironment();
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("base-application-yaml", new ClassPathResource("application.yaml"));
        sources.forEach(s -> env.getPropertySources().addLast(s));
        return env;
    }

    @Test
    @DisplayName("基底 yaml 中所有憑證類屬性（帳號／密碼／金鑰）解析後皆為空，沒有可用預設值")
    void baseYamlCredentialPropertiesResolveToBlank() throws IOException {
        MockEnvironment env = baseYamlOnlyEnvironment();

        List<String> credentialKeys = new ArrayList<>();
        env.getPropertySources().forEach(ps -> {
            if (ps instanceof EnumerablePropertySource<?> eps) {
                for (String name : eps.getPropertyNames()) {
                    if (CREDENTIAL_KEY.matcher(name).matches()) {
                        credentialKeys.add(name);
                    }
                }
            }
        });

        assertThat(credentialKeys)
                .as("基底 yaml 應宣告這些必要憑證的鍵（以環境變數注入）")
                .contains("spring.datasource.username", "spring.datasource.password",
                        "spring.rabbitmq.username", "spring.rabbitmq.password",
                        "minio.access-key", "minio.secret-key");
        for (String key : credentialKeys) {
            assertThat(env.getProperty(key, ""))
                    .as("基底 yaml 的 %s 在未設環境變數時必須為空，不得是可用的預設憑證", key)
                    .isBlank();
        }
    }

    @Test
    @DisplayName("基底 yaml 不得含 jwt.private-key 預設值")
    void baseYamlHasNoJwtPrivateKeyDefault() throws IOException {
        assertThat(baseYamlOnlyEnvironment().getProperty("jwt.private-key", "")).isBlank();
    }

    @Test
    @DisplayName("只用基底 yaml、無 profile、無 secret 環境變數啟動 → 啟動失敗並指出缺哪些環境變數")
    void startingWithBaseYamlOnlyFailsInsteadOfUsingDefaultCredentials() {
        SpringApplication app = new SpringApplication(EmptyConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(new IsolatedEnvironment());

        assertThatThrownBy(app::run)
                .hasStackTraceContaining("DATABASE_USERNAME")
                .hasStackTraceContaining("DATABASE_PASSWORD")
                .hasStackTraceContaining("RABBITMQ_USERNAME")
                .hasStackTraceContaining("RABBITMQ_PASSWORD")
                .hasStackTraceContaining("MINIO_ACCESS_KEY")
                .hasStackTraceContaining("MINIO_SECRET_KEY");
    }

    @ParameterizedTest(name = "SPRING_PROFILES_ACTIVE={0}")
    @ValueSource(strings = {"prod,demo", "demo,prod", "prod,dev", "prod,e2e", "prod,test"})
    @DisplayName("prod 與非正式 profile 並存、未給任何 secret → 啟動失敗（不得被 demo／dev yaml 補上的弱憑證放行）")
    void startingWithProdAndNonProductionProfilesFailsEvenThoughYamlSuppliesCredentials(String profiles) {
        SpringApplication app = new SpringApplication(EmptyConfig.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setEnvironment(new IsolatedEnvironment(Map.of("SPRING_PROFILES_ACTIVE", profiles)));

        assertThatThrownBy(app::run)
                .hasStackTraceContaining("prod 不可與 dev/demo/e2e/test 同時啟用");
    }

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {
    }

    /** 不帶系統屬性與環境變數的 Environment（可注入指定的模擬環境變數），避免測試機器上的環境變數影響結果。 */
    private static final class IsolatedEnvironment extends StandardEnvironment {
        private final Map<String, Object> fakeSystemEnvironment;

        IsolatedEnvironment() {
            this(Map.of());
        }

        IsolatedEnvironment(Map<String, Object> fakeSystemEnvironment) {
            this.fakeSystemEnvironment = fakeSystemEnvironment;
        }

        @Override
        protected void customizePropertySources(org.springframework.core.env.MutablePropertySources propertySources) {
            // 父類別建構子會先呼叫本方法，此時子類別欄位尚未初始化，故這裡不能依賴欄位；改於建構後補上。
            propertySources.addLast(new MapPropertySource("isolated-empty", Map.of()));
        }

        @Override
        public org.springframework.core.env.MutablePropertySources getPropertySources() {
            org.springframework.core.env.MutablePropertySources sources = super.getPropertySources();
            if (fakeSystemEnvironment != null && !fakeSystemEnvironment.isEmpty()
                    && !sources.contains("fake-system-environment")) {
                sources.addLast(new org.springframework.core.env.SystemEnvironmentPropertySource(
                        "fake-system-environment", fakeSystemEnvironment));
            }
            return sources;
        }
    }
}
