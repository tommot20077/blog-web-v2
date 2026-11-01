package dowob.xyz.blog.config;

import dowob.xyz.blog.infrastructure.config.RuntimeProfiles;
import org.apache.commons.lang3.StringUtils;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * 正式環境缺必要憑證時啟動失敗（SEC-12，fail-fast）。
 *
 * <p>基底 {@code application.yaml} 不再內建任何可用的預設憑證，憑證類屬性在未設環境變數時解析為空字串。
 * 本 {@link EnvironmentPostProcessor} 在 context 建立之前檢查：若<b>不是</b>明確的非正式環境
 * （判斷唯一來源：{@link RuntimeProfiles#isNonProduction(Environment)}），下列必要憑證任一為空即拋出
 * {@link IllegalStateException}，訊息列出所有缺漏的環境變數名稱。避免生產少注入一個 Secret 時
 * 靜默以空憑證／預設憑證連線，造成難診斷的間歇失敗。</p>
 *
 * <p>非正式 profile（dev／demo／e2e／test）的憑證由各自的 yaml 或測試的動態屬性提供，本檢查略過。
 * 刻意不納入的選用憑證：Redis、Elasticsearch、SMTP（部署可合法地不啟用認證或不寄信）；
 * JWT 私鑰由 {@code JwtService} 以同一個 {@link RuntimeProfiles} 判斷自行 fail-fast。</p>
 *
 * <p>以 {@code META-INF/spring.factories} 註冊，順序為最低優先，確保在 ConfigData（yaml）載入之後執行。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public class RequiredSecretsEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** 必要憑證：屬性鍵與對應的環境變數名稱（錯誤訊息用）。 */
    private record RequiredSecret(String property, String envVar) {
    }

    private static final List<RequiredSecret> REQUIRED_SECRETS = List.of(
            new RequiredSecret("spring.datasource.username", "DATABASE_USERNAME"),
            new RequiredSecret("spring.datasource.password", "DATABASE_PASSWORD"),
            new RequiredSecret("spring.rabbitmq.username", "RABBITMQ_USERNAME"),
            new RequiredSecret("spring.rabbitmq.password", "RABBITMQ_PASSWORD"),
            new RequiredSecret("minio.access-key", "MINIO_ACCESS_KEY"),
            new RequiredSecret("minio.secret-key", "MINIO_SECRET_KEY"));

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        validate(environment);
    }

    /**
     * 驗證必要憑證；非正式環境略過。prod 與 dev／demo／e2e／test 並存時一律失敗
     * （非正式 profile 的 yaml 會補上弱憑證，值檢查會被繞過）。
     *
     * @param environment Spring Environment（需已載入 yaml 與環境變數）
     * @throws IllegalStateException prod 與非正式 profile 並存；或正式環境缺少任一必要憑證（訊息列出缺漏的環境變數名稱）
     */
    public static void validate(Environment environment) {
        RuntimeProfiles.requireNoProductionConflict(environment);
        if (RuntimeProfiles.isNonProduction(environment)) {
            return;
        }
        List<RequiredSecret> missing = REQUIRED_SECRETS.stream()
                .filter(secret -> StringUtils.isBlank(environment.getProperty(secret.property())))
                .toList();
        if (missing.isEmpty()) {
            return;
        }
        StringBuilder message = new StringBuilder("缺少必要憑證，應用程式拒絕啟動（基底 application.yaml 不內建預設憑證）。請設定下列環境變數：");
        for (RequiredSecret secret : missing) {
            message.append("\n  - ").append(secret.envVar()).append("（屬性 ").append(secret.property()).append("）");
        }
        message.append("\n只有 dev／demo／e2e／test profile 允許由各自設定檔提供開發用值。");
        throw new IllegalStateException(message.toString());
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
