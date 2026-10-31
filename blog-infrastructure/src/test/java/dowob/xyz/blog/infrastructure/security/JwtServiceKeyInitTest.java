package dowob.xyz.blog.infrastructure.security;

import io.jsonwebtoken.Jwts;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JwtService} 金鑰初始化的環境守衛測試（SEC-13）。
 *
 * <p>規則：{@code jwt.private-key} 未設（或僅空白）時，只有明確的非正式 profile
 * （dev／demo／e2e／test）允許自動產生金鑰並記 WARN；其餘情況（含沒有任何 profile）啟動失敗。
 * 設了私鑰則一律使用該私鑰，不受 profile 影響。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("JwtService：金鑰初始化的環境守衛")
class JwtServiceKeyInitTest {

    private static final UUID USER_UUID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private CapturingAppender appender;

    @BeforeEach
    void attachAppender() {
        Configurator.setLevel(JwtService.class.getName(), Level.WARN);
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration cfg = ctx.getConfiguration();
        appender = new CapturingAppender();
        appender.start();
        cfg.addLoggerAppender((org.apache.logging.log4j.core.Logger) LogManager.getLogger(JwtService.class), appender);
    }

    @AfterEach
    void detachAppender() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.getConfiguration().getLoggerConfig(JwtService.class.getName()).removeAppender(appender.getName());
        appender.stop();
    }

    private static MockEnvironment envWith(String... profiles) {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(profiles);
        return env;
    }

    private static JwtService newService(MockEnvironment env, String privateKeyPem) {
        JwtService svc = new JwtService(env);
        ReflectionTestUtils.setField(svc, "privateKeyPem", privateKeyPem);
        ReflectionTestUtils.setField(svc, "expiration", 3600000L);
        ReflectionTestUtils.setField(svc, "accessTokenExpiration", 900000L);
        ReflectionTestUtils.setField(svc, "refreshTokenExpiration", 604800000L);
        return svc;
    }

    private static KeyPair newKeyPair() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(256);
        return gen.generateKeyPair();
    }

    private static String toPem(KeyPair pair) {
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
    }

    @Test
    @DisplayName("未設私鑰且沒有任何 profile → 啟動失敗，訊息含 JWT_PRIVATE_KEY 與 jwt.private-key")
    void whenPrivateKeyMissingAndNoProfile_initFailsWithEnvVarName() {
        JwtService svc = newService(new MockEnvironment(), "");

        assertThatThrownBy(svc::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY")
                .hasMessageContaining("jwt.private-key");
    }

    @Test
    @DisplayName("未設私鑰且為 prod profile → 啟動失敗")
    void whenPrivateKeyMissingAndProdProfile_initFails() {
        JwtService svc = newService(envWith("prod"), "");

        assertThatThrownBy(svc::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY");
    }

    @Test
    @DisplayName("私鑰僅有空白（Secret 掛載成空檔）且為 prod → 視同未設，啟動失敗")
    void whenPrivateKeyBlankAndProdProfile_initFails() {
        JwtService svc = newService(envWith("prod"), "  \n ");

        assertThatThrownBy(svc::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY");
    }

    @Test
    @DisplayName("私鑰為 null 且沒有任何 profile → 啟動失敗（不得 NPE 繞過）")
    void whenPrivateKeyNullAndNoProfile_initFails() {
        JwtService svc = newService(new MockEnvironment(), null);

        assertThatThrownBy(svc::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_PRIVATE_KEY");
    }

    @ParameterizedTest(name = "profile={0}")
    @ValueSource(strings = {"dev", "demo", "e2e", "test"})
    @DisplayName("未設私鑰但為非正式 profile → 自動產生金鑰、token 可簽可驗，並記 WARN")
    void whenPrivateKeyMissingAndNonProductionProfile_generatesKeyAndWarns(String profile) {
        JwtService svc = newService(envWith(profile), "");

        svc.init();

        String token = svc.generateAccessToken(USER_UUID, "USER", "v1");
        assertThat(svc.validateToken(token)).isTrue();
        assertThat(appender.warnMessages())
                .anySatisfy(msg -> assertThat(msg).contains("JWT_PRIVATE_KEY"));
    }

    @Test
    @DisplayName("設了私鑰且為 prod → 使用該私鑰（token 可用其公鑰驗證），不記 WARN")
    void whenPrivateKeyProvidedAndProdProfile_usesConfiguredKey() throws Exception {
        KeyPair pair = newKeyPair();
        JwtService svc = newService(envWith("prod"), toPem(pair));

        svc.init();

        String token = svc.generateAccessToken(USER_UUID, "USER", "v1");
        assertThat(Jwts.parser().verifyWith(pair.getPublic()).build().parseSignedClaims(token).getPayload().getSubject())
                .isEqualTo(USER_UUID.toString());
        assertThat(appender.warnMessages()).isEmpty();
    }

    @Test
    @DisplayName("設了私鑰且為 dev → 仍使用該私鑰，不因非正式 profile 改為隨機金鑰")
    void whenPrivateKeyProvidedAndDevProfile_usesConfiguredKey() throws Exception {
        KeyPair pair = newKeyPair();
        JwtService svc = newService(envWith("dev"), toPem(pair));

        svc.init();

        String token = svc.generateAccessToken(USER_UUID, "USER", "v1");
        assertThat(Jwts.parser().verifyWith(pair.getPublic()).build().parseSignedClaims(token).getPayload().getSubject())
                .isEqualTo(USER_UUID.toString());
        assertThat(appender.warnMessages()).isEmpty();
    }

    /** 收集 JwtService logger 的 WARN 訊息。 */
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> warns = new ArrayList<>();

        CapturingAppender() {
            super("jwt-key-init-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel() == Level.WARN) {
                warns.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> warnMessages() {
            return warns;
        }
    }
}
