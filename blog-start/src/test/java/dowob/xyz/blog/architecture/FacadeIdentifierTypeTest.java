package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Facade 識別型別守衛（設計 {@code 2026-10-24-cross-module-uuid-design.md} §6 全域守衛 ①）。
 *
 * <p>跨模組識別一律是 UUID，facade（{@code dowob.xyz.blog.infrastructure.facade..}）是跨模組唯一的同步入口，
 * 因此 facade 的簽章與 DTO 不得再以 {@code Long} 表達「識別」。本守衛把重構終態釘住，防止回退：</p>
 * <ol>
 *   <li><b>參數</b>：facade 介面 public 方法中，名稱以 {@code Id}／{@code Ids}（含恰為 {@code id}、{@code ids}）
 *       結尾的參數，型別不得涉及 Long／long——包含 {@code Long[]}、{@code List<Long>}、{@code Set<Long>}、
 *       {@code Collection<Long>} 等任何泛型展開後出現 Long 的型別；</li>
 *   <li><b>回傳</b>：回傳型別（泛型容器如 List／Optional／Map value 一律展開）中不得出現以 Long 為 key 的 {@code Map}；</li>
 *   <li><b>DTO</b>：facade 套件（含 {@code dto} 子套件與巢狀類別）下所有 class／record 的欄位
 *       （record 即 component），名稱以 {@code Id}／{@code Ids} 結尾者不得涉及 Long 系型別。</li>
 * </ol>
 *
 * <p><b>白名單</b>：沒有。計數類欄位或參數（{@code count}、{@code total}、{@code limit}、{@code viewCount}）
 * 名稱不以 Id 結尾，本來就不會命中；{@code Map<UUID, Long>} 的 value 為計數也不在 (2) 的禁止範圍。
 * 若日後有必須保留 Long 識別的例外，請修改本守衛本身並在 PR 說明（審查看得到），不要另開例外清單。</p>
 *
 * <p><b>參數名稱的前提</b>：參數名取自 class 檔，需要 {@code -parameters} 編譯旗標（Spring Boot parent 預設開啟）。
 * 若旗標遺失，所有參數會變成 {@code arg0}、{@code arg1}，規則 (1) 將恆綠——
 * 因此 {@link #facadeParameterNamesMustBeReadableFromClassFiles()} 以斷言證明讀得到真實名稱。</p>
 *
 * <p><b>守衛的界限</b>：只檢查「名稱帶 Id」的識別。名稱與 Id 無關卻實為識別的 Long（例如 {@code long key}）
 * 無法由名稱規則認出，須靠審查。</p>
 *
 * <p><b>為何附反向驗證與防空轉</b>：見 {@link EndpointAuthorizationTest}——守衛掃不到違規有兩種可能（真的沒有或守衛壞了），
 * 故判定邏輯抽成 {@code find*} 方法，另以 {@code fixture.facadeidentifier} 下故意違規的 facade 證明抓得到，
 * 並斷言正式碼掃描確實掃到介面、方法、參數與 DTO 欄位。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class FacadeIdentifierTypeTest {

    /** 專案根套件：可達型別只追蹤本專案的類別（JDK、第三方函式庫不檢查） */
    private static final String PROJECT_ROOT = "dowob.xyz.blog";

    /** 正式碼 facade 根套件 */
    private static final String FACADE_PACKAGE = "dowob.xyz.blog.infrastructure.facade";

    /** 反向驗證 fixture 的 facade 根套件 */
    private static final String FIXTURE_FACADE_PACKAGE =
            "dowob.xyz.blog.architecture.fixture.facadeidentifier.facade";

    /**
     * 代表識別的名稱：恰為 {@code id}／{@code ids}，或以 {@code Id}／{@code Ids}／{@code ID}／{@code IDs} 結尾。
     *
     * <p>區分大小寫：{@code userUuid}、{@code articleUuids} 以小寫 {@code id} 結尾，不是 Id 命名，不會命中；
     * {@code valid}、{@code paid} 同理。</p>
     */
    private static final Pattern ID_NAME = Pattern.compile("^(id|ids)$|^.+(Id|Ids|ID|IDs)$");

    /** 反向驗證 fixture：位於 facade 套件之外、但被 facade 方法觸及的 DTO 所在套件 */
    private static final String FIXTURE_OUTSIDE_PACKAGE =
            "dowob.xyz.blog.architecture.fixture.facadeidentifier.outside";

    /** 編譯器未保留參數名時的合成名稱，如 {@code arg0} */
    private static final Pattern SYNTHETIC_PARAMETER_NAME = Pattern.compile("^arg\\d+$");

    @Test
    @DisplayName("facade 方法中名稱以 Id/Ids 結尾的參數不得為 Long 系型別")
    void facadeIdParametersMustNotBeLong() {
        assertThat(findParameterViolations(productionFacadeClasses(), FACADE_PACKAGE))
                .as("跨模組識別一律是 UUID。facade 參數若名稱帶 Id 卻是 Long/long/Collection<Long>，代表識別型別退回舊制。"
                  + "請改用 UUID（參數命名 xxxUuid／xxxUuids）。")
                .isEmpty();
    }

    @Test
    @DisplayName("facade 方法的回傳型別不得含以 Long 為 key 的 Map")
    void facadeReturnTypesMustNotBeMapKeyedByLong() {
        assertThat(findReturnViolations(productionFacadeClasses(), FACADE_PACKAGE))
                .as("Map<Long, …> 代表以 Long 識別為 key 的跨模組回傳。請改為 Map<UUID, …>。")
                .isEmpty();
    }

    @Test
    @DisplayName("facade DTO 中名稱以 Id/Ids 結尾的欄位不得為 Long 系型別")
    void facadeDtoIdentifierFieldsMustNotBeLong() {
        assertThat(findDtoFieldViolations(productionFacadeClasses(), FACADE_PACKAGE))
                .as("facade DTO 的識別欄位若名稱帶 Id 卻是 Long，代表識別型別退回舊制。請改用 UUID。")
                .isEmpty();
    }

    @Test
    @DisplayName("防假陰性：正式碼 facade 掃描必須實際掃到介面、方法、Id 參數與 DTO 欄位")
    void productionScanMustActuallyFindFacadeMembers() {
        List<Class<?>> classes = productionFacadeClasses();
        List<Class<?>> interfaces = facadeInterfaces(classes);
        List<Method> methods = interfaces.stream().flatMap(i -> facadeMethods(i).stream()).toList();
        long idNamedParameters = methods.stream()
                .flatMap(m -> Arrays.stream(m.getParameters()))
                .filter(p -> ID_NAME.matcher(p.getName()).matches())
                .count();
        List<Field> dtoFields = dtoClasses(classes).stream().flatMap(c -> dtoFields(c).stream()).toList();

        assertThat(interfaces)
                .as("若掃不到任何 facade 介面，上面三條「無違規」就毫無意義（守衛空轉）")
                .isNotEmpty();
        assertThat(methods).as("facade 介面的 public 方法").isNotEmpty();
        assertThat(idNamedParameters)
                .as("名稱以 Id/Ids 結尾的參數（如 tagIds）。若為 0，代表參數名讀取失敗或 ID_NAME 失效，規則 (1) 恆綠")
                .isGreaterThan(0);
        assertThat(dtoClasses(classes)).as("facade DTO 類別").isNotEmpty();
        Set<Class<?>> reachable = reachableDtoClasses(classes);
        System.out.println("[FacadeIdentifierTypeTest] checked DTO classes: " + reachable.size() + ", outside facade package: "
                + reachable.stream().filter(c -> !c.getName().startsWith(FACADE_PACKAGE + ".")).count());
        assertThat(reachable)
                .as("可達型別集合必須涵蓋 facade 套件下的所有 DTO（它是超集合）")
                .containsAll(dtoClasses(classes));
        assertThat(dtoFields).as("facade DTO 欄位（record component）").isNotEmpty();
    }

    @Test
    @DisplayName("前提：facade 參數名必須能從 class 檔讀到真實名稱（-parameters），不得是 arg0")
    void facadeParameterNamesMustBeReadableFromClassFiles() {
        List<Parameter> parameters = facadeInterfaces(productionFacadeClasses()).stream()
                .flatMap(i -> facadeMethods(i).stream())
                .flatMap(m -> Arrays.stream(m.getParameters()))
                .toList();

        assertThat(parameters).as("正式碼 facade 的參數").isNotEmpty();
        assertThat(parameters)
                .as("編譯缺少 -parameters 時參數名會變成 arg0，規則 (1) 將恆綠。"
                  + "Spring Boot parent 預設開啟；若此處失敗，請檢查 maven-compiler-plugin 設定是否被覆寫。")
                .allSatisfy(p -> {
                    assertThat(p.isNamePresent()).as(p + " 的參數名是否保留於 class 檔").isTrue();
                    assertThat(p.getName()).doesNotMatch(SYNTHETIC_PARAMETER_NAME);
                });
    }

    @Test
    @DisplayName("tripwire：正式碼 facade 掃描不得掃進 architecture.fixture")
    void productionScanMustNotContainFixtures() {
        assertThat(productionFacadeClasses())
                .as("正式碼匯入若包含測試 source 的 fixture，故意違規的 fixture 會污染守衛結果")
                .noneMatch(c -> c.getName().contains(".architecture.fixture"));
    }

    @Test
    @DisplayName("Id 命名規則：只認 id／ids 與以 Id／Ids 結尾者，不誤判 Uuid、count、total")
    void idNamePatternMatchesIdSuffixOnly() {
        assertThat(List.of("id", "ids", "articleId", "tagIds", "userID", "articleIDs"))
                .allSatisfy(n -> assertThat(ID_NAME.matcher(n).matches()).as(n).isTrue());
        assertThat(List.of("uuid", "uuids", "userUuid", "articleUuids", "limit", "total", "count",
                "viewCount", "valid", "paid", "identifier", "idx", "Id0"))
                .allSatisfy(n -> assertThat(ID_NAME.matcher(n).matches()).as(n).isFalse());
    }

    @Test
    @DisplayName("反向驗證（參數）：抓得到 Long/long/List/Set/Collection/陣列，且讀得到真實參數名，不誤判合規者")
    void guardDetectsLongIdentifierParameters() {
        List<String> violations = findParameterViolations(fixtureFacadeClasses(), FIXTURE_FACADE_PACKAGE);

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義；字串含真實參數名，同時證明沒有退化成 arg0")
                .containsExactlyInAnyOrder(
                        "LongParamFacade#boxedLong(articleId)",
                        "LongParamFacade#primitiveLong(id)",
                        "LongParamFacade#listOfLong(tagIds)",
                        "LongParamFacade#setOfLong(ids)",
                        "LongParamFacade#collectionOfLong(userIds)",
                        "LongParamFacade#arrayOfLong(seriesIds)");
    }

    @Test
    @DisplayName("反向驗證（回傳）：抓得到 Map<Long,…>，以及方法名含 Id/Ids token 卻回傳 Long 系型別；不誤判計數、Valid、Idle、UUID 集合")
    void guardDetectsMapKeyedByLong() {
        List<String> violations = findReturnViolations(fixtureFacadeClasses(), FIXTURE_FACADE_PACKAGE);

        assertThat(violations)
                .containsExactlyInAnyOrder(
                        "LongReturnFacade#keyedByLong",
                        "LongReturnFacade#optionalKeyedByLong",
                        "LongReturnFacade#findTagIdsByArticleUuid",
                        "LongReturnFacade#findIdByUuid",
                        "LongReturnFacade#findOptionalId",
                        "LongReturnFacade#idsOf",
                        "LongReturnFacade#listIds",
                        "LongReturnFacade#findByUserID");
    }

    @Test
    @DisplayName("反向驗證（DTO）：抓得到 record component 與巢狀類別的 Long 識別，不誤判 UUID 與計數欄位")
    void guardDetectsLongIdentifierDtoFields() {
        List<String> violations = findDtoFieldViolations(fixtureFacadeClasses(), FIXTURE_FACADE_PACKAGE);

        assertThat(violations)
                .as("LongIdDto 的 authorId／id／tagIds 違規；NestedHolderDto.Inner.seriesId 是巢狀類別，也要抓；"
                  + "uuid、count 與 CompliantDto 全部合規；位於 facade 套件外但被 facade 參數／回傳（含泛型、循環）觸及的 DTO 也要抓，"
                  + "沒有任何 facade 方法觸及的 UnreachableDto 不得被報")
                .containsExactlyInAnyOrder(
                        "dto.LongIdDto#authorId",
                        "dto.LongIdDto#id",
                        "dto.LongIdDto#tagIds",
                        "dto.NestedHolderDto$Inner#seriesId",
                        FIXTURE_OUTSIDE_PACKAGE + ".OutsideDto#authorId",
                        FIXTURE_OUTSIDE_PACKAGE + ".OutsideChild#tagId",
                        FIXTURE_OUTSIDE_PACKAGE + ".OutsideParamDto#userIds");
    }

    // ─── 判定邏輯 ───

    /**
     * 找出 facade 介面方法中「名稱帶 Id 卻涉及 Long 系型別」的參數。
     *
     * @param classes     facade 根套件下的所有類別
     * @param rootPackage facade 根套件（用來縮短輸出的類別名稱）
     * @return 違規描述 {@code 介面#方法(參數名)}，已排序
     */
    private static List<String> findParameterViolations(List<Class<?>> classes, String rootPackage) {
        List<String> violations = new ArrayList<>();
        for (Class<?> facade : facadeInterfaces(classes)) {
            for (Method method : facadeMethods(facade)) {
                for (Parameter parameter : method.getParameters()) {
                    if (ID_NAME.matcher(parameter.getName()).matches()
                            && involvesLong(parameter.getParameterizedType())) {
                        violations.add(relativeName(facade, rootPackage) + "#" + method.getName()
                                + "(" + parameter.getName() + ")");
                    }
                }
            }
        }
        return violations.stream().sorted().toList();
    }

    /**
     * 找出 facade 介面方法回傳型別違規者：(1) 回傳型別（泛型容器展開後）含以 Long 為 key 的 Map；
     * (2) 方法名稱以駝峰分詞後有獨立的 {@code Id}／{@code Ids} token（如 {@code findTagIds}、{@code findIdByUuid}），
     * 回傳型別卻是 Long 系（Long、long、{@code Optional<Long>}、{@code List<Long>}、{@code Set<Long>}、{@code Long[]}）。
     *
     * @param classes     facade 根套件下的所有類別
     * @param rootPackage facade 根套件
     * @return 違規描述 {@code 介面#方法}，已排序
     */
    private static List<String> findReturnViolations(List<Class<?>> classes, String rootPackage) {
        List<String> violations = new ArrayList<>();
        for (Class<?> facade : facadeInterfaces(classes)) {
            for (Method method : facadeMethods(facade)) {
                Type returnType = method.getGenericReturnType();
                if (hasLongKeyedMap(returnType)
                        || (hasIdToken(method.getName()) && isLongLike(returnType))) {
                    violations.add(relativeName(facade, rootPackage) + "#" + method.getName());
                }
            }
        }
        return violations.stream().sorted().toList();
    }

    /**
     * 找出 DTO 類別中「名稱帶 Id 卻涉及 Long 系型別」的欄位（record 即 component）。
     * DTO 的範圍見 {@link #reachableDtoClasses(List)}：facade 套件下的所有類別，加上從 facade 方法的
     * 參數與回傳型別出發、遞迴可達的所有 {@code dowob.xyz.blog..} 型別（不論位於哪個套件或模組）。
     *
     * @param classes     facade 根套件下的所有類別
     * @param rootPackage facade 根套件
     * @return 違規描述 {@code 類別#欄位}，已排序
     */
    private static List<String> findDtoFieldViolations(List<Class<?>> classes, String rootPackage) {
        List<String> violations = new ArrayList<>();
        for (Class<?> dto : reachableDtoClasses(classes)) {
            for (Field field : dtoFields(dto)) {
                if (ID_NAME.matcher(field.getName()).matches() && involvesLong(field.getGenericType())) {
                    violations.add(relativeName(dto, rootPackage) + "#" + field.getName());
                }
            }
        }
        return violations.stream().sorted().toList();
    }

    // ─── 掃描輔助 ───

    private static List<Class<?>> productionFacadeClasses() {
        return toClasses(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(FACADE_PACKAGE));
    }

    private static List<Class<?>> fixtureFacadeClasses() {
        return toClasses(new ClassFileImporter().importPackages(FIXTURE_FACADE_PACKAGE));
    }

    private static List<Class<?>> toClasses(Iterable<JavaClass> javaClasses) {
        List<Class<?>> classes = new ArrayList<>();
        for (JavaClass javaClass : javaClasses) {
            Class<?> clazz = javaClass.reflect();
            if (!clazz.isAnonymousClass() && !clazz.isSynthetic() && !clazz.isEnum()) {
                classes.add(clazz);
            }
        }
        return classes;
    }

    private static List<Class<?>> facadeInterfaces(List<Class<?>> classes) {
        return classes.stream().filter(Class::isInterface).toList();
    }

    private static List<Class<?>> dtoClasses(List<Class<?>> classes) {
        return classes.stream().filter(c -> !c.isInterface()).toList();
    }

    /** 介面自己宣告的 public 非合成方法（含 default／static）。 */
    private static List<Method> facadeMethods(Class<?> facade) {
        return Arrays.stream(facade.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()) && !m.isSynthetic() && !m.isBridge())
                .toList();
    }

    /** 類別的非 static、非合成欄位；record 的 component 在 class 檔中就是同名 private final 欄位。 */
    private static List<Field> dtoFields(Class<?> dto) {
        if (dto.isRecord()) {
            return Arrays.stream(dto.getRecordComponents())
                    .map(RecordComponent::getName)
                    .map(name -> declaredField(dto, name))
                    .toList();
        }
        return Arrays.stream(dto.getDeclaredFields())
                .filter(f -> !Modifier.isStatic(f.getModifiers()) && !f.isSynthetic())
                .toList();
    }

    private static Field declaredField(Class<?> type, String name) {
        try {
            return type.getDeclaredField(name);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(type.getName() + " 的 record component " + name + " 找不到對應欄位", e);
        }
    }

    /** facade 套件內的類別以相對名稱表示；套件外的可達型別以全名表示。 */
    private static String relativeName(Class<?> clazz, String rootPackage) {
        return clazz.getName().startsWith(rootPackage + ".")
                ? clazz.getName().substring(rootPackage.length() + 1)
                : clazz.getName();
    }

    /**
     * 收集要檢查識別欄位的 DTO 類別：(1) facade 套件下所有非介面類別（含巢狀）；(2) 從所有 facade 方法的參數與
     * 回傳型別出發，展開泛型／陣列／萬用字元，並沿 record component、欄位與父類別遞迴，可達的所有
     * {@code dowob.xyz.blog..} 非介面、非列舉類別。以已訪問集合防止循環參照造成無窮迴圈。
     *
     * @param classes facade 根套件下的所有類別
     * @return 要檢查的 DTO 類別
     */
    private static Set<Class<?>> reachableDtoClasses(List<Class<?>> classes) {
        Set<Class<?>> visited = new LinkedHashSet<>();
        Deque<Type> pending = new ArrayDeque<>(dtoClasses(classes));
        for (Class<?> facade : facadeInterfaces(classes)) {
            for (Method method : facadeMethods(facade)) {
                pending.add(method.getGenericReturnType());
                pending.addAll(Arrays.asList(method.getGenericParameterTypes()));
            }
        }
        while (!pending.isEmpty()) {
            for (Class<?> candidate : rawClasses(pending.poll())) {
                if (!candidate.getName().startsWith(PROJECT_ROOT + ".")
                        || candidate.isInterface() || candidate.isEnum() || candidate.isAnnotation()
                        || candidate.isSynthetic() || candidate.isAnonymousClass()
                        || !visited.add(candidate)) {
                    continue;
                }
                dtoFields(candidate).forEach(field -> pending.add(field.getGenericType()));
                if (candidate.getSuperclass() != null) {
                    pending.add(candidate.getSuperclass());
                }
            }
        }
        return visited;
    }

    /** 型別展開後涉及的所有 Class（泛型參數、陣列元素、萬用字元與型別變數的界線）。 */
    private static List<Class<?>> rawClasses(Type type) {
        List<Class<?>> result = new ArrayList<>();
        if (type instanceof Class<?> clazz) {
            result.add(clazz.isArray() ? clazz.getComponentType() : clazz);
            if (clazz.isArray()) {
                result.addAll(rawClasses(clazz.getComponentType()));
            }
        } else if (type instanceof ParameterizedType parameterized) {
            result.addAll(rawClasses(parameterized.getRawType()));
            Arrays.stream(parameterized.getActualTypeArguments()).forEach(a -> result.addAll(rawClasses(a)));
        } else if (type instanceof GenericArrayType array) {
            result.addAll(rawClasses(array.getGenericComponentType()));
        } else if (type instanceof WildcardType wildcard) {
            Arrays.stream(wildcard.getUpperBounds()).forEach(b -> result.addAll(rawClasses(b)));
            Arrays.stream(wildcard.getLowerBounds()).forEach(b -> result.addAll(rawClasses(b)));
        } else if (type instanceof TypeVariable<?> variable) {
            Arrays.stream(variable.getBounds()).forEach(b -> result.addAll(rawClasses(b)));
        }
        return result;
    }

    // ─── 型別展開 ───

    /** 駝峰分詞：在小寫／數字接大寫處、以及連續大寫接「大寫＋小寫」處切開。 */
    private static final Pattern CAMEL_BOUNDARY =
            Pattern.compile("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])");

    /** 獨立的 Id token（方法名稱中非首位的寫法） */
    private static final Pattern ID_TOKEN = Pattern.compile("^(Id|Ids|ID|IDs)$");

    /**
     * 方法名稱分詞後是否有獨立的 Id／Ids token。
     *
     * <p>{@code findTagIds}、{@code findIdByUuid}、{@code findByUserID} 命中；
     * {@code getValidTotal}（token 為 Valid）、{@code idleCount}（Idle）、{@code isIdle} 不命中；
     * 首位 token 允許小寫 {@code id}／{@code ids}（如 {@code idsOf}）。</p>
     */
    private static boolean hasIdToken(String methodName) {
        String[] tokens = CAMEL_BOUNDARY.split(methodName);
        for (int i = 0; i < tokens.length; i++) {
            if (ID_TOKEN.matcher(tokens[i]).matches()
                    || (i == 0 && (tokens[i].equals("id") || tokens[i].equals("ids")))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否為「Long 系」回傳：Long／long、其陣列，或元素／內容為 Long 系的非 Map 泛型容器
     * （{@code Optional<Long>}、{@code List<Long>}、{@code Set<Long>}…）。Map 由 key 規則另外處理。
     */
    private static boolean isLongLike(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz == Long.class || clazz == long.class
                    || (clazz.isArray() && isLongLike(clazz.getComponentType()));
        }
        if (type instanceof GenericArrayType array) {
            return isLongLike(array.getGenericComponentType());
        }
        if (type instanceof ParameterizedType parameterized) {
            if (parameterized.getRawType() instanceof Class<?> raw && Map.class.isAssignableFrom(raw)) {
                return false;
            }
            return Arrays.stream(parameterized.getActualTypeArguments())
                    .anyMatch(FacadeIdentifierTypeTest::isLongLike);
        }
        if (type instanceof WildcardType wildcard) {
            return Arrays.stream(wildcard.getUpperBounds()).anyMatch(FacadeIdentifierTypeTest::isLongLike);
        }
        return false;
    }

    /** 型別（泛型、陣列、萬用字元、型別變數展開後）是否涉及 Long／long。 */
    private static boolean involvesLong(Type type) {
        if (type instanceof Class<?> clazz) {
            return clazz == Long.class || clazz == long.class
                    || (clazz.isArray() && involvesLong(clazz.getComponentType()));
        }
        if (type instanceof ParameterizedType parameterized) {
            return involvesLong(parameterized.getRawType())
                    || Arrays.stream(parameterized.getActualTypeArguments()).anyMatch(
                            FacadeIdentifierTypeTest::involvesLong);
        }
        if (type instanceof GenericArrayType array) {
            return involvesLong(array.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcard) {
            return Arrays.stream(wildcard.getUpperBounds()).anyMatch(FacadeIdentifierTypeTest::involvesLong)
                    || Arrays.stream(wildcard.getLowerBounds()).anyMatch(FacadeIdentifierTypeTest::involvesLong);
        }
        if (type instanceof TypeVariable<?> variable) {
            return Arrays.stream(variable.getBounds()).anyMatch(FacadeIdentifierTypeTest::involvesLong);
        }
        return false;
    }

    /** 型別展開後是否存在 key 涉及 Long 的 Map。 */
    private static boolean hasLongKeyedMap(Type type) {
        if (type instanceof ParameterizedType parameterized) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (parameterized.getRawType() instanceof Class<?> raw
                    && Map.class.isAssignableFrom(raw)
                    && arguments.length > 0
                    && involvesLong(arguments[0])) {
                return true;
            }
            return Arrays.stream(arguments).anyMatch(FacadeIdentifierTypeTest::hasLongKeyedMap);
        }
        if (type instanceof GenericArrayType array) {
            return hasLongKeyedMap(array.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcard) {
            return Arrays.stream(wildcard.getUpperBounds()).anyMatch(FacadeIdentifierTypeTest::hasLongKeyedMap);
        }
        return false;
    }
}
