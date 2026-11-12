package dowob.xyz.blog.infrastructure.web;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F2：未設定 {@code app.http.trusted-proxy-count}（=0）時，啟動需留下一行 WARN。
 *
 * <p>情境：正式環境忘了設 {@code APP_HTTP_TRUSTED_PROXY_COUNT}，所有請求的 remoteAddr 都是 Ingress 的 IP，
 * 登入／註冊 IP 限流等於全站共用一個桶（任一攻擊者 20 次請求即可鎖死全站登入），而且沒有任何警示。
 * 不讓啟動失敗（dev／CI 本來就是 0），只要留下可 grep 的警示。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ClientIpResolver 啟動警示（count=0）")
class ClientIpResolverStartupWarnTest {

    /** 本專案日誌實作為 log4j2；resolver 與 filter 兩個 logger 都掛上，確認「只有 resolver 警示一次」 */
    private static final List<Class<?>> WATCHED = List.of(ClientIpResolver.class, TrustedForwardedRequestFilter.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ClientIpResolver.class, TrustedForwardedRequestFilter.class);

    private CapturingAppender appender;

    @BeforeEach
    void attachAppender() {
        appender = new CapturingAppender();
        appender.start();
        for (Class<?> type : WATCHED) {
            // 測試環境預設 root 為 ERROR，明確開到 INFO 才收得到 WARN
            Configurator.setLevel(type.getName(), Level.INFO);
            ((Logger) LogManager.getLogger(type)).addAppender(appender);
        }
    }

    @AfterEach
    void detachAppender() {
        for (Class<?> type : WATCHED) {
            ((Logger) LogManager.getLogger(type)).removeAppender(appender);
            Configurator.setLevel(type.getName(), (Level) null);
        }
        appender.stop();
    }

    private List<LogEvent> warnings() {
        return appender.events.stream().filter(e -> e.getLevel().isMoreSpecificThan(Level.WARN)).toList();
    }

    @Test
    @DisplayName("count=0（明確設定）：啟動時有且僅有一行 WARN，說明以 remoteAddr 計並指示設定 APP_HTTP_TRUSTED_PROXY_COUNT")
    void countZero_logsExactlyOneWarn() {
        runner.withPropertyValues("app.http.trusted-proxy-count=0").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(warnings()).hasSize(1);
            LogEvent warn = warnings().get(0);
            assertThat(warn.getLevel()).isEqualTo(Level.WARN);
            assertThat(warn.getMessage().getFormattedMessage())
                    .contains("remoteAddr")
                    .contains("APP_HTTP_TRUSTED_PROXY_COUNT");
        });
    }

    @Test
    @DisplayName("完全未設定（走預設 0）：同樣會 WARN，且不讓啟動失敗")
    void unset_defaultsToZeroAndWarns() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(warnings()).hasSize(1);
        });
    }

    @Test
    @DisplayName("count=1：沒有 WARN")
    void countOne_noWarn() {
        runner.withPropertyValues("app.http.trusted-proxy-count=1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(warnings()).isEmpty();
        });
    }

    @Test
    @DisplayName("count=2：沒有 WARN")
    void countTwo_noWarn() {
        runner.withPropertyValues("app.http.trusted-proxy-count=2").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(warnings()).isEmpty();
        });
    }

    @Test
    @DisplayName("負數仍啟動失敗（filter 與 resolver 一致）")
    void negative_stillFailsStartup() {
        runner.withPropertyValues("app.http.trusted-proxy-count=-1")
                .run(context -> assertThat(context).hasFailed());
    }

    /** 捕捉日誌事件的測試用 appender */
    private static final class CapturingAppender extends AbstractAppender {

        private final List<LogEvent> events = new ArrayList<>();

        private CapturingAppender() {
            super("client-ip-warn-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            events.add(event.toImmutable());
        }
    }
}
