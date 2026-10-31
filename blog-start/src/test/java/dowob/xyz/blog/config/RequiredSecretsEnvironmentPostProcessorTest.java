package dowob.xyz.blog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RequiredSecretsEnvironmentPostProcessor} 單元測試（SEC-12）：正式環境缺必要憑證時啟動失敗，
 * 且訊息指出要設定哪些環境變數。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("RequiredSecretsEnvironmentPostProcessor：缺必要憑證 fail-fast")
class RequiredSecretsEnvironmentPostProcessorTest {

    private static MockEnvironment envWith(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    private static MockEnvironment allSecretsSet(String... profiles) {
        return envWith(profiles)
                .withProperty("spring.datasource.username", "db-user")
                .withProperty("spring.datasource.password", "db-pass")
                .withProperty("spring.rabbitmq.username", "mq-user")
                .withProperty("spring.rabbitmq.password", "mq-pass")
                .withProperty("minio.access-key", "s3-access")
                .withProperty("minio.secret-key", "s3-secret");
    }

    @Test
    @DisplayName("沒有任何 profile 且一個憑證都沒給 → 失敗，訊息列出全部六個環境變數名")
    void whenNoProfileAndNoSecrets_failsListingAllEnvVars() {
        assertThatThrownBy(() -> RequiredSecretsEnvironmentPostProcessor.validate(envWith()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_USERNAME")
                .hasMessageContaining("DATABASE_PASSWORD")
                .hasMessageContaining("RABBITMQ_USERNAME")
                .hasMessageContaining("RABBITMQ_PASSWORD")
                .hasMessageContaining("MINIO_ACCESS_KEY")
                .hasMessageContaining("MINIO_SECRET_KEY");
    }

    @Test
    @DisplayName("prod 只給 DB 憑證 → 失敗，只列出缺的 RabbitMQ／MinIO，不列已給的 DB")
    void whenProdAndOnlyDbSecretsSet_failsListingOnlyMissingOnes() {
        MockEnvironment env = envWith("prod")
                .withProperty("spring.datasource.username", "db-user")
                .withProperty("spring.datasource.password", "db-pass");

        assertThatThrownBy(() -> RequiredSecretsEnvironmentPostProcessor.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RABBITMQ_USERNAME")
                .hasMessageContaining("RABBITMQ_PASSWORD")
                .hasMessageContaining("MINIO_ACCESS_KEY")
                .hasMessageContaining("MINIO_SECRET_KEY")
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("DATABASE_USERNAME")
                        .doesNotContain("DATABASE_PASSWORD"));
    }

    @Test
    @DisplayName("憑證值僅有空白（Secret 掛成空值）→ 視同未設，失敗")
    void whenSecretIsBlank_failsAsMissing() {
        MockEnvironment env = allSecretsSet("prod").withProperty("spring.datasource.password", "   ");

        assertThatThrownBy(() -> RequiredSecretsEnvironmentPostProcessor.validate(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_PASSWORD");
    }

    @Test
    @DisplayName("prod 且六個憑證都有給 → 通過")
    void whenProdAndAllSecretsSet_passes() {
        assertThatCode(() -> RequiredSecretsEnvironmentPostProcessor.validate(allSecretsSet("prod")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("沒有 profile 但六個憑證都有給 → 通過（是否 fail-fast 只取決於憑證齊不齊）")
    void whenNoProfileButAllSecretsSet_passes() {
        assertThatCode(() -> RequiredSecretsEnvironmentPostProcessor.validate(allSecretsSet()))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "profile={0}")
    @ValueSource(strings = {"dev", "demo", "e2e", "test"})
    @DisplayName("非正式 profile 不要求憑證（由該 profile 的 yaml／測試自行提供）")
    void whenNonProductionProfile_doesNotRequireSecrets(String profile) {
        assertThatCode(() -> RequiredSecretsEnvironmentPostProcessor.validate(envWith(profile)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("prod,dev 並存 → 仍視為正式環境，缺憑證即失敗")
    void whenProdAndDevBothActive_stillRequiresSecrets() {
        assertThatThrownBy(() -> RequiredSecretsEnvironmentPostProcessor.validate(envWith("prod", "dev")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DATABASE_PASSWORD");
    }
}
