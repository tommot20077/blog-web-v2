package dowob.xyz.blog.infrastructure.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link RuntimeProfiles} 單元測試：「是否為明確的非正式環境」的單一判斷（SEC-12／SEC-13）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("RuntimeProfiles：非正式環境判斷")
class RuntimeProfilesTest {

    private static MockEnvironment envWith(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    @ParameterizedTest(name = "profile={0}")
    @ValueSource(strings = {"dev", "demo", "e2e", "test"})
    @DisplayName("dev／demo／e2e／test 任一啟用 → 視為非正式環境")
    void whenExplicitNonProductionProfileActive_isNonProductionReturnsTrue(String profile) {
        assertThat(RuntimeProfiles.isNonProduction(envWith(profile))).isTrue();
    }

    @Test
    @DisplayName("沒有啟用任何 profile → 視為正式環境（fail-closed）")
    void whenNoProfileActive_isNonProductionReturnsFalse() {
        assertThat(RuntimeProfiles.isNonProduction(new MockEnvironment())).isFalse();
    }

    @ParameterizedTest(name = "profile={0}")
    @ValueSource(strings = {"prod", "staging", "k3s", "Dev", "default"})
    @DisplayName("prod 或未列入白名單的 profile（含大小寫不同者）→ 視為正式環境")
    void whenOnlyUnlistedProfileActive_isNonProductionReturnsFalse(String profile) {
        assertThat(RuntimeProfiles.isNonProduction(envWith(profile))).isFalse();
    }

    @Test
    @DisplayName("prod 與非正式 profile 並存（例如 prod,dev）→ 仍視為正式環境，避免誤設放行")
    void whenProdAndNonProductionProfilesBothActive_isNonProductionReturnsFalse() {
        assertThat(RuntimeProfiles.isNonProduction(envWith("prod", "dev"))).isFalse();
    }

    @Test
    @DisplayName("非正式 profile 與其他非 prod profile 並存（例如 dev,local）→ 視為非正式環境")
    void whenNonProductionAndOtherProfilesActive_isNonProductionReturnsTrue() {
        assertThat(RuntimeProfiles.isNonProduction(envWith("local", "dev"))).isTrue();
    }

    @ParameterizedTest(name = "profiles={0}")
    @ValueSource(strings = {"prod,dev", "dev,prod", "prod,demo", "demo,prod", "prod,e2e", "prod,test", "prod,local,test"})
    @DisplayName("prod 與任一 dev／demo／e2e／test 並存（不分順序）→ 判定為衝突")
    void whenProdAndNonProductionProfilesBothActive_hasProductionConflictReturnsTrue(String profiles) {
        assertThat(RuntimeProfiles.hasProductionConflict(envWith(profiles.split(",")))).isTrue();
    }

    @ParameterizedTest(name = "profiles={0}")
    @ValueSource(strings = {"prod", "demo", "dev", "dev,demo", "prod,staging", "staging,dev"})
    @DisplayName("單獨 prod、僅非正式 profile、或 prod 搭配非白名單 profile → 不算衝突")
    void whenNoProdWithNonProductionCombination_hasProductionConflictReturnsFalse(String profiles) {
        assertThat(RuntimeProfiles.hasProductionConflict(envWith(profiles.split(",")))).isFalse();
    }

    @Test
    @DisplayName("沒有啟用任何 profile → 不算衝突")
    void whenNoProfileActive_hasProductionConflictReturnsFalse() {
        assertThat(RuntimeProfiles.hasProductionConflict(new MockEnvironment())).isFalse();
    }

    @Test
    @DisplayName("prod,demo 並存 → requireNoProductionConflict 拋錯，訊息說明規則並列出實際啟用的 profiles")
    void whenProdAndDemoActive_requireNoProductionConflictThrowsWithActiveProfiles() {
        assertThatThrownBy(() -> RuntimeProfiles.requireNoProductionConflict(envWith("prod", "demo")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod 不可與 dev/demo/e2e/test 同時啟用")
                .hasMessageContaining("prod")
                .hasMessageContaining("demo");
    }

    @ParameterizedTest(name = "profiles={0}")
    @ValueSource(strings = {"prod", "demo", "dev", "prod,staging"})
    @DisplayName("無衝突時 requireNoProductionConflict 不拋錯")
    void whenNoConflict_requireNoProductionConflictDoesNotThrow(String profiles) {
        assertThatCode(() -> RuntimeProfiles.requireNoProductionConflict(envWith(profiles.split(","))))
                .doesNotThrowAnyException();
    }
}
