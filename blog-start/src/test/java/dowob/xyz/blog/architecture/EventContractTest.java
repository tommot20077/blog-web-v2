package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件契約守衛（設計 {@code 2026-10-24-cross-module-uuid-design.md} §3.1「事件契約位置」）。
 *
 * <p><b>守衛對象（事件集合）是三者聯集</b>，避免靠套件名或類名單一特徵就漏網：</p>
 * <ol>
 *   <li>類名以 {@code Event} 結尾的類別（<b>不限</b> {@code ..event..} 套件，{@code events} 複數套件也算）；</li>
 *   <li>所有 {@code @RabbitListener} 方法的訊息參數型別（排除 {@code Channel} 與 {@code @Header} 參數）——
 *       會納入 search 的 {@code listener/dto/*Message} 這類鏡像 DTO；</li>
 *   <li>{@code dowob.xyz.blog.infrastructure.event..} 下除 {@code ArticleEventContract} 以外的所有類別
 *       （含 {@code TagInfo} 這類承載型別與巢狀型別）。</li>
 * </ol>
 *
 * <p>規則（static 欄位一律不計——{@code serialVersionUID} 不該被誤判，{@code static UUID eventId} 也不該騙過 (a)）：</p>
 * <ol>
 *   <li>(a) 事件本體（集合 1、2）必須有 {@code java.util.UUID eventId} 欄位（consumer 冪等的前提）；
 *       集合 3 的承載型別（如 {@code TagInfo}）是事件的組成片段，不要求 eventId；</li>
 *   <li>(b) 整個事件集合（含父類別欄位與巢狀型別）不得有 Long／long——
 *       以 {@code getAllInvolvedRawTypes()} 判斷，{@code List<Long>}、{@code Long[]}、
 *       {@code Map<Long,String>}、{@code Optional<Long>} 都算；</li>
 *   <li>(c) <b>反向判準</b>：{@code dowob.xyz.blog.module.*..} 下的 {@code *Event} 類別只能是
 *       {@link #INTERNAL_EVENTS 登記過的模組內部事件}，新增任何其他的就紅
 *       （跨模組事件必須放 {@code infrastructure.event}，新增模組內事件須經人工確認後登記）。</li>
 * </ol>
 *
 * <p><b>沒有例外清單（Z2 起）</b>：重構期間 (a)(b) 曾各有一份例外清單逐模組清除現存違規，
 * M1～M8 完成後清單已為空，Z2 將「清單」這個機制本身刪除。往後要放行任何事件違規，
 * 必須修改本守衛的判定邏輯，該修改會出現在 PR diff 中被審查。
 * {@link #INTERNAL_EVENTS} 是登記簿而非例外（登記的是「確認過只有本模組使用」的內部事件），予以保留。</p>
 *
 * <p><b>為何附防死條目與反向驗證</b>：與 {@link EndpointAuthorizationTest} 同理——
 * 清單會腐爛，守衛也可能壞掉而「掃不到」被誤認為「沒違規」，故兩者都以測試驗證。</p>
 *
 * @author Yuan
 * @version 1.1
 */
class EventContractTest {

    /** 正式碼根套件 */
    private static final String PRODUCTION_ROOT = "dowob.xyz.blog";

    /** 業務模組根套件，其下第一層為模組名 */
    private static final String MODULE_ROOT = "dowob.xyz.blog.module";

    /** 跨模組事件的共用契約位置 */
    private static final String SHARED_EVENT_PACKAGE = "dowob.xyz.blog.infrastructure.event";

    /** 共用契約位置中不屬於事件的常數類別 */
    private static final String SHARED_NON_EVENT_CLASS = "ArticleEventContract";

    /** 反向驗證 fixture 的根套件 */
    private static final String FIXTURE_ROOT = "dowob.xyz.blog.architecture.fixture.eventcontract";

    /** 反向驗證 fixture 的模組根套件 */
    private static final String FIXTURE_MODULE_ROOT = FIXTURE_ROOT + ".modules";

    /** 反向驗證 fixture 的共用契約位置 */
    private static final String FIXTURE_SHARED_EVENT_PACKAGE = FIXTURE_ROOT + ".shared.event";

    /** 快取的正式碼匯入結果（匯入約數秒，各測試共用） */
    private static JavaClasses productionClasses;

    /**
     * 規則 (c) 登記簿：允許留在 {@code module.*..} 之下的<b>模組內部事件</b>（只有本模組自己發送與消費）。
     * key 為類別全名，value 為登記理由。新增模組內事件前必須先確認它不會被他模組使用，再登記於此。
     */
    private static final Map<String, String> INTERNAL_EVENTS = Map.ofEntries(
            Map.entry("dowob.xyz.blog.module.article.event.ArticleViewedEvent",
                    "article 內部：瀏覽計數（ViewCountConsumer 同模組消費）"),
            Map.entry("dowob.xyz.blog.module.file.event.ImageUploadedEvent",
                    "file 內部：縮圖生成（ThumbnailConsumer 同模組消費）"),
            Map.entry("dowob.xyz.blog.module.user.model.event.UserRegisteredEvent",
                    "user 內部：寄送驗證信（EmailVerificationConsumer 同模組消費）"),
            Map.entry("dowob.xyz.blog.module.user.model.event.UserPasswordResetRequestedEvent",
                    "user 內部：寄送重設密碼信（PasswordResetConsumer 同模組消費）"));

    @Test
    @DisplayName("規則 (a)：事件必須有 UUID eventId（無例外清單）")
    void eventMustHaveUuidEventId() {
        assertThat(findMissingEventId(importProduction()))
                .as("事件缺少 java.util.UUID eventId，consumer 無法冪等去重。請補上 eventId。")
                .isEmpty();
    }

    @Test
    @DisplayName("規則 (b)：事件不得有 Long/long 欄位（含泛型、陣列、父類別；無例外清單）")
    void eventMustNotHaveLongFields() {
        assertThat(findLongFields(importProduction(), SHARED_EVENT_PACKAGE))
                .as("事件含 Long/long 欄位，跨模組識別一律改用 UUID。")
                .isEmpty();
    }

    @Test
    @DisplayName("規則 (c)：module 套件下的事件只能是登記過的模組內部事件")
    void moduleResidentEventMustBeRegisteredAsInternal() {
        List<String> violations = findUnregisteredModuleEvents(
                importProduction(), MODULE_ROOT, INTERNAL_EVENTS.keySet());

        assertThat(violations)
                .as("module.*.. 下出現未登記的事件。跨模組事件必須放在 " + SHARED_EVENT_PACKAGE + "；"
                  + "若確為只有本模組使用的內部事件，請確認後加入 INTERNAL_EVENTS。")
                .isEmpty();
    }

    @Test
    @DisplayName("防死條目 (c)：登記簿的每一項都必須仍是 module 套件下的事件")
    void internalEventRegistryMustNotContainStaleEntries() {
        List<String> actual = findModuleResidentEvents(importProduction(), MODULE_ROOT);

        assertThat(INTERNAL_EVENTS.keySet())
                .as("登記的事件若已被刪除或搬到共用位置，就成了死條目，請移除。")
                .allSatisfy(registered -> assertThat(actual).contains(registered));
    }

    @Test
    @DisplayName("防假陰性：正式碼掃描必須在 article、file、infrastructure、user 都掃到事件")
    void productionScanMustActuallyFindEvents() {
        Set<String> origins = describeOrigins(importProduction(), MODULE_ROOT, SHARED_EVENT_PACKAGE);

        assertThat(origins)
                .as("若某個來源掃不到任何事件，該處的「無違規」就毫無意義（守衛空轉）")
                .contains("article", "file", "infrastructure", "user");
    }

    @Test
    @DisplayName("tripwire：正式碼掃描不得掃進 architecture.fixture（否則 fixture 的故意違規會污染結果）")
    void productionScanMustNotContainFixtures() {
        assertThat(findFixtureLeaks(importProduction()))
                .as("正式碼匯入若包含測試 source 的 fixture，故意違規的 fixture 會讓守衛誤報或掩蓋問題")
                .isEmpty();
    }

    @Test
    @DisplayName("反向驗證 tripwire：fixture 掃描確實能被 tripwire 認出")
    void tripwireDetectsFixtureClasses() {
        assertThat(findFixtureLeaks(importFixtures()))
                .as("tripwire 若認不出 fixture，上面的「正式碼無 fixture」就沒有意義")
                .isNotEmpty();
    }

    @Test
    @DisplayName("反向驗證 (a)：抓得到缺 eventId（含 events 複數套件、listener 參數、static eventId），不誤判合規者")
    void guardDetectsEventWithoutEventId() {
        List<String> violations = findMissingEventId(importFixtures());

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .contains(modulePart("alpha.event.NoEventIdEvent"))
                .as("events 複數套件下的 *Event 也是事件")
                .contains(modulePart("alpha.events.PluralPackageEvent"))
                .as("static UUID eventId 不算事件欄位，不得騙過規則 (a)")
                .contains(modulePart("alpha.event.StaticFieldEvent"))
                .as("@RabbitListener 的訊息參數（類名不是 Event）也是事件")
                .contains(modulePart("alpha.dto.ListenerMessage"))
                .as("有 UUID eventId 者、非事件的一般 DTO、Channel／@Header 參數、共用承載型別皆不該被抓")
                .doesNotContain(modulePart("alpha.event.CompliantEvent"))
                .doesNotContain(modulePart("alpha.event.LongFieldEvent"))
                .doesNotContain(modulePart("alpha.dto.UnrelatedDto"))
                .doesNotContain("com.rabbitmq.client.Channel")
                .doesNotContain("long")
                .doesNotContain(FIXTURE_SHARED_EVENT_PACKAGE + ".PayloadCarrier");
    }

    @Test
    @DisplayName("反向驗證 (b)：抓得到 Long/long（含泛型、陣列、Optional、Map、父類別、listener 參數、共用承載型別）")
    void guardDetectsLongFields() {
        List<String> violations = findLongFields(importFixtures(), FIXTURE_SHARED_EVENT_PACKAGE);
        String generic = modulePart("alpha.event.GenericLongEvent");

        assertThat(violations)
                .contains(modulePart("alpha.event.LongFieldEvent") + "#articleId")
                .contains(modulePart("alpha.event.LongFieldEvent") + "#count")
                .as("Long 藏在泛型／陣列／Optional／Map 裡也要抓")
                .contains(generic + "#list", generic + "#set", generic + "#array", generic + "#primArr",
                        generic + "#map", generic + "#opt")
                .as("父類別的 Long 欄位要抓")
                .contains(modulePart("alpha.base.LongBase") + "#id")
                .as("listener 參數與共用位置承載型別的 Long 要抓")
                .contains(modulePart("alpha.dto.ListenerMessage") + "#authorId")
                .contains(FIXTURE_SHARED_EVENT_PACKAGE + ".PayloadCarrier#ids")
                .as("合規欄位、static 欄位、非事件 DTO 不得誤判")
                .doesNotContain(generic + "#ok", generic + "#eventId")
                .doesNotContain(modulePart("alpha.event.LongFieldEvent") + "#eventId")
                .doesNotContain(modulePart("alpha.event.LongFieldEvent") + "#label")
                .doesNotContain(modulePart("alpha.event.StaticFieldEvent") + "#serialVersionUID")
                .doesNotContain(modulePart("alpha.dto.UnrelatedDto") + "#id");
    }

    @Test
    @DisplayName("反向驗證 (c)：抓得到 module 下未登記的事件，不誤判已登記者與共用位置事件")
    void guardDetectsUnregisteredModuleEvent() {
        List<String> violations = findUnregisteredModuleEvents(
                importFixtures(), FIXTURE_MODULE_ROOT, Set.of(modulePart("alpha.event.CompliantEvent")));

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .contains(modulePart("alpha.event.UnregisteredModuleEvent"))
                .contains(modulePart("alpha.events.PluralPackageEvent"))
                .as("已登記者不誤判")
                .doesNotContain(modulePart("alpha.event.CompliantEvent"))
                .as("位於共用契約位置（不在 module 之下）者不受規則 (c) 約束")
                .doesNotContain(FIXTURE_SHARED_EVENT_PACKAGE + ".SharedEvent");
    }

    /**
     * 組出 fixture 模組內類別的全名。
     *
     * @param relative 相對於 fixture 模組根套件的類別路徑，如 {@code alpha.event.CompliantEvent}
     * @return 類別全名
     */
    private static String modulePart(String relative) {
        return FIXTURE_MODULE_ROOT + "." + relative;
    }

    /**
     * 匯入正式碼（不含測試 source），結果快取供各測試共用。
     *
     * @return 待掃描的類集合
     */
    private static synchronized JavaClasses importProduction() {
        if (productionClasses == null) {
            productionClasses = new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(PRODUCTION_ROOT);
        }
        return productionClasses;
    }

    /**
     * 匯入反向驗證用的 fixture（位於測試 source）。
     *
     * @return fixture 類集合
     */
    private static JavaClasses importFixtures() {
        return new ClassFileImporter().importPackages(FIXTURE_ROOT);
    }

    // ───────────────────────── 守衛判定邏輯 ─────────────────────────

    /** {@code @RabbitListener} 的全限定名 */
    private static final String RABBIT_LISTENER = "org.springframework.amqp.rabbit.annotation.RabbitListener";

    /** AMQP channel 的全限定名（listener 參數中不是訊息本體） */
    private static final String AMQP_CHANNEL = "com.rabbitmq.client.Channel";

    /** {@code @Header} 的全限定名（標註此註解的 listener 參數不是訊息本體） */
    private static final String MESSAGE_HEADER = "org.springframework.messaging.handler.annotation.Header";

    /**
     * 事件集合 (1)：類名以 {@code Event} 結尾的類別，<b>不限套件</b>。
     *
     * @param classes 待掃描的類集合
     * @return 事件類別
     */
    private static List<JavaClass> findNamedEvents(JavaClasses classes) {
        return classes.stream()
                .filter(c -> c.getSimpleName().endsWith("Event"))
                .toList();
    }

    /**
     * 事件集合 (2)：所有 {@code @RabbitListener} 方法的訊息參數型別（排除 Channel 與 {@code @Header} 參數）。
     *
     * @param classes 待掃描的類集合
     * @return 訊息參數的類別
     */
    private static List<JavaClass> findListenerPayloads(JavaClasses classes) {
        return classes.stream()
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.isAnnotatedWith(RABBIT_LISTENER))
                .flatMap(m -> m.getParameters().stream())
                .filter(p -> !p.getRawType().getName().equals(AMQP_CHANNEL))
                .filter(p -> p.getAnnotations().stream()
                        .noneMatch(a -> a.getRawType().getName().equals(MESSAGE_HEADER)))
                .map(p -> p.getRawType())
                .toList();
    }

    /**
     * 事件本體 = 集合 (1) ∪ 集合 (2)（規則 (a) 的對象）。
     *
     * @param classes 待掃描的類集合
     * @return 事件本體類別（依類別全名去重）
     */
    private static List<JavaClass> findEventRoots(JavaClasses classes) {
        return List.copyOf(Stream.concat(findNamedEvents(classes).stream(), findListenerPayloads(classes).stream())
                .collect(Collectors.toMap(JavaClass::getName, c -> c, (x, y) -> x, LinkedHashMap::new))
                .values());
    }

    /**
     * 事件集合 (3)：共用契約位置下除常數類別以外的所有類別（含承載型別與巢狀型別）。
     *
     * @param classes       待掃描的類集合
     * @param sharedPackage 共用契約套件
     * @return 共用契約位置的類別
     */
    private static List<JavaClass> findSharedContractClasses(JavaClasses classes, String sharedPackage) {
        return classes.stream()
                .filter(c -> isInPackage(c.getPackageName(), sharedPackage))
                .filter(c -> !topLevelSimpleName(c).equals(SHARED_NON_EVENT_CLASS))
                .toList();
    }

    /**
     * 規則 (b) 的對象 = 三個集合聯集，再加上各事件本體的巢狀型別（如 {@code ArticlePublishedMessage.TagInfoMessage}）。
     *
     * @param classes       待掃描的類集合
     * @param sharedPackage 共用契約套件
     * @return 規則 (b) 要檢查的類別
     */
    private static List<JavaClass> findContractClasses(JavaClasses classes, String sharedPackage) {
        List<JavaClass> roots = findEventRoots(classes);
        Set<String> rootNames = new TreeSet<>();
        roots.forEach(r -> rootNames.add(r.getName()));
        Map<String, JavaClass> result = new LinkedHashMap<>();
        roots.forEach(r -> result.put(r.getName(), r));
        findSharedContractClasses(classes, sharedPackage).forEach(c -> result.put(c.getName(), c));
        classes.stream()
                .filter(c -> c.getName().indexOf('$') > 0
                        && rootNames.contains(c.getName().substring(0, c.getName().indexOf('$'))))
                .forEach(c -> result.put(c.getName(), c));
        return List.copyOf(result.values());
    }

    /**
     * 規則 (a)：找出沒有非 static 的 {@code java.util.UUID eventId} 欄位（含父類別）的事件本體。
     *
     * @param classes 待掃描的類集合
     * @return 違規事件的類別全名（排序、去重）
     */
    private static List<String> findMissingEventId(JavaClasses classes) {
        return findEventRoots(classes).stream()
                .filter(c -> c.getAllFields().stream()
                        .filter(f -> !isStatic(f))
                        .noneMatch(EventContractTest::isUuidEventId))
                .map(JavaClass::getName)
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
        return field.getName().equals("eventId") && field.getRawType().isEquivalentTo(UUID.class);
    }

    /**
     * 判斷欄位是否為 static。
     *
     * @param field 待判斷的欄位
     * @return static 欄位回傳 true
     */
    private static boolean isStatic(JavaField field) {
        return field.getModifiers().contains(JavaModifier.STATIC);
    }

    /**
     * 規則 (b)：找出事件集合中型別涉及 {@code Long} 或 {@code long} 的非 static 欄位（含父類別欄位）。
     *
     * <p>以 {@code getAllInvolvedRawTypes()} 判斷，泛型參數、陣列元素型別都算。</p>
     *
     * @param classes       待掃描的類集合
     * @param sharedPackage 共用契約套件
     * @return {@code 類別全名#欄位}（排序、去重）
     */
    private static List<String> findLongFields(JavaClasses classes, String sharedPackage) {
        return findContractClasses(classes, sharedPackage).stream()
                .flatMap(c -> c.getAllFields().stream())
                .filter(f -> !isStatic(f))
                .filter(f -> f.getType().getAllInvolvedRawTypes().stream()
                        .anyMatch(t -> t.isEquivalentTo(Long.class) || t.isEquivalentTo(long.class)))
                .map(f -> f.getOwner().getName() + "#" + f.getName())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 規則 (c) 的比對基礎：找出 module 套件下所有 {@code *Event} 類別。
     *
     * @param classes    待掃描的類集合
     * @param moduleRoot 模組根套件
     * @return 類別全名（排序、去重）
     */
    private static List<String> findModuleResidentEvents(JavaClasses classes, String moduleRoot) {
        return findNamedEvents(classes).stream()
                .filter(c -> moduleOf(c.getPackageName(), moduleRoot) != null)
                .map(JavaClass::getName)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 規則 (c)：找出 module 套件下未登記為內部事件的 {@code *Event} 類別。
     *
     * @param classes    待掃描的類集合
     * @param moduleRoot 模組根套件
     * @param registered 已登記的模組內部事件類別全名
     * @return 違規事件的類別全名（排序、去重）
     */
    private static List<String> findUnregisteredModuleEvents(
            JavaClasses classes, String moduleRoot, Set<String> registered) {
        return findModuleResidentEvents(classes, moduleRoot).stream()
                .filter(name -> !registered.contains(name))
                .toList();
    }

    /**
     * 防空轉用：列出事件集合涵蓋的來源（模組名，或共用位置的 {@code infrastructure}）。
     *
     * @param classes       待掃描的類集合
     * @param moduleRoot    模組根套件
     * @param sharedPackage 共用契約套件
     * @return 來源名稱集合
     */
    private static Set<String> describeOrigins(JavaClasses classes, String moduleRoot, String sharedPackage) {
        Set<String> origins = new TreeSet<>();
        for (JavaClass c : findContractClasses(classes, sharedPackage)) {
            String module = moduleOf(c.getPackageName(), moduleRoot);
            if (module != null) {
                origins.add(module);
            } else if (isInPackage(c.getPackageName(), sharedPackage)) {
                origins.add("infrastructure");
            }
        }
        return origins;
    }

    /**
     * tripwire：找出位於 {@code ..architecture.fixture..} 的類別。
     *
     * @param classes 待掃描的類集合
     * @return 類別全名（排序、去重）
     */
    private static List<String> findFixtureLeaks(JavaClasses classes) {
        return classes.stream()
                .filter(c -> JavaClass.Predicates.resideInAPackage("..architecture.fixture..").test(c))
                .map(JavaClass::getName)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 取得最外層類別的簡單名稱（巢狀類別歸併）。
     *
     * @param javaClass 類別
     * @return 最外層類別簡單名稱
     */
    private static String topLevelSimpleName(JavaClass javaClass) {
        String name = javaClass.getName();
        String top = name.indexOf('$') > 0 ? name.substring(0, name.indexOf('$')) : name;
        return top.substring(top.lastIndexOf('.') + 1);
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
