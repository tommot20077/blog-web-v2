package dowob.xyz.blog.infrastructure.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Set;

/**
 * 判斷目前執行環境是否為「明確的非正式環境」的單一真相（SEC-12／SEC-13）。
 *
 * <p>專案中所有「缺 secret 時能否靜默降級」的決策（例如 {@code JwtService} 是否可自動產生金鑰、
 * 啟動時是否強制檢查必要憑證）都必須經由本類別判斷，禁止在其他地方各寫一份 profile 清單。</p>
 *
 * <p>採白名單 fail-closed：只有 {@link #NON_PRODUCTION_PROFILES} 內的 profile 被啟用時才視為非正式環境；
 * 沒有啟用任何 profile、或啟用了未列入白名單的 profile（例如 {@code prod}、{@code staging}），一律視為正式環境。
 * 此外 {@link #PRODUCTION_PROFILE}（{@code prod}）一旦啟用，即使同時啟用了非正式 profile
 * （例如誤設 {@code SPRING_PROFILES_ACTIVE=prod,dev}）也視為正式環境。</p>
 *
 * @author Yuan
 * @version 1.0
 */
public final class RuntimeProfiles {

    /** 明確的非正式環境 profile 白名單（以 repo 實際存在者為準：dev／demo 為 yaml，e2e／test 為測試 profile）。 */
    public static final Set<String> NON_PRODUCTION_PROFILES = Set.of("dev", "demo", "e2e", "test");

    /** 正式環境 profile 名稱（見部署檢核表 {@code SPRING_PROFILES_ACTIVE=prod}）；啟用時優先於白名單。 */
    public static final String PRODUCTION_PROFILE = "prod";

    private RuntimeProfiles() {
    }

    /**
     * 是否為明確的非正式環境。
     *
     * @param environment Spring Environment
     * @return 啟用了白名單內的 profile 且未啟用 {@code prod} 時回傳 true；其餘（含沒有任何 profile）回傳 false
     */
    public static boolean isNonProduction(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))) {
            return false;
        }
        return environment.acceptsProfiles(Profiles.of(NON_PRODUCTION_PROFILES.toArray(String[]::new)));
    }
}
