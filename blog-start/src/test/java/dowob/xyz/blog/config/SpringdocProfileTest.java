package dowob.xyz.blog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Swagger UI 與 {@code /v3/api-docs} 預設關閉的守衛（SEC-21，Yuan 2026-11-04 決定）。
 *
 * <p>以 yaml 綁定方式驗證：基底 yaml（無 profile）兩個開關皆為 false；dev／demo 開啟；
 * 環境變數 {@code SPRINGDOC_ENABLED=true} 可同時覆寫兩者。不需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("springdoc 預設關閉（SEC-21）")
class SpringdocProfileTest {

    private static final String API_DOCS = "springdoc.api-docs.enabled";
    private static final String SWAGGER_UI = "springdoc.swagger-ui.enabled";

    /** 載入基底 yaml，再依序疊上指定的 profile yaml（後者優先）與模擬的環境變數。 */
    private static MockEnvironment environment(String profileYaml, Map<String, Object> envVars) throws IOException {
        MockEnvironment env = new MockEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        if (!envVars.isEmpty()) {
            env.getPropertySources().addLast(new MapPropertySource("fake-env", envVars));
        }
        if (profileYaml != null) {
            List<PropertySource<?>> profile = loader.load("profile", new ClassPathResource(profileYaml));
            profile.forEach(s -> env.getPropertySources().addLast(s));
        }
        loader.load("base", new ClassPathResource("application.yaml"))
                .forEach(s -> env.getPropertySources().addLast(s));
        return env;
    }

    @Test
    @DisplayName("無任何 profile、無環境變數 → api-docs 與 swagger-ui 皆為 false")
    void baseYamlDisablesSpringdocByDefault() throws IOException {
        MockEnvironment env = environment(null, Map.of());

        assertThat(env.getProperty(API_DOCS)).isEqualTo("false");
        assertThat(env.getProperty(SWAGGER_UI)).isEqualTo("false");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"application-dev.yaml", "application-demo.yaml"})
    @DisplayName("dev／demo profile → api-docs 與 swagger-ui 皆為 true")
    void devAndDemoEnableSpringdoc(String profileYaml) throws IOException {
        MockEnvironment env = environment(profileYaml, Map.of());

        assertThat(env.getProperty(API_DOCS)).isEqualTo("true");
        assertThat(env.getProperty(SWAGGER_UI)).isEqualTo("true");
    }

    @Test
    @DisplayName("SPRINGDOC_ENABLED=true → 無 profile 下兩個開關同時開啟")
    void envVariableEnablesBothSwitches() throws IOException {
        MockEnvironment env = environment(null, Map.of("SPRINGDOC_ENABLED", "true"));

        assertThat(env.getProperty(API_DOCS)).isEqualTo("true");
        assertThat(env.getProperty(SWAGGER_UI)).isEqualTo("true");
    }
}
