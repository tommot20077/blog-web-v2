package dowob.xyz.blog.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client IP 信任設定測試（findings SEC-01 第 3 層）。
 *
 * <p>以真實的 {@code application.yaml} 載入 Environment，守住兩件事：</p>
 * <ol>
 *   <li>不得再由 Spring／Tomcat 無條件改寫 remoteAddr：{@code server.forward-headers-strategy}
 *       （{@code framework} 會掛 {@code ForwardedHeaderFilter}、{@code native} 會啟用 Tomcat RemoteIpValve）
 *       與 {@code server.tomcat.remoteip.*} 都不得設定。否則 {@code ClientIpResolver} 的退路
 *       {@code getRemoteAddr()} 被 XFF 污染，兩套機制互相打架；</li>
 *   <li>{@code app.http.trusted-proxy-count} 預設為 0（不信任 XFF），prod 由環境變數明確提高。</li>
 * </ol>
 * <p>不需要 Docker。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("Client IP 信任設定")
class ClientIpTrustConfigTest {

    /**
     * 以真實設定檔建立 Environment 並交給斷言。
     *
     * @param assertions 對 Environment 的斷言
     */
    private static void withRealEnvironment(java.util.function.Consumer<ConfigurableEnvironment> assertions) {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .run(context -> assertions.accept((ConfigurableEnvironment) context.getEnvironment()));
    }

    @Test
    @DisplayName("application.yaml 不得設定 server.forward-headers-strategy（避免無條件信任 XFF）")
    void applicationYaml_hasNoForwardHeadersStrategy() {
        withRealEnvironment(env -> assertThat(env.getProperty("server.forward-headers-strategy"))
                .as("framework／native 都會讓 XFF 無條件覆寫 remoteAddr；client IP 一律走 ClientIpResolver")
                .isNull());
    }

    @Test
    @DisplayName("不得設定 server.tomcat.remoteip.*（Tomcat RemoteIpValve 同樣會改寫 remoteAddr）")
    void applicationYaml_hasNoTomcatRemoteIp() {
        withRealEnvironment(env -> {
            assertThat(env.getProperty("server.tomcat.remoteip.remote-ip-header")).isNull();
            assertThat(env.getProperty("server.tomcat.remoteip.internal-proxies")).isNull();
            assertThat(env.getProperty("server.tomcat.remoteip.trusted-proxies")).isNull();
        });
    }

    @Test
    @DisplayName("app.http.trusted-proxy-count 預設為 0（不信任 XFF）")
    void applicationYaml_trustedProxyCountDefaultsToZero() {
        withRealEnvironment(env -> assertThat(env.getProperty("app.http.trusted-proxy-count", Integer.class))
                .isEqualTo(0));
    }
}
