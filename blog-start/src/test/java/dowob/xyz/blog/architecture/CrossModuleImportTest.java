package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模組 import 守衛（設計 {@code 2026-10-24-cross-module-uuid-design.md} §3.1「跨模組 import 守衛（Q11）」）。
 *
 * <p>{@code dowob.xyz.blog.module.<A>..} 不得依賴 {@code dowob.xyz.blog.module.<B>..}（A≠B）：
 * 模組之間只能透過 facade 與共用事件契約（皆位於 blog-infrastructure）溝通。
 * 「依賴」涵蓋 ArchUnit 的所有直接依賴種類（import 的型別、欄位、方法簽章與呼叫、繼承、標註、泛型參數等）。</p>
 *
 * <p><b>例外清單起步、各模組階段逐步清空</b>：現存違規列入 {@link #EXEMPT}，
 * key 以「來源類別 -&gt; 目標類別」為單位（內部類別、匿名類別歸併到最外層類別），
 * value 必須以預計清除的階段（{@code M1}～{@code M8} 或 {@code Z}＝尚未排定）開頭。
 * 各模組 PR 清掉自己的條目；<b>收尾階段清單必須為空</b>。</p>
 *
 * <p><b>為何附防死條目與反向驗證</b>：見 {@link EndpointAuthorizationTest}——
 * 清單會腐爛、守衛也可能壞掉，故兩者都以測試驗證。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class CrossModuleImportTest {

    /** 業務模組根套件，其下第一層為模組名 */
    private static final String MODULE_ROOT = "dowob.xyz.blog.module";

    /** 反向驗證 fixture 的模組根套件 */
    private static final String FIXTURE_MODULE_ROOT = "dowob.xyz.blog.architecture.fixture.moduleimport";

    /** 例外清單 value 的格式：以預計清除階段開頭 */
    private static final Pattern STAGE_PREFIX = Pattern.compile("^(M[1-8]|Z)[：:].+");

    /** key 與 value 之間的箭頭 */
    private static final String ARROW = " -> ";

    /**
     * 現存跨模組依賴的例外清單：{@code 來源類別 -> 目標類別} → 「階段：說明」。
     */
    private static final Map<String, String> EXEMPT = Map.ofEntries(
            // 預計 M2（reading 階段）：改走 ArticleFacade.getSummariesByUuids，回應 JSON 不變
            Map.entry("dowob.xyz.blog.module.reading.controller.BookmarkController"
                    + ARROW + "dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse",
                    "M2：reading 階段改用 facade 回傳型別"),
            Map.entry("dowob.xyz.blog.module.reading.service.BookmarkQueryService"
                    + ARROW + "dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse",
                    "M2：reading 階段改用 facade 回傳型別"),
            Map.entry("dowob.xyz.blog.module.reading.service.BookmarkQueryService"
                    + ARROW + "dowob.xyz.blog.module.article.service.ArticleQueryService",
                    "M2：reading 階段改走 ArticleFacade"),
            // 預計 M4（series 階段）：移除對 ArticleQueryService 的直接依賴，改走 ArticleFacade
            Map.entry("dowob.xyz.blog.module.series.model.dto.response.SeriesDetailResponse"
                    + ARROW + "dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse",
                    "M4：series 階段改用 facade 回傳型別"),
            Map.entry("dowob.xyz.blog.module.series.service.SeriesService"
                    + ARROW + "dowob.xyz.blog.module.article.model.dto.response.ArticleSummaryResponse",
                    "M4：series 階段改用 facade 回傳型別"),
            Map.entry("dowob.xyz.blog.module.series.service.SeriesService"
                    + ARROW + "dowob.xyz.blog.module.article.service.ArticleQueryService",
                    "M4：series 階段改走 ArticleFacade"),
            // 預計 M3（version 階段）：還原改由 ArticleFacade.applyRestoreContent 由 article 自行渲染
            Map.entry("dowob.xyz.blog.module.version.service.VersioningService"
                    + ARROW + "dowob.xyz.blog.module.article.service.ArticleMarkdownRenderer",
                    "M3：version 階段不再 import 渲染器"),
            Map.entry("dowob.xyz.blog.module.version.service.VersioningService"
                    + ARROW + "dowob.xyz.blog.module.article.service.ArticleTocCodec",
                    "M3：version 階段不再 import 渲染器"),
            Map.entry("dowob.xyz.blog.module.version.service.VersioningService"
                    + ARROW + "dowob.xyz.blog.module.article.service.RenderResult",
                    "M3：version 階段不再 import 渲染器"));

    @Test
    @DisplayName("模組不得依賴他模組的類別（例外清單以外不得有）")
    void moduleMustNotDependOnAnotherModule() {
        List<String> violations = findCrossModuleDependencies(importProduction(), MODULE_ROOT).stream()
                .filter(v -> !EXEMPT.containsKey(v))
                .toList();

        assertThat(violations)
                .as("模組之間只能透過 facade 與共用事件契約（blog-infrastructure）溝通，不得直接 import 他模組的類別。"
                  + "若為尚未改造的模組，加入 EXEMPT（key 為 來源類別 -> 目標類別）並標明清除階段。")
                .isEmpty();
    }

    @Test
    @DisplayName("防死條目：例外清單的每一項都必須仍是實際的跨模組依賴")
    void exemptListMustNotContainStaleEntries() {
        assertThat(EXEMPT.keySet())
                .as("條目對應的依賴若已被移除（改走 facade／共用事件）或類別已刪除，就成了死條目，"
                  + "會靜靜掩蓋日後同一組來源→目標的真違規，請移除。")
                .allSatisfy(exempt -> assertThat(findCrossModuleDependencies(importProduction(), MODULE_ROOT))
                        .contains(exempt));
    }

    @Test
    @DisplayName("例外清單每個條目都必須標明預計清除的階段（M1～M8 或 Z）")
    void everyExemptEntryMustDeclareClearingStage() {
        assertThat(EXEMPT.values()).allSatisfy(v -> assertThat(v).matches(STAGE_PREFIX));
    }

    @Test
    @DisplayName("防假陰性：正式碼掃描必須實際掃到多個模組，否則守衛是空轉的")
    void productionScanMustActuallyFindModules() {
        List<String> modules = importProduction().stream()
                .map(c -> moduleOf(c.getPackageName(), MODULE_ROOT))
                .filter(m -> m != null)
                .distinct()
                .toList();

        assertThat(modules)
                .as("若掃不到模組類別，「無違規」就毫無意義")
                .contains("article", "comment", "user");
    }

    @Test
    @DisplayName("反向驗證：守衛抓得到跨模組依賴，且不誤判同模組依賴")
    void guardDetectsCrossModuleDependency() {
        List<String> violations = findCrossModuleDependencies(
                new ClassFileImporter().importPackages(FIXTURE_MODULE_ROOT), FIXTURE_MODULE_ROOT);

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .containsExactly(FIXTURE_MODULE_ROOT + ".beta.BetaViolator" + ARROW
                        + FIXTURE_MODULE_ROOT + ".alpha.AlphaTarget");
    }

    /**
     * 匯入正式碼的業務模組（不含測試 source）。
     *
     * @return 待掃描的類集合
     */
    private static JavaClasses importProduction() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(MODULE_ROOT);
    }

    /**
     * 找出所有跨模組的直接依賴。
     *
     * @param classes    待掃描的類集合
     * @param moduleRoot 模組根套件，其下第一層為模組名
     * @return {@code 來源類別 -> 目標類別}（內部／匿名類別歸併到最外層類別；排序、去重）
     */
    private static List<String> findCrossModuleDependencies(JavaClasses classes, String moduleRoot) {
        return classes.stream()
                .filter(c -> moduleOf(c.getPackageName(), moduleRoot) != null)
                .flatMap(c -> c.getDirectDependenciesFromSelf().stream())
                .filter(d -> isCrossModule(d, moduleRoot))
                .map(d -> topLevelName(d.getOriginClass()) + ARROW + topLevelName(d.getTargetClass()))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 判斷依賴是否由一個模組指向另一個模組。
     *
     * @param dependency 待判斷的依賴
     * @param moduleRoot 模組根套件
     * @return 來源與目標分屬不同模組回傳 true
     */
    private static boolean isCrossModule(Dependency dependency, String moduleRoot) {
        String from = moduleOf(dependency.getOriginClass().getPackageName(), moduleRoot);
        String to = moduleOf(dependency.getTargetClass().getPackageName(), moduleRoot);
        return from != null && to != null && !from.equals(to);
    }

    /**
     * 取得類別最外層類別的全名（內部類別、匿名類別歸併）。
     *
     * @param javaClass 類別
     * @return 最外層類別全名
     */
    private static String topLevelName(JavaClass javaClass) {
        String name = javaClass.getName();
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }

    /**
     * 取得套件所屬的模組名（模組根套件下第一層）。
     *
     * @param packageName 套件名
     * @param moduleRoot  模組根套件
     * @return 模組名；不在任何模組之下回傳 null
     */
    private static String moduleOf(String packageName, String moduleRoot) {
        if (!packageName.startsWith(moduleRoot + ".")) {
            return null;
        }
        String rest = packageName.substring(moduleRoot.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }
}
