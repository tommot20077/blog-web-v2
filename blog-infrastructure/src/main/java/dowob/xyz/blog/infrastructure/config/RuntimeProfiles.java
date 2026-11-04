package dowob.xyz.blog.infrastructure.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Arrays;
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
 * （例如誤設 {@code SPRING_PROFILES_ACTIVE=prod,dev}）也視為正式環境；而這種並存本身是錯誤設定
 * （非正式 profile 的 yaml 會補上弱憑證），由 {@link #requireNoProductionConflict(Environment)} 一律拒絕啟動。</p>
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

    /**
     * prod 是否與任一非正式 profile（dev／demo／e2e／test）同時啟用。
     *
     * <p>此組合是誤設：非正式 profile 的 yaml 會補上弱憑證（例如 {@code postgres/password}、{@code guest/guest}、
     * {@code minioadmin}），使「檢查解析後的值」的 fail-fast 被繞過，應用會以弱憑證在正式環境啟動。</p>
     *
     * @param environment Spring Environment
     * @return prod 與白名單內任一 profile 同時啟用時回傳 true
     */
    public static boolean hasProductionConflict(Environment environment) {
        return environment.acceptsProfiles(Profiles.of(PRODUCTION_PROFILE))
                && environment.acceptsProfiles(Profiles.of(NON_PRODUCTION_PROFILES.toArray(String[]::new)));
    }

    /**
     * 若 prod 與非正式 profile 同時啟用則拋錯；所有「缺 secret 時能否降級」的守衛（憑證檢查、JWT 金鑰）
     * 必須先呼叫本方法，衝突判斷與訊息只在此維護一份。
     *
     * @param environment Spring Environment
     * @throws IllegalStateException prod 與 dev／demo／e2e／test 同時啟用時，訊息列出實際啟用的 profiles
     */
    public static void requireNoProductionConflict(Environment environment) {
        if (hasProductionConflict(environment)) {
            throw new IllegalStateException("prod 不可與 dev/demo/e2e/test 同時啟用：非正式 profile 的設定檔會補上弱憑證，"
                    + "使正式環境以預設憑證啟動。目前啟用的 profiles：" + Arrays.toString(environment.getActiveProfiles())
                    + "。請調整 SPRING_PROFILES_ACTIVE，正式環境只能設為 prod。");
        }
    }
}
