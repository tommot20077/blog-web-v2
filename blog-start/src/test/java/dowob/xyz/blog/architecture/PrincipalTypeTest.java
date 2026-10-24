package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameter;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dowob.xyz.blog.common.security.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 登入身分（principal）型別守衛（設計 {@code 2026-10-24-cross-module-uuid-design.md} §3.2「0b：登入身分」）。
 *
 * <p><b>為何需要守衛</b>：principal 由 {@code Long} 換成 {@link AuthUser} 時，漏改的地方<b>不會編譯失敗</b>——
 * {@code @AuthenticationPrincipal Long} 會靜默拿到 {@code null}（等同未登入），
 * {@code getPrincipal() instanceof Long} 會靜默變成 false。守衛把這類漏改變成測試失敗。
 * 只掃正式碼（{@code DO_NOT_INCLUDE_TESTS}）。</p>
 *
 * <p>規則：</p>
 * <ol>
 *   <li>(a) 標註 {@code @AuthenticationPrincipal} 的方法參數，型別必須是 {@link AuthUser}
 *       （{@code Long}、{@code long}、{@code Object} 等一律違規）；</li>
 *   <li>(b) 除 {@link #GET_PRINCIPAL_ALLOWED 白名單}（只有 {@code SecurityUtils}）外，
 *       任何程式碼不得呼叫 {@code Authentication.getPrincipal()}——
 *       含具體子類別（如 {@code UsernamePasswordAuthenticationToken}）、lambda 與方法參考；
 *       讀取已登入者一律改走 {@code SecurityUtils.currentAuthUser(Authentication)}；</li>
 *   <li>(b2) 白名單外同樣不得呼叫 {@code Principal#getName()}（含 {@code Authentication#getName()}）、
 *       {@code HttpServletRequest#getUserPrincipal()}、{@code HttpServletRequest#getRemoteUser()}：
 *       principal 換成 record 後 {@code getName()} 會回 {@code AuthUser[id=…, uuid=…]}，是靜默的語意改變；</li>
 *   <li>(c) 禁用 {@code @CurrentSecurityContext}，以及被 {@code @AuthenticationPrincipal}／
 *       {@code @CurrentSecurityContext} 直接或 meta 標註的自訂註解（定義與使用處都算）——
 *       它們能繞過 (a) 的參數型別檢查。擇「直接禁用」而非「其參數也必須是 AuthUser」，
 *       因為後者要求守衛追蹤任意註解的 meta 鏈與 SpEL expression。</li>
 * </ol>
 *
 * <p><b>例外清單起步、T0b-3 清空</b>：T0b-1 只建立守衛、不動 filter 與 controller，故現存違規
 * （63 處 {@code @AuthenticationPrincipal Long}、2 處 {@code getPrincipal()}）列入例外清單；
 * (b2)(c) 現況零違規，故不設例外清單，
 * value 必須以預計清除的 Task（{@code T0b-2} 或 {@code T0b-3}）開頭。
 * 各 Task 清掉自己的條目；<b>T0b-3 完成時兩份例外清單必須為空</b>，且守衛仍須為綠。
 * 這取代「完成時守衛是紅的」的作法，避免 develop 上出現長期紅燈；
 * 拿掉例外清單即可重現原本的紅燈（違規數見 T0b-1 回報）。</p>
 *
 * <p><b>為何附防死條目與反向驗證</b>：與 {@link EventContractTest}、{@link CrossModuleImportTest} 同理——
 * 清單會腐爛，守衛也可能壞掉而「掃不到」被誤認為「沒違規」，故兩者都以測試驗證。</p>
 *
 * <p>守衛的已知範圍：只看方法參數上的注入註解（欄位、建構子參數不在範圍，
 * Spring MVC 也只對 handler 方法參數解析它），以及 {@code Authentication}／{@code Principal}／
 * {@code HttpServletRequest} 型別上的上述方法呼叫（反射呼叫、經由 {@code SecurityContextHolder} 以外的
 * 自製包裝不在範圍；包裝類別本身若呼叫了禁用方法仍會被抓到）。(b2)(c) 在正式碼沒有正例，
 * 偵測能力只由反向驗證 fixture 保證。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class PrincipalTypeTest {

    /** 正式碼根套件 */
    private static final String PRODUCTION_ROOT = "dowob.xyz.blog";

    /** 業務模組根套件，其下第一層為模組名 */
    private static final String MODULE_ROOT = "dowob.xyz.blog.module";

    /** 反向驗證 fixture 的根套件 */
    private static final String FIXTURE_ROOT = "dowob.xyz.blog.architecture.fixture.principaltype";

    /** 當前登入者注入標註的全限定名 */
    private static final String AUTH_PRINCIPAL = "org.springframework.security.core.annotation.AuthenticationPrincipal";

    /** 例外清單 value 的格式：以預計清除的 Task 開頭 */
    private static final Pattern STAGE_PREFIX = Pattern.compile("^T0b-[23][：:].+");

    /** 讀取 principal 的唯一入口 */
    private static final String SECURITY_UTILS = "dowob.xyz.blog.common.util.SecurityUtils";

    /** {@code @CurrentSecurityContext} 的全限定名（可用 SpEL 取出任意 principal 屬性，繞過規則 (a)） */
    private static final String CURRENT_SECURITY_CONTEXT =
            "org.springframework.security.core.annotation.CurrentSecurityContext";

    /** {@code java.security.Principal} 的全限定名（{@code Authentication} 的父介面） */
    private static final String PRINCIPAL = "java.security.Principal";

    /** {@code HttpServletRequest} 的全限定名 */
    private static final String HTTP_SERVLET_REQUEST = "jakarta.servlet.http.HttpServletRequest";

    /**
     * 規則 (b)(b2) 白名單：允許讀取 principal 的最外層類別全名，<b>只留 {@code SecurityUtils}</b>。
     * 新增項目須經人工確認；不得預先登記尚未存在的類別（放錯套件時防死條目仍綠、
     * 同名類別會自動獲得權限）。{@code JwtAuthenticationFilter} 與 T0b-2 的 {@code AuthStateLoader}
     * 是「建立」身分而非「讀取」身分，不需要；日後真的需要再加。
     */
    private static final Set<String> GET_PRINCIPAL_ALLOWED = Set.of(SECURITY_UTILS);

    /** 預期有使用 {@code @AuthenticationPrincipal} 的業務模組；新增有登入端點的模組時須同步更新 */
    private static final Set<String> EXPECTED_PRINCIPAL_MODULES = Set.of(
            "article", "comment", "file", "reading", "search", "series", "tag", "user", "version");

    /** 快取的正式碼匯入結果（匯入約數秒，各測試共用） */
    private static JavaClasses productionClasses;

    /** 規則 (a) 例外的清除說明：T0b-3 逐 controller 純型別替換 */
    private static final String T0B3_PARAM = "T0b-3：controller 純型別替換，@AuthenticationPrincipal Long 改為 AuthUser";

    /** 規則 (a) 例外的清除說明：AuthController／SearchController 的 service 簽章已在 T0b-2 改收 UUID，故由 T0b-2 一併替換 */
    private static final String T0B2_PARAM = "T0b-2：認證鏈 Task 因 service 簽章改收 UUID 一併替換為 AuthUser";

    /** 規則 (b) 例外的清除說明 */
    private static final String T0B3_PRINCIPAL_READ = "T0b-3：改用 SecurityUtils.currentAuthUser(Authentication)";

    /**
     * 規則 (a) 例外清單：型別不是 AuthUser 的 {@code @AuthenticationPrincipal} 參數。
     * key 為 {@code 類別全名#方法名[參數索引]}，value 為「Task：說明」。
     */
    private static final Map<String, String> PRINCIPAL_PARAM_EXEMPT = Map.ofEntries(
            // AdminArticleController
            Map.entry("dowob.xyz.blog.module.article.controller.AdminArticleController#archiveArticle[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.AdminArticleController#unarchiveArticle[1]",
                    T0B3_PARAM),
            // ArticleController
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#createArticle[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#deleteArticle[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#getArticleBySlug[2]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#getArticleForEdit[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#getArticle[2]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#getMyArticles[2]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#publishArticle[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#rejectArticle[2]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#submitForReview[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#updateArticle[2]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.article.controller.ArticleController#withdrawArticle[1]",
                    T0B3_PARAM),
            // CommentController
            Map.entry("dowob.xyz.blog.module.comment.controller.CommentController#create[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.comment.controller.CommentController#delete[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.comment.controller.CommentController#edit[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.comment.controller.CommentController#list[3]",
                    T0B3_PARAM),
            // CommentLikeController
            Map.entry("dowob.xyz.blog.module.comment.controller.CommentLikeController#like[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.comment.controller.CommentLikeController#unlike[0]",
                    T0B3_PARAM),
            // FileController
            Map.entry("dowob.xyz.blog.module.file.controller.FileController#deleteFile[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.file.controller.FileController#getFileContent[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.file.controller.FileController#getFileMetadata[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.file.controller.FileController#getQuota[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.file.controller.FileController#getUserFiles[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.file.controller.FileController#uploadFile[3]",
                    T0B3_PARAM),
            // ArticleLikeController
            Map.entry("dowob.xyz.blog.module.reading.controller.ArticleLikeController#like[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.reading.controller.ArticleLikeController#unlike[0]",
                    T0B3_PARAM),
            // BookmarkController
            Map.entry("dowob.xyz.blog.module.reading.controller.BookmarkController#bookmark[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.reading.controller.BookmarkController#myBookmarks[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.reading.controller.BookmarkController#unbookmark[0]",
                    T0B3_PARAM),
            // HighlightController
            Map.entry("dowob.xyz.blog.module.reading.controller.HighlightController#create[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.reading.controller.HighlightController#delete[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.reading.controller.HighlightController#list[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.reading.controller.HighlightController#update[1]",
                    T0B3_PARAM),
            // ReadingProgressController
            Map.entry("dowob.xyz.blog.module.reading.controller.ReadingProgressController#get[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.reading.controller.ReadingProgressController#update[1]",
                    T0B3_PARAM),
            // SearchController
            Map.entry("dowob.xyz.blog.module.search.controller.SearchController#clearHistory[0]",
                    T0B2_PARAM),
            Map.entry("dowob.xyz.blog.module.search.controller.SearchController#getHistory[0]",
                    T0B2_PARAM),
            Map.entry("dowob.xyz.blog.module.search.controller.SearchController#search[4]",
                    T0B2_PARAM),
            // SeriesController
            Map.entry("dowob.xyz.blog.module.series.controller.SeriesController#addArticle[2]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.series.controller.SeriesController#create[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.series.controller.SeriesController#delete[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.series.controller.SeriesController#get[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.series.controller.SeriesController#removeArticle[2]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.series.controller.SeriesController#update[1]",
                    T0B3_PARAM),
            // TagController
            Map.entry("dowob.xyz.blog.module.tag.controller.TagController#followTag[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.tag.controller.TagController#getTagDetail[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.tag.controller.TagController#unfollowTag[1]",
                    T0B3_PARAM),
            // AuthController
            Map.entry("dowob.xyz.blog.module.user.controller.AuthController#logout[0]",
                    T0B2_PARAM),
            // UserController
            Map.entry("dowob.xyz.blog.module.user.controller.UserController#changePassword[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.user.controller.UserController#deleteAccount[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.user.controller.UserController#getMe[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.user.controller.UserController#updateNotifications[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.user.controller.UserController#updateProfile[0]",
                    T0B3_PARAM),
            // PreferenceController
            Map.entry("dowob.xyz.blog.module.version.controller.PreferenceController#get[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.version.controller.PreferenceController#reset[0]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.version.controller.PreferenceController#update[0]",
                    T0B3_PARAM),
            // VersionController
            Map.entry("dowob.xyz.blog.module.version.controller.VersionController#createManual[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.version.controller.VersionController#delete[2]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.version.controller.VersionController#getDetail[2]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.version.controller.VersionController#list[1]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.version.controller.VersionController#promote[2]",
                    T0B3_PARAM),
            Map.entry("dowob.xyz.blog.module.version.controller.VersionController#restore[2]",
                    T0B3_PARAM));

    /**
     * 規則 (b) 例外清單：白名單外呼叫 {@code getPrincipal()} 的程式碼。
     * key 為 {@code 呼叫端最外層類別全名#方法名}，value 為「Task：說明」。
     */
    private static final Map<String, String> GET_PRINCIPAL_EXEMPT = Map.ofEntries(
            Map.entry("dowob.xyz.blog.module.article.service.ArticleQueryService#currentUserIdOrNull",
                    T0B3_PRINCIPAL_READ),
            Map.entry("dowob.xyz.blog.module.article.service.ArticleResponseMapper#resolveViewerId",
                    T0B3_PRINCIPAL_READ));

    @Test
    @DisplayName("規則 (a)：@AuthenticationPrincipal 參數型別必須是 AuthUser（例外清單以外不得有）")
    void authenticationPrincipalParameterMustBeAuthUser() {
        List<String> violations = findNonAuthUserPrincipalParameters(importProduction()).stream()
                .filter(v -> !PRINCIPAL_PARAM_EXEMPT.containsKey(v))
                .toList();

        assertThat(violations)
                .as("@AuthenticationPrincipal 的參數型別必須是 " + AuthUser.class.getName() + "，"
                  + "否則 principal 換型別後會靜默拿到 null（等同未登入）。共 " + violations.size() + " 處違規。"
                  + "若為尚未改造的 controller，加入 PRINCIPAL_PARAM_EXEMPT 並標明清除的 Task。")
                .isEmpty();
    }

    @Test
    @DisplayName("規則 (b)：白名單外不得呼叫 Authentication.getPrincipal()（例外清單以外不得有）")
    void getPrincipalMustOnlyBeCalledByAllowedClasses() {
        List<String> violations = findGetPrincipalCallers(importProduction(), GET_PRINCIPAL_ALLOWED).stream()
                .filter(v -> !GET_PRINCIPAL_EXEMPT.containsKey(v))
                .toList();

        assertThat(violations)
                .as("白名單 " + GET_PRINCIPAL_ALLOWED + " 以外不得直接讀取 principal，"
                  + "請改用 SecurityUtils.currentAuthUser(Authentication)。共 " + violations.size() + " 處違規。"
                  + "若為尚未改造的程式碼，加入 GET_PRINCIPAL_EXEMPT 並標明清除的 Task。")
                .isEmpty();
    }

    @Test
    @DisplayName("防死條目 (a)：例外清單的每一項都必須仍是實際違規")
    void principalParamExemptListMustNotContainStaleEntries() {
        List<String> actual = findNonAuthUserPrincipalParameters(importProduction());

        assertThat(PRINCIPAL_PARAM_EXEMPT.keySet())
                .as("條目對應的參數若已改為 AuthUser 或方法已被刪除，就成了死條目，"
                  + "會靜靜掩蓋日後落在同一個方法上的真違規，請移除。")
                .allSatisfy(exempt -> assertThat(actual).contains(exempt));
    }

    @Test
    @DisplayName("防死條目 (b)：例外清單的每一項都必須仍是實際的 getPrincipal() 呼叫")
    void getPrincipalExemptListMustNotContainStaleEntries() {
        List<String> actual = findGetPrincipalCallers(importProduction(), GET_PRINCIPAL_ALLOWED);

        assertThat(GET_PRINCIPAL_EXEMPT.keySet())
                .as("條目對應的呼叫若已改走 SecurityUtils.currentAuthUser 或方法已被刪除，就成了死條目，請移除。")
                .allSatisfy(exempt -> assertThat(actual).contains(exempt));
    }

    @Test
    @DisplayName("防死條目 (b 白名單)：白名單的每個類別都必須存在於正式碼")
    void getPrincipalAllowListMustNotContainStaleEntries() {
        Set<String> existing = new TreeSet<>();
        importProduction().forEach(c -> existing.add(c.getName()));

        assertThat(GET_PRINCIPAL_ALLOWED)
                .as("白名單類別若已改名或刪除，就成了死條目；同名新類別會無條件獲得讀取 principal 的權限，請移除。")
                .allSatisfy(allowed -> assertThat(existing).contains(allowed));
    }

    @Test
    @DisplayName("例外清單每個條目都必須標明預計清除的 Task（T0b-2 或 T0b-3）")
    void everyExemptEntryMustDeclareClearingTask() {
        assertThat(PRINCIPAL_PARAM_EXEMPT.values()).allSatisfy(v -> assertThat(v).matches(STAGE_PREFIX));
        assertThat(GET_PRINCIPAL_EXEMPT.values()).allSatisfy(v -> assertThat(v).matches(STAGE_PREFIX));
    }

    @Test
    @DisplayName("白名單最小化：只有 SecurityUtils 可以讀取 principal")
    void allowListMustContainOnlySecurityUtils() {
        assertThat(GET_PRINCIPAL_ALLOWED)
                .as("白名單只留把 principal 轉成 AuthUser 的唯一入口；日後真的需要再加（須人工確認）。"
                  + "尚未存在的類別不得預先登記，否則放錯套件時防死條目仍綠、同名類別會自動獲得權限。")
                .containsExactly(SECURITY_UTILS);
    }

    @Test
    @DisplayName("規則 (b2)：白名單外不得呼叫 getName()／getUserPrincipal()／getRemoteUser()（現況零違規，無例外清單）")
    void identityNameAccessorsMustNotBeCalledOutsideAllowList() {
        assertThat(findIdentityAccessorCallers(importProduction(), GET_PRINCIPAL_ALLOWED))
                .as("principal 換成 record 後，Authentication／Principal 的 getName() 會回 AuthUser[id=…, uuid=…]，"
                  + "HttpServletRequest 的 getUserPrincipal()／getRemoteUser() 同理，是靜默的語意改變。"
                  + "請改用 SecurityUtils.currentAuthUser(Authentication)。")
                .isEmpty();
    }

    @Test
    @DisplayName("規則 (c)：禁止 @CurrentSecurityContext 與以 meta 標註繞過 (a) 的自訂註解（現況零違規，無例外清單）")
    void principalInjectionBypassesMustNotExist() {
        assertThat(findPrincipalInjectionBypasses(importProduction()))
                .as("@CurrentSecurityContext 與「被 @AuthenticationPrincipal／@CurrentSecurityContext meta 標註的自訂註解」"
                  + "都能繞過規則 (a) 的參數型別檢查，一律禁用（不採「其參數也必須是 AuthUser」的折衷，"
                  + "因為那會要求守衛追蹤任意註解的 meta 鏈與 expression）。")
                .isEmpty();
    }

    @Test
    @DisplayName("防假陰性：正式碼掃描必須在每個預期的業務模組都掃到 @AuthenticationPrincipal 參數")
    void productionScanMustActuallyFindPrincipalParameters() {
        Set<String> modules = findModulesUsingAuthenticationPrincipal(importProduction());

        assertThat(modules)
                .as("若某個模組掃不到任何 @AuthenticationPrincipal 參數，該處的「無違規」就毫無意義（守衛空轉）")
                .containsAll(EXPECTED_PRINCIPAL_MODULES);
    }

    @Test
    @DisplayName("防假陰性：不套白名單時，正式碼掃描必須掃到 SecurityUtils 的 getPrincipal() 呼叫")
    void productionScanMustActuallyFindGetPrincipalCalls() {
        List<String> allCallers = findGetPrincipalCallers(importProduction(), Set.of());

        assertThat(allCallers)
                .as("SecurityUtils.currentAuthUser 必然呼叫 getPrincipal()；掃不到代表 (b) 的偵測壞了（守衛空轉）")
                .contains(SECURITY_UTILS + "#currentAuthUser");
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
    @DisplayName("反向驗證 (a)：抓得到 Long、long、Object 與非首位參數，不誤判 AuthUser 與無標註的 Long")
    void guardDetectsNonAuthUserPrincipalParameters() {
        String fixture = FIXTURE_ROOT + ".param.PrincipalParamFixture";

        List<String> violations = findNonAuthUserPrincipalParameters(importFixtures());

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .containsExactlyInAnyOrder(
                        fixture + "#boxedLong[0]",
                        fixture + "#primitiveLong[0]",
                        fixture + "#looseObject[0]",
                        fixture + "#secondParameter[1]")
                .as("型別為 AuthUser、或 Long 參數沒有 @AuthenticationPrincipal，皆合規")
                .doesNotContain(fixture + "#compliant[0]")
                .doesNotContain(fixture + "#unannotatedLong[0]");
    }

    @Test
    @DisplayName("反向驗證 (b)：抓得到介面、子類別、鏈式、lambda、方法參考的 getPrincipal()，不誤判合規與同名方法")
    void guardDetectsGetPrincipalCalls() {
        String fixture = FIXTURE_ROOT + ".reader.PrincipalReadFixture";

        List<String> violations = findGetPrincipalCallers(importFixtures(), Set.of());

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .contains(fixture + "#viaInterface")
                .contains(fixture + "#viaConcreteToken")
                .contains(fixture + "#viaContextHolder")
                .as("lambda 內的呼叫歸屬於外層方法（ArchUnit 不另列編譯器產生的 lambda$ 方法）")
                .contains(fixture + "#viaLambda")
                .as("方法參考沒有 invoke 指令，也要抓")
                .contains(fixture + "#viaMethodReference")
                .as("getName、SecurityUtils.currentAuthUser、與 Authentication 無關的同名方法皆合規")
                .doesNotContain(fixture + "#compliantGetName")
                .doesNotContain(fixture + "#compliantViaUtils")
                .doesNotContain(fixture + "#unrelatedSameName");
    }

    @Test
    @DisplayName("反向驗證 (b 白名單)：白名單能放行（含 lambda、內部類別），不放白名單時又抓得到")
    void allowListSkipsOnlyListedClasses() {
        String allowed = FIXTURE_ROOT + ".reader.AllowedPrincipalReader";
        String other = FIXTURE_ROOT + ".reader.PrincipalReadFixture";

        List<String> withoutAllowList = findGetPrincipalCallers(importFixtures(), Set.of());
        List<String> withAllowList = findGetPrincipalCallers(importFixtures(), Set.of(allowed));

        assertThat(withoutAllowList)
                .as("不放白名單時，AllowedPrincipalReader 的直接呼叫、lambda、內部類別都該被抓到")
                .contains(allowed + "#direct", allowed + "#inLambda", allowed + "#nested");
        assertThat(withAllowList)
                .as("白名單以最外層類別為單位：直接呼叫、lambda、內部類別都被放行")
                .noneMatch(v -> v.startsWith(allowed + "#"))
                .as("白名單只放行列出的類別，其他違規不受影響")
                .contains(other + "#viaInterface");
    }

    @Test
    @DisplayName("反向驗證 (b2)：抓得到 Authentication／Principal／子類別／方法參考的 getName() 與 request 的 getUserPrincipal()／getRemoteUser()")
    void guardDetectsIdentityAccessorCalls() {
        String fixture = FIXTURE_ROOT + ".name.NameReadFixture";

        List<String> violations = findIdentityAccessorCallers(importFixtures(), Set.of());

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .contains(fixture + "#viaAuthentication")
                .contains(fixture + "#viaPrincipal")
                .contains(fixture + "#viaConcreteToken")
                .as("方法參考沒有 invoke 指令，也要抓")
                .contains(fixture + "#viaMethodReference")
                .contains(fixture + "#viaUserPrincipal")
                .contains(fixture + "#viaRemoteUser")
                .as("request 的其他方法、與 Principal 無關的同名 getName() 皆合規")
                .doesNotContain(fixture + "#compliantHeader")
                .doesNotContain(fixture + "#unrelatedSameName");
    }

    @Test
    @DisplayName("反向驗證 (b2 白名單)：白名單能放行，不放白名單時抓得到")
    void identityAccessorAllowListSkipsOnlyListedClasses() {
        String allowed = FIXTURE_ROOT + ".name.AllowedNameReader";
        String other = FIXTURE_ROOT + ".name.NameReadFixture";

        assertThat(findIdentityAccessorCallers(importFixtures(), Set.of()))
                .contains(allowed + "#name");
        assertThat(findIdentityAccessorCallers(importFixtures(), Set.of(allowed)))
                .doesNotContain(allowed + "#name")
                .contains(other + "#viaAuthentication");
    }

    @Test
    @DisplayName("反向驗證 (c)：抓得到 @CurrentSecurityContext、meta 標註的自訂註解（定義與使用處），不誤判無關註解")
    void guardDetectsPrincipalInjectionBypasses() {
        String pkg = FIXTURE_ROOT + ".inject.";

        List<String> violations = findPrincipalInjectionBypasses(importFixtures());

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .contains(pkg + "InjectionFixture#currentSecurityContext[0]")
                .as("使用被 meta 標註的自訂註解的參數")
                .contains(pkg + "InjectionFixture#customPrincipal[0]")
                .contains(pkg + "InjectionFixture#customSecurityContext[0]")
                .as("自訂註解本身的定義也要抓（禁用定義，使用處就不可能合法出現）")
                .contains(pkg + "CustomPrincipal")
                .contains(pkg + "CustomSecurityContext")
                .as("無關註解與無註解參數皆合規")
                .doesNotContain(pkg + "InjectionFixture#unrelated[0]")
                .doesNotContain(pkg + "InjectionFixture#plain[0]")
                .doesNotContain(pkg + "UnrelatedMarker");
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

    /**
     * 規則 (a)：找出標註 {@code @AuthenticationPrincipal} 但型別不是 {@link AuthUser} 的方法參數。
     *
     * @param classes 待掃描的類集合
     * @return {@code 類別全名#方法名[參數索引]}（排序、去重）
     */
    private static List<String> findNonAuthUserPrincipalParameters(JavaClasses classes) {
        return classes.stream()
                .flatMap(c -> c.getMethods().stream())
                .flatMap(m -> m.getParameters().stream()
                        .filter(PrincipalTypeTest::isAnnotatedWithAuthenticationPrincipal)
                        .filter(p -> !p.getRawType().isEquivalentTo(AuthUser.class))
                        .map(p -> parameterKey(m, p)))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 防空轉用：列出有 {@code @AuthenticationPrincipal} 參數（不論型別）的業務模組。
     *
     * @param classes 待掃描的類集合
     * @return 模組名集合
     */
    private static Set<String> findModulesUsingAuthenticationPrincipal(JavaClasses classes) {
        Set<String> modules = new TreeSet<>();
        for (JavaClass c : classes) {
            String module = moduleOf(c.getPackageName(), MODULE_ROOT);
            boolean uses = c.getMethods().stream()
                    .flatMap(m -> m.getParameters().stream())
                    .anyMatch(PrincipalTypeTest::isAnnotatedWithAuthenticationPrincipal);
            if (module != null && uses) {
                modules.add(module);
            }
        }
        return modules;
    }

    /**
     * 判斷參數是否標註 {@code @AuthenticationPrincipal}。
     *
     * @param parameter 待判斷的參數
     * @return 標註了回傳 true
     */
    private static boolean isAnnotatedWithAuthenticationPrincipal(JavaParameter parameter) {
        return parameter.getAnnotations().stream()
                .anyMatch(a -> a.getRawType().getName().equals(AUTH_PRINCIPAL));
    }

    /**
     * 組出規則 (a) 的違規描述：{@code 類別全名#方法名[參數索引]}。
     *
     * @param method    參數所屬方法
     * @param parameter 違規參數
     * @return 違規描述
     */
    private static String parameterKey(JavaMethod method, JavaParameter parameter) {
        return method.getOwner().getName() + "#" + method.getName() + "[" + parameter.getIndex() + "]";
    }

    /**
     * 規則 (b)：找出白名單外呼叫 {@code Authentication.getPrincipal()} 的程式碼。
     *
     * <p>呼叫目標只要是 {@link Authentication} 的實作或子介面（含 {@code UsernamePasswordAuthenticationToken}
     * 等具體類別）的 {@code getPrincipal} 就算；方法參考與 lambda 內的呼叫也算。
     * 呼叫端的內部類別、匿名類別歸併到最外層類別，白名單以最外層類別全名比對。</p>
     *
     * @param classes   待掃描的類集合
     * @param allowList 白名單（最外層類別全名）
     * @return {@code 呼叫端最外層類別全名#方法名}（排序、去重）
     */
    private static List<String> findGetPrincipalCallers(JavaClasses classes, Set<String> allowList) {
        return classes.stream()
                .flatMap(c -> c.getCodeUnitAccessesFromSelf().stream())
                .filter(PrincipalTypeTest::isGetPrincipalOnAuthentication)
                .filter(a -> !allowList.contains(topLevelName(a.getOriginOwner())))
                .map(a -> topLevelName(a.getOriginOwner()) + "#" + a.getOrigin().getName())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 判斷存取是否為對 {@link Authentication}（含實作類別）的 {@code getPrincipal()} 呼叫或方法參考。
     *
     * @param access 待判斷的存取
     * @return 是 {@code getPrincipal} 且目標型別可指派給 {@code Authentication} 回傳 true
     */
    private static boolean isGetPrincipalOnAuthentication(JavaAccess<?> access) {
        return access.getName().equals("getPrincipal")
                && access.getTargetOwner().isAssignableTo(Authentication.class);
    }

    /**
     * 規則 (b2)：找出白名單外呼叫身分名稱存取器的程式碼：
     * {@code Principal#getName()}（含 {@code Authentication} 與其實作類別）、
     * {@code HttpServletRequest#getUserPrincipal()}、{@code HttpServletRequest#getRemoteUser()}。
     *
     * <p>principal 換成 record 後 {@code getName()} 會回 {@code AuthUser[id=…, uuid=…]}，是靜默的語意改變。
     * 方法參考與 lambda 內的呼叫同樣算；歸併與白名單比對規則同 {@link #findGetPrincipalCallers}。</p>
     *
     * @param classes   待掃描的類集合
     * @param allowList 白名單（最外層類別全名）
     * @return {@code 呼叫端最外層類別全名#方法名}（排序、去重）
     */
    private static List<String> findIdentityAccessorCallers(JavaClasses classes, Set<String> allowList) {
        return classes.stream()
                .flatMap(c -> c.getCodeUnitAccessesFromSelf().stream())
                .filter(PrincipalTypeTest::isIdentityAccessor)
                .filter(a -> !allowList.contains(topLevelName(a.getOriginOwner())))
                .map(a -> topLevelName(a.getOriginOwner()) + "#" + a.getOrigin().getName())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 判斷存取是否為規則 (b2) 禁止的身分名稱存取器。
     *
     * @param access 待判斷的存取
     * @return 符合 (b2) 的三種存取之一回傳 true
     */
    private static boolean isIdentityAccessor(JavaAccess<?> access) {
        JavaClass owner = access.getTargetOwner();
        return switch (access.getName()) {
            case "getName" -> owner.isAssignableTo(PRINCIPAL);
            case "getUserPrincipal", "getRemoteUser" -> owner.isAssignableTo(HTTP_SERVLET_REQUEST);
            default -> false;
        };
    }

    /**
     * 規則 (c)：找出繞過規則 (a) 的注入方式——一律禁用，不追蹤其參數型別。
     *
     * <ul>
     *   <li>參數標註 {@code @CurrentSecurityContext}，或標註「被 {@code @AuthenticationPrincipal}／
     *       {@code @CurrentSecurityContext} 直接或 meta 標註的自訂註解」（key 同規則 (a)：
     *       {@code 類別全名#方法名[參數索引]}）；</li>
     *   <li>這類自訂註解的定義本身（key 為註解全名），使用處因此不可能合法出現。</li>
     * </ul>
     *
     * <p>直接標註 {@code @AuthenticationPrincipal} 的參數由規則 (a) 管，這裡不重複計。</p>
     *
     * @param classes 待掃描的類集合
     * @return 違規描述（排序、去重）
     */
    private static List<String> findPrincipalInjectionBypasses(JavaClasses classes) {
        Stream<String> definitions = classes.stream()
                .filter(JavaClass::isAnnotation)
                .filter(PrincipalTypeTest::isPrincipalInjectionAnnotation)
                .map(JavaClass::getName);
        Stream<String> usages = classes.stream()
                .flatMap(c -> c.getMethods().stream())
                .flatMap(m -> m.getParameters().stream()
                        .filter(p -> p.getAnnotations().stream()
                                .map(JavaAnnotation::getRawType)
                                .anyMatch(PrincipalTypeTest::isForbiddenInjectionAnnotation))
                        .map(p -> parameterKey(m, p)));
        return Stream.concat(definitions, usages).distinct().sorted().toList();
    }

    /**
     * 判斷註解型別是否為被禁用的注入註解：{@code @CurrentSecurityContext} 本身，或被注入註解標註的自訂註解。
     *
     * @param annotationType 註解型別
     * @return 禁用回傳 true；{@code @AuthenticationPrincipal} 本身不算（由規則 (a) 管）
     */
    private static boolean isForbiddenInjectionAnnotation(JavaClass annotationType) {
        return annotationType.getName().equals(CURRENT_SECURITY_CONTEXT)
                || (!annotationType.getName().equals(AUTH_PRINCIPAL)
                        && isPrincipalInjectionAnnotation(annotationType));
    }

    /**
     * 判斷註解型別是否被 {@code @AuthenticationPrincipal} 或 {@code @CurrentSecurityContext}
     * 直接或 meta 標註。
     *
     * @param annotationType 註解型別
     * @return 是回傳 true
     */
    private static boolean isPrincipalInjectionAnnotation(JavaClass annotationType) {
        return annotationType.isAnnotatedWith(AUTH_PRINCIPAL)
                || annotationType.isMetaAnnotatedWith(AUTH_PRINCIPAL)
                || annotationType.isAnnotatedWith(CURRENT_SECURITY_CONTEXT)
                || annotationType.isMetaAnnotatedWith(CURRENT_SECURITY_CONTEXT);
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
