package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件契約守衛（設計 {@code 2026-10-24-cross-module-uuid-design.md} §3.1「事件契約位置」）。
 *
 * <p>所有事件類別（類名以 {@code Event} 結尾、位於 {@code ..event..} 套件，
 * 含 blog-module-user 的 {@code model.event}）必須遵守三條規則：</p>
 * <ol>
 *   <li>(a) 必須有 {@code java.util.UUID eventId} 欄位（consumer 冪等的前提）；</li>
 *   <li>(b) 不得有 {@code Long}／{@code long} 欄位或 record component（跨模組識別一律 UUID）；</li>
 *   <li>(c) <b>跨模組事件</b>必須位於 {@code dowob.xyz.blog.infrastructure.event..}。
 *       「跨模組」以實際依賴判定：事件類別被<b>另一個模組</b>的類別依賴即是；
 *       只在自己模組內使用的內部事件不受 (c) 約束。</li>
 * </ol>
 *
 * <p><b>例外清單起步、各模組階段逐步清空</b>：三條規則各有一份例外清單，key 為類別全名
 * （規則 (b) 為 {@code 類別全名#欄位}），value 必須以預計清除的階段（{@code M1}～{@code M8}
 * 或 {@code Z}＝尚未排定）開頭。各模組 PR 清掉自己的條目；<b>收尾階段三份清單必須為空</b>。</p>
 *
 * <p><b>為何附防死條目與反向驗證</b>：與 {@link EndpointAuthorizationTest} 同理——
 * 清單會腐爛（欄位已移除但條目還在，會靜靜掩蓋日後同名的真違規），
 * 守衛也可能壞掉而「掃不到」被誤認為「沒違規」，故兩者都以測試驗證。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class EventContractTest {

    /** 正式碼根套件 */
    private static final String PRODUCTION_ROOT = "dowob.xyz.blog";

    /** 業務模組根套件，其下第一層為模組名 */
    private static final String MODULE_ROOT = "dowob.xyz.blog.module";

    /** 跨模組事件的共用契約位置 */
    private static final String SHARED_EVENT_PACKAGE = "dowob.xyz.blog.infrastructure.event";

    /** 反向驗證 fixture 的模組根套件 */
    private static final String FIXTURE_MODULE_ROOT = "dowob.xyz.blog.architecture.fixture.eventcontract";

    /** 反向驗證 fixture 的共用契約位置 */
    private static final String FIXTURE_SHARED_EVENT_PACKAGE = FIXTURE_MODULE_ROOT + ".shared.event";

    /** 例外清單 value 的格式：以預計清除階段開頭 */
    private static final Pattern STAGE_PREFIX = Pattern.compile("^(M[1-8]|Z)[：:].+");

    /**
     * 規則 (a) 例外清單：缺少 {@code UUID eventId} 的事件。key 為類別全名，value 為「階段：說明」。
     */
    private static final Map<String, String> MISSING_EVENT_ID_EXEMPT = Map.ofEntries(
            // 預計 M5（file 階段）：補 eventId，ThumbnailConsumer 冪等
            Map.entry("dowob.xyz.blog.module.file.event.ImageUploadedEvent",
                    "M5：file 模組內部事件，file 階段補 eventId 並讓 ThumbnailConsumer 冪等"),
            // 預計 M8（user 階段）：模組內事件（註冊、重設密碼）一併改 uuid＋eventId
            Map.entry("dowob.xyz.blog.module.user.model.event.UserRegisteredEvent",
                    "M8：user 模組內部事件，user 階段補 eventId"),
            Map.entry("dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent",
                    "M8：user 模組內部事件，user 階段補 eventId"));

    /**
     * 規則 (b) 例外清單：仍含 Long 欄位的事件。key 為 {@code 類別全名#欄位}，value 為「階段：說明」。
     */
    private static final Map<String, String> LONG_FIELD_EXEMPT = Map.ofEntries(
            // 預計 M3（version 階段）：ArticleContentChangedEvent 的 consumer（version）改讀 uuid 後移除
            Map.entry("dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent#articleId",
                    "M3：過渡欄位，version 階段改讀 articleUuid 後移除"),
            Map.entry("dowob.xyz.blog.infrastructure.event.ArticleContentChangedEvent#authorId",
                    "M3：過渡欄位，version 階段改讀 authorUuid 後移除"),
            // 預計 M4／M6：ArticleDeletedEvent 的 Long 欄位依 owner 模組階段分批移除
            Map.entry("dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent#articleId",
                    "M4：過渡欄位，series 階段（最後一個讀取者）改讀 articleUuid 後移除"),
            Map.entry("dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent#seriesId",
                    "M4：過渡欄位，series 階段改讀 seriesUuid 後移除"),
            Map.entry("dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent#authorId",
                    "M6：過渡欄位，article 階段發送端只帶 authorUuid 後移除"),
            // 預計 M6（article 階段）：發送端改只帶 uuid；search／recommend 目前仍讀取
            Map.entry("dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent#authorId",
                    "M6：過渡欄位，article 階段發送端只帶 authorUuid 後移除"),
            Map.entry("dowob.xyz.blog.infrastructure.event.ArticleUpdatedEvent#authorId",
                    "M6：過渡欄位，article 階段發送端只帶 authorUuid 後移除"),
            // 預計 M8（user 階段）：模組內事件改 uuid
            Map.entry("dowob.xyz.blog.module.user.model.event.UserRegisteredEvent#userId",
                    "M8：user 模組內部事件，user 階段改 userUuid"),
            Map.entry("dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent#userId",
                    "M8：user 模組內部事件，user 階段改 userUuid"));

    /**
     * 規則 (c) 例外清單：被他模組使用卻不在共用契約位置的事件。key 為類別全名，value 為「階段：說明」。
     */
    private static final Map<String, String> MISPLACED_EXEMPT = Map.of();

    @Test
    @DisplayName("規則 (a)：事件必須有 UUID eventId（例外清單以外不得缺）")
    void eventMustHaveUuidEventId() {
        List<String> violations = findMissingEventId(importProduction()).stream()
                .filter(v -> !MISSING_EVENT_ID_EXEMPT.containsKey(v))
                .toList();

        assertThat(violations)
                .as("事件缺少 java.util.UUID eventId，consumer 無法冪等去重。"
                  + "請補上 eventId；若為尚未改造的模組，加入 MISSING_EVENT_ID_EXEMPT 並標明清除階段。")
                .isEmpty();
    }

    @Test
    @DisplayName("規則 (b)：事件不得有 Long/long 欄位（例外清單以外不得有）")
    void eventMustNotHaveLongFields() {
        List<String> violations = findLongFields(importProduction()).stream()
                .filter(v -> !LONG_FIELD_EXEMPT.containsKey(v))
                .toList();

        assertThat(violations)
                .as("事件含 Long/long 欄位，跨模組識別一律改用 UUID。"
                  + "若為過渡欄位，加入 LONG_FIELD_EXEMPT（key 為 類別全名#欄位）並標明清除階段。")
                .isEmpty();
    }

    @Test
    @DisplayName("規則 (c)：跨模組事件必須位於 infrastructure.event（例外清單以外不得放錯）")
    void crossModuleEventMustResideInSharedPackage() {
        List<String> violations = findMisplacedCrossModuleEvents(importProduction(), MODULE_ROOT, SHARED_EVENT_PACKAGE)
                .stream()
                .filter(v -> !MISPLACED_EXEMPT.containsKey(v))
                .toList();

        assertThat(violations)
                .as("事件被另一個模組使用，即為跨模組事件，必須放在 " + SHARED_EVENT_PACKAGE + "。"
                  + "若暫時無法搬移，加入 MISPLACED_EXEMPT 並標明清除階段。")
                .isEmpty();
    }

    @Test
    @DisplayName("防死條目 (a)：例外清單的每一項都必須仍缺 eventId")
    void missingEventIdExemptListMustNotContainStaleEntries() {
        assertThat(MISSING_EVENT_ID_EXEMPT.keySet())
                .as("條目對應的事件若已補上 eventId 或已被刪除，就成了死條目，請移除。")
                .allSatisfy(exempt -> assertThat(findMissingEventId(importProduction())).contains(exempt));
    }

    @Test
    @DisplayName("防死條目 (b)：例外清單的每一項都必須仍是實際的 Long 欄位")
    void longFieldExemptListMustNotContainStaleEntries() {
        assertThat(LONG_FIELD_EXEMPT.keySet())
                .as("條目對應的欄位若已改為 UUID 或已被刪除，就成了死條目，請移除。")
                .allSatisfy(exempt -> assertThat(findLongFields(importProduction())).contains(exempt));
    }

    @Test
    @DisplayName("防死條目 (c)：例外清單的每一項都必須仍是放錯位置的跨模組事件")
    void misplacedExemptListMustNotContainStaleEntries() {
        assertThat(MISPLACED_EXEMPT.keySet())
                .as("條目對應的事件若已搬到共用位置或不再被他模組使用，就成了死條目，請移除。")
                .allSatisfy(exempt -> assertThat(
                        findMisplacedCrossModuleEvents(importProduction(), MODULE_ROOT, SHARED_EVENT_PACKAGE))
                        .contains(exempt));
    }

    @Test
    @DisplayName("例外清單每個條目都必須標明預計清除的階段（M1～M8 或 Z）")
    void everyExemptEntryMustDeclareClearingStage() {
        assertThat(MISSING_EVENT_ID_EXEMPT.values()).allSatisfy(v -> assertThat(v).matches(STAGE_PREFIX));
        assertThat(LONG_FIELD_EXEMPT.values()).allSatisfy(v -> assertThat(v).matches(STAGE_PREFIX));
        assertThat(MISPLACED_EXEMPT.values()).allSatisfy(v -> assertThat(v).matches(STAGE_PREFIX));
    }

    @Test
    @DisplayName("防假陰性：正式碼掃描必須實際掃到事件，否則守衛是空轉的")
    void productionScanMustActuallyFindEvents() {
        assertThat(findEventClasses(importProduction()))
                .as("若掃不到任何事件類別，上面的「無違規」就毫無意義。"
                  + "至少應包含共用契約位置與 user 模組 model.event 兩處的事件。")
                .anySatisfy(c -> assertThat(c.getPackageName()).isEqualTo(SHARED_EVENT_PACKAGE))
                .anySatisfy(c -> assertThat(c.getPackageName()).startsWith(MODULE_ROOT + ".user"));
    }

    @Test
    @DisplayName("反向驗證 (a)：守衛抓得到缺少 eventId 的事件，且不誤判合規事件")
    void guardDetectsEventWithoutEventId() {
        List<String> violations = findMissingEventId(importFixtures());

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .contains(fixtureName("alpha.event.NoEventIdEvent"))
                .as("有 UUID eventId 者皆合規，被抓到即為假陽性")
                .doesNotContain(fixtureName("alpha.event.CompliantEvent"))
                .doesNotContain(fixtureName("alpha.event.LongFieldEvent"));
    }

    @Test
    @DisplayName("反向驗證 (b)：守衛抓得到 Long 與 long 欄位，且不誤判其他欄位")
    void guardDetectsLongFields() {
        List<String> violations = findLongFields(importFixtures());

        assertThat(violations)
                .contains(fixtureName("alpha.event.LongFieldEvent") + "#articleId")
                .contains(fixtureName("alpha.event.LongFieldEvent") + "#count")
                .doesNotContain(fixtureName("alpha.event.LongFieldEvent") + "#eventId")
                .doesNotContain(fixtureName("alpha.event.LongFieldEvent") + "#label")
                .noneMatch(v -> v.startsWith(fixtureName("alpha.event.CompliantEvent")));
    }

    @Test
    @DisplayName("反向驗證 (c)：守衛抓得到放在模組內卻被他模組使用的事件，且不誤判內部事件與共用事件")
    void guardDetectsMisplacedCrossModuleEvent() {
        List<String> violations = findMisplacedCrossModuleEvents(
                importFixtures(), FIXTURE_MODULE_ROOT, FIXTURE_SHARED_EVENT_PACKAGE);

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .contains(fixtureName("alpha.event.MisplacedCrossModuleEvent"))
                .as("只在本模組內使用的內部事件不受規則 (c) 約束")
                .doesNotContain(fixtureName("alpha.event.CompliantEvent"))
                .as("位於共用契約位置的事件被他模組使用是合規的")
                .doesNotContain(fixtureName("shared.event.SharedEvent"));
    }

    /**
     * 組出 fixture 類別的全名。
     *
     * @param relative 相對於 fixture 模組根套件的類別路徑，如 {@code alpha.event.CompliantEvent}
     * @return 類別全名
     */
    private static String fixtureName(String relative) {
        return FIXTURE_MODULE_ROOT + "." + relative;
    }

    /**
     * 匯入正式碼（不含測試 source）。
     *
     * @return 待掃描的類集合
     */
    private static JavaClasses importProduction() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PRODUCTION_ROOT);
    }

    /**
     * 匯入反向驗證用的 fixture（位於測試 source）。
     *
     * @return fixture 類集合
     */
    private static JavaClasses importFixtures() {
        return new ClassFileImporter().importPackages(FIXTURE_MODULE_ROOT);
    }

    /**
     * 找出所有事件類別：類名以 {@code Event} 結尾且位於 {@code ..event..} 套件。
     *
     * @param classes 待掃描的類集合
     * @return 事件類別
     */
    private static List<JavaClass> findEventClasses(JavaClasses classes) {
        return classes.stream()
                .filter(c -> c.getSimpleName().endsWith("Event"))
                .filter(c -> JavaClass.Predicates.resideInAPackage("..event..").test(c))
                .toList();
    }

    /**
     * 規則 (a)：找出沒有 {@code java.util.UUID eventId} 欄位的事件。
     *
     * @param classes 待掃描的類集合
     * @return 違規事件的類別全名（排序、去重）
     */
    private static List<String> findMissingEventId(JavaClasses classes) {
        return findEventClasses(classes).stream()
                .filter(c -> c.getFields().stream().noneMatch(EventContractTest::isUuidEventId))
                .map(JavaClass::getName)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 判斷欄位是否為 {@code java.util.UUID eventId}。
     *
     * @param field 待判斷的欄位
     * @return 名稱為 eventId 且型別為 UUID 回傳 true
     */
    private static boolean isUuidEventId(JavaField field) {
        return field.getName().equals("eventId") && field.getRawType().isEquivalentTo(java.util.UUID.class);
    }

    /**
     * 規則 (b)：找出事件中型別為 {@code Long} 或 {@code long} 的欄位（record component 亦會編成欄位）。
     *
     * @param classes 待掃描的類集合
     * @return {@code 類別全名#欄位}（排序、去重）
     */
    private static List<String> findLongFields(JavaClasses classes) {
        return findEventClasses(classes).stream()
                .flatMap(c -> c.getFields().stream())
                .filter(f -> f.getRawType().isEquivalentTo(Long.class) || f.getRawType().isEquivalentTo(long.class))
                .map(f -> f.getOwner().getName() + "#" + f.getName())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 規則 (c)：找出不在共用契約位置、卻被另一個模組的類別依賴的事件。
     *
     * @param classes       待掃描的類集合
     * @param moduleRoot    模組根套件，其下第一層為模組名
     * @param sharedPackage 共用契約套件（含子套件）
     * @return 違規事件的類別全名（排序、去重）
     */
    private static List<String> findMisplacedCrossModuleEvents(
            JavaClasses classes, String moduleRoot, String sharedPackage) {
        return findEventClasses(classes).stream()
                .filter(c -> !isInPackage(c.getPackageName(), sharedPackage))
                .filter(c -> {
                    String ownModule = moduleOf(c.getPackageName(), moduleRoot);
                    return c.getDirectDependenciesToSelf().stream()
                            .map(Dependency::getOriginClass)
                            .map(o -> moduleOf(o.getPackageName(), moduleRoot))
                            .anyMatch(m -> m != null && !m.equals(ownModule));
                })
                .map(JavaClass::getName)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 判斷套件是否等於或位於指定套件之下。
     *
     * @param packageName 待判斷的套件名
     * @param root        根套件
     * @return 位於其下回傳 true
     */
    private static boolean isInPackage(String packageName, String root) {
        return packageName.equals(root) || packageName.startsWith(root + ".");
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
