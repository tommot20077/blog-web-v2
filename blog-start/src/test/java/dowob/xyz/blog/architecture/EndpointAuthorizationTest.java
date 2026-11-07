package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import dowob.xyz.blog.common.api.enums.Permission;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端點授權守衛（{@code security.md} 原則 1「兩層防護缺一不可」、原則 7）。
 *
 * <p>守衛 #7：Controller 中取用 {@code @AuthenticationPrincipal} 的 handler 方法，
 * 必須有方法層或類層的 {@code @PreAuthorize}。BUG-2026-001 FIN-3+4 的缺陷正是
 * {@code UserController} 3 個端點與 {@code AuthController.logout()} 少了它——
 * 當時只靠 URL 層 permitAll 規則把關，方法層無兜底。</p>
 *
 * <p><b>豁免的判準不是「開發者說它公開」，而是 {@code security.md} 的
 * Public Endpoints 表有對應條目</b>（原則 7 的「選填認證公開端點」：
 * URL 層明確 permitAll、principal 允許為 null 僅供個人化、且 JavaDoc 寫明豁免）。
 * {@link #EXEMPT} 的每一項都必須能在該表指到一條路徑；新增豁免時請先改那張表，
 * 表是真相，本清單只是它的機器可讀投影。</p>
 *
 * <p><b>為何附反向驗證與防腐測試</b>：守衛掃不到違規有兩種可能——真的沒違規，
 * 或守衛壞了（見 {@code 29a1000} 堵住守衛 #5 兩處靜默假陰性）；而白名單會腐爛——
 * 端點若日後補上 {@code @PreAuthorize} 或被刪除，對應條目就變成死條目，
 * 靜靜掩蓋未來真正的違規。故本測試同時驗這兩件事。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class EndpointAuthorizationTest {

    /** Spring Security 方法層授權標註的全限定名 */
    private static final String PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";

    /** 當前登入者注入標註的全限定名 */
    private static final String AUTH_PRINCIPAL = "org.springframework.security.core.annotation.AuthenticationPrincipal";

    /** Spring MVC 的 handler 映射標註簡單名稱 */
    private static final List<String> MAPPING_ANNOTATIONS = List.of(
            "GetMapping", "PostMapping", "PutMapping", "DeleteMapping", "PatchMapping", "RequestMapping");

    /**
     * 原則 7 的豁免清單：{@code 類別#方法} → {@code security.md} Public Endpoints 表的對應路徑。
     *
     * <p>value 不是註解，是<b>可查證的依據</b>——每一項都必須能在該表指到一條路徑。
     * 指不到就不該進這份清單，而該去補 {@code security.md}（那是 Yuan 決策項，
     * 見 {@code judgment.md} §5「Public Endpoints 表的任何增減必問」）。</p>
     */
    private static final Map<String, String> EXEMPT = Map.of(
            "ArticleController#getArticle", "GET /api/v1/articles/{uuid}",
            "ArticleController#getArticleBySlug", "GET /api/v1/articles/slug/{slug}",
            "CommentController#list", "GET /api/v1/articles/{uuid}/comments",
            "FileController#getFileContent", "GET /api/v1/files/**（實際授權在 FileService#canRead()）",
            "FileController#getFileMetadata", "GET /api/v1/files/**（實際授權在 FileService#canRead()）",
            "SearchController#search", "GET /api/v1/search",
            "SeriesController#get", "GET /api/v1/series/**",
            "TagController#getTagDetail", "GET /api/v1/tags/**");

    @Test
    @DisplayName("守衛 #7：取用 @AuthenticationPrincipal 的端點必須有 @PreAuthorize")
    void endpointUsingPrincipalMustDeclarePreAuthorize() {
        List<String> violations = findViolations(importProductionControllers()).stream()
                .filter(v -> !EXEMPT.containsKey(v))
                .toList();

        assertThat(violations)
                .as("端點取用 @AuthenticationPrincipal 即代表其行為依登入者而定，"
                  + "必須有方法層 @PreAuthorize 兜底（security.md 原則 1「兩層防護缺一不可」）。"
                  + "若確為原則 7 的選填認證公開端點，請先在 security.md 的 Public Endpoints 表"
                  + "補上該路徑，再把它加進 EXEMPT 並填寫對應路徑。")
                .isEmpty();
    }

    @Test
    @DisplayName("防腐：豁免清單不得有死條目——每一項都必須仍是實際違規")
    void exemptListMustNotContainStaleEntries() {
        List<String> actual = findViolations(importProductionControllers());

        assertThat(EXEMPT.keySet())
                .as("豁免清單的條目若已不再違規（端點補了 @PreAuthorize 或被刪除），"
                  + "它就成了死條目，會靜靜掩蓋未來落在同一個方法名上的真違規，請移除。")
                .allSatisfy(exempt -> assertThat(actual).contains(exempt));
    }

    @Test
    @DisplayName("反向驗證：守衛抓得到缺少 @PreAuthorize 的端點")
    void guardDetectsEndpointWithoutPreAuthorize() {
        JavaClasses fixtures = new ClassFileImporter()
                .importPackages("dowob.xyz.blog.architecture.fixture");

        assertThat(findViolations(fixtures))
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .contains("UnauthorizedEndpointFixture#usesPrincipalWithoutPreAuthorize");
    }

    @Test
    @DisplayName("反向驗證：守衛不誤判已授權端點與非 handler 方法")
    void guardDoesNotFlagLegitimatePatterns() {
        JavaClasses fixtures = new ClassFileImporter()
                .importPackages("dowob.xyz.blog.architecture.fixture");

        assertThat(findViolations(fixtures))
                .as("有 @PreAuthorize 者與無 mapping 標註者皆合規，被抓到即為假陽性")
                .doesNotContain("UnauthorizedEndpointFixture#usesPrincipalWithPreAuthorize")
                .doesNotContain("UnauthorizedEndpointFixture#notAHandler");
    }

    // =========================================================================
    // A9（AUTH-10）：權限命名統一
    // =========================================================================

    /** 所有合法的 Permission 名稱（真相來源是 {@link Permission} enum） */
    private static final Set<String> PERMISSION_NAMES = Arrays.stream(Permission.values())
            .map(Enum::name).collect(Collectors.toSet());

    /** 抓出 {@code hasAuthority('X')} 的 X */
    private static final Pattern HAS_AUTHORITY = Pattern.compile("hasAuthority\\('([A-Z_]+)'\\)");

    @Test
    @DisplayName("守衛 A9-1：/api/v1/admin/** 底下每個 handler 都必須是 hasAuthority('ADMIN_*')（與 URL 層 hasRole(ADMIN) 構成雙層）")
    void adminEndpointsMustRequireAdminPermission() {
        assertThat(findAdminPermissionViolations(importProductionControllers()))
                .as("admin 端點須宣告 @PreAuthorize(\"hasAuthority('ADMIN_*')\")，且 ADMIN_* 必須是 Permission enum 內存在的名稱；"
                  + "不得沿用已廢除的泛用 SYSTEM_CONFIG，也不得只靠 isAuthenticated()（security.md 原則 1、AUTH-10）")
                .isEmpty();
    }

    @Test
    @DisplayName("守衛 A9-2：所有 @PreAuthorize 引用的 hasAuthority('X') 都必須是現存 Permission（防拼錯／改名後漏改）")
    void everyReferencedAuthorityMustBeAnExistingPermission() {
        assertThat(findUnknownAuthorityReferences(importProductionControllers()))
                .as("hasAuthority 引用了不存在的 Permission：Spring 不會報錯，只會讓該端點對所有人 403（或被誤放行的假象）")
                .isEmpty();
    }

    @Test
    @DisplayName("守衛 A9-3：POST /api/v1/articles/{uuid}/reject（不在 /admin URL 層）必須是 ADMIN_ARTICLE_REVIEW")
    void rejectEndpointMustRequireAdminArticleReview() {
        JavaClasses classes = importProductionControllers();

        String value = classes.stream()
                .filter(c -> c.getSimpleName().equals("ArticleController"))
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.getName().equals("rejectArticle"))
                .map(m -> m.tryGetAnnotationOfType(PreAuthorize.class).map(PreAuthorize::value).orElse("<none>"))
                .findFirst().orElse("<missing method>");

        assertThat(value).isEqualTo("hasAuthority('ADMIN_ARTICLE_REVIEW')");
    }

    @Test
    @DisplayName("守衛 A9-4：VersionController 的讀取端點不再只靠 isAuthenticated()，需 hasAuthority('ARTICLE_EDIT')（owner／admin 檢查仍在 service 層）")
    void versionReadEndpointsMustRequireArticleEdit() {
        JavaClasses classes = importProductionControllers();

        List<String> values = classes.stream()
                .filter(c -> c.getSimpleName().equals("VersionController"))
                .flatMap(c -> c.getMethods().stream())
                .filter(EndpointAuthorizationTest::isHandler)
                .filter(m -> m.getName().equals("list") || m.getName().equals("getDetail"))
                .map(m -> m.getName() + "=" + m.tryGetAnnotationOfType(PreAuthorize.class).map(PreAuthorize::value).orElse("<none>"))
                .sorted().toList();

        assertThat(values).containsExactly(
                "getDetail=hasAuthority('ARTICLE_EDIT')", "list=hasAuthority('ARTICLE_EDIT')");
    }

    @Test
    @DisplayName("反向驗證 A9：守衛抓得到泛用／缺失／只登入／拼錯的 admin 端點，且不誤判合規者")
    void adminPermissionGuardDetectsKnownViolations() {
        JavaClasses fixtures = new ClassFileImporter().importPackages("dowob.xyz.blog.architecture.fixture");

        assertThat(findAdminPermissionViolations(fixtures))
                .containsExactlyInAnyOrder(
                        "AdminPermissionFixture#legacyGenericPermission",
                        "AdminPermissionFixture#missingPreAuthorize",
                        "AdminPermissionFixture#authenticatedOnly",
                        "AdminPermissionFixture#unknownPermission")
                .doesNotContain("AdminPermissionFixture#compliant");
        assertThat(findUnknownAuthorityReferences(fixtures))
                .contains("AdminPermissionFixture#unknownPermission", "AdminPermissionFixture#legacyGenericPermission")
                .doesNotContain("AdminPermissionFixture#compliant");
    }

    /**
     * 找出 admin URL 底下不符 {@code hasAuthority('ADMIN_*')}（且名稱須為現存 Permission）的 handler。
     *
     * @param classes 待掃描的類集合
     * @return 違規方法的 {@code 類別#方法} 描述
     */
    private static List<String> findAdminPermissionViolations(JavaClasses classes) {
        return classes.stream()
                .filter(c -> c.getSimpleName().endsWith("Controller") || c.getSimpleName().endsWith("Fixture"))
                .filter(c -> c.tryGetAnnotationOfType(RequestMapping.class)
                        .map(a -> Arrays.stream(a.value()).anyMatch(p -> p.startsWith("/api/v1/admin")))
                        .orElse(false))
                .flatMap(c -> c.getMethods().stream())
                .filter(EndpointAuthorizationTest::isHandler)
                .filter(m -> !hasAdminPermission(m))
                .map(m -> m.getOwner().getSimpleName() + "#" + m.getName())
                .distinct().sorted().toList();
    }

    private static boolean hasAdminPermission(JavaMethod method) {
        String expression = method.tryGetAnnotationOfType(PreAuthorize.class).map(PreAuthorize::value).orElse("");
        Matcher m = HAS_AUTHORITY.matcher(expression);
        return m.matches() && m.group(1).startsWith("ADMIN_") && PERMISSION_NAMES.contains(m.group(1));
    }

    /**
     * 找出 {@code @PreAuthorize} 內引用了不存在 Permission 名稱的方法（方法層或類層）。
     *
     * @param classes 待掃描的類集合
     * @return 違規方法的 {@code 類別#方法} 描述
     */
    private static List<String> findUnknownAuthorityReferences(JavaClasses classes) {
        return classes.stream()
                .filter(c -> c.getSimpleName().endsWith("Controller") || c.getSimpleName().endsWith("Fixture"))
                .flatMap(c -> c.getMethods().stream())
                .filter(m -> m.tryGetAnnotationOfType(PreAuthorize.class)
                        .map(a -> unknownAuthorityIn(a.value())).orElse(false))
                .map(m -> m.getOwner().getSimpleName() + "#" + m.getName())
                .distinct().sorted().toList();
    }

    private static boolean unknownAuthorityIn(String expression) {
        Matcher m = Pattern.compile("hasAuthority\\('([A-Za-z_]+)'\\)").matcher(expression);
        while (m.find()) {
            if (!PERMISSION_NAMES.contains(m.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 匯入正式碼的 controller（不含測試 source）。
     *
     * @return 待掃描的類集合
     */
    private static JavaClasses importProductionControllers() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dowob.xyz.blog.module");
    }

    /**
     * 找出所有「取用 {@code @AuthenticationPrincipal} 卻無 {@code @PreAuthorize}」的 handler。
     *
     * @param classes 待掃描的類集合
     * @return 違規方法的 {@code 類別#方法} 描述，無違規則為空清單
     */
    private static List<String> findViolations(JavaClasses classes) {
        return classes.stream()
                .filter(c -> c.getSimpleName().endsWith("Controller") || c.getSimpleName().endsWith("Fixture"))
                .flatMap(c -> c.getMethods().stream())
                .filter(EndpointAuthorizationTest::isHandler)
                .filter(EndpointAuthorizationTest::usesAuthenticationPrincipal)
                .filter(m -> !isAuthorized(m))
                .map(m -> m.getOwner().getSimpleName() + "#" + m.getName())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 判斷方法是否為 Spring MVC handler。
     *
     * @param method 待判斷的方法
     * @return 帶有任一 mapping 標註回傳 true
     */
    private static boolean isHandler(JavaMethod method) {
        return method.getAnnotations().stream()
                .anyMatch(a -> MAPPING_ANNOTATIONS.contains(a.getRawType().getSimpleName()));
    }

    /**
     * 判斷方法是否有參數取用當前登入者。
     *
     * @param method 待判斷的方法
     * @return 任一參數標註 {@code @AuthenticationPrincipal} 回傳 true
     */
    private static boolean usesAuthenticationPrincipal(JavaMethod method) {
        return method.getParameters().stream()
                .anyMatch(p -> p.getAnnotations().stream()
                        .anyMatch(a -> a.getRawType().getName().equals(AUTH_PRINCIPAL)));
    }

    /**
     * 判斷方法是否受方法層授權標註保護（方法層或類層皆算）。
     *
     * @param method 待判斷的方法
     * @return 有 {@code @PreAuthorize} 回傳 true
     */
    private static boolean isAuthorized(JavaMethod method) {
        return method.isAnnotatedWith(PRE_AUTHORIZE) || method.getOwner().isAnnotatedWith(PRE_AUTHORIZE);
    }
}
