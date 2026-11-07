package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import dowob.xyz.blog.infrastructure.ratelimit.RateLimitProperties;
import dowob.xyz.blog.infrastructure.ratelimit.RateLimited;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 應用層限流守衛（SEC-23 / A4）：指定清單上的 handler 必須帶有對應的 {@code @RateLimited} 規則，
 * 防止日後新增或改名端點時漏掉限流；同時 {@code application.yaml} 的門檻必須等於 Yuan 2026-11-07 拍板值。
 *
 * <p>寫法參考 {@link EndpointAuthorizationTest}。清單是 A4 決策表（含 2026-11-07 Yuan 追加決策）的機器可讀投影：新增需限流的端點時，
 * 先在決策文件確認門檻，再同步更新本清單與 yaml。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("應用層限流守衛（A4）")
class RateLimitedEndpointGuardTest {

    /** 規則名稱常量，避免字串散落 */
    private static final String COMMENT_WRITE = "comment-write";
    private static final String INTERACTION_WRITE = "interaction-write";
    private static final String UPLOAD = "upload";
    private static final String ARTICLE_WRITE = "article-write";
    private static final String READING_PROGRESS = "reading-progress";
    private static final String ACCOUNT_WRITE = "account-write";
    private static final String SEARCH = "search";
    private static final String SEARCH_SUGGEST = "search-suggest";

    /**
     * 必須帶規則的 handler：{@code 類別#方法} → 規則名稱。
     *
     * <p>刪除類（取消按讚、取消收藏、刪留言／文章／檔案／劃線）與讀取類不在清單內（決策 A4：寫入類才限流）；
     * 寫入類 handler 的完整覆蓋由 {@link #everyWriteHandlerMustBeRateLimitedOrExempt()} 守衛。</p>
     */
    private static final Map<String, String> EXPECTED = new TreeMap<>(Map.ofEntries(
            Map.entry("CommentController#create", COMMENT_WRITE),
            Map.entry("CommentController#edit", COMMENT_WRITE),
            Map.entry("CommentLikeController#like", INTERACTION_WRITE),
            Map.entry("ArticleLikeController#like", INTERACTION_WRITE),
            Map.entry("BookmarkController#bookmark", INTERACTION_WRITE),
            Map.entry("HighlightController#create", INTERACTION_WRITE),
            Map.entry("HighlightController#update", INTERACTION_WRITE),
            Map.entry("TagController#followTag", INTERACTION_WRITE),
            Map.entry("ReadingProgressController#update", READING_PROGRESS),
            Map.entry("FileController#uploadFile", UPLOAD),
            Map.entry("ArticleController#createArticle", ARTICLE_WRITE),
            Map.entry("ArticleController#updateArticle", ARTICLE_WRITE),
            Map.entry("ArticleController#submitForReview", ARTICLE_WRITE),
            Map.entry("ArticleController#withdrawArticle", ARTICLE_WRITE),
            Map.entry("ArticleController#publishArticle", ARTICLE_WRITE),
            Map.entry("VersionController#createManual", ARTICLE_WRITE),
            Map.entry("VersionController#restore", ARTICLE_WRITE),
            Map.entry("VersionController#promote", ARTICLE_WRITE),
            Map.entry("SeriesController#create", ARTICLE_WRITE),
            Map.entry("SeriesController#update", ARTICLE_WRITE),
            Map.entry("SeriesController#addArticle", ARTICLE_WRITE),
            Map.entry("UserController#updateProfile", ACCOUNT_WRITE),
            Map.entry("UserController#updateNotifications", ACCOUNT_WRITE),
            Map.entry("UserController#changePassword", ACCOUNT_WRITE),
            Map.entry("PreferenceController#update", ACCOUNT_WRITE),
            Map.entry("SearchController#search", SEARCH),
            Map.entry("SearchController#suggest", SEARCH_SUGGEST)));

    private static JavaClasses importProduction() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dowob.xyz.blog.module");
    }

    /** 寫入類 HTTP 方法（DELETE、GET 不在守衛範圍） */
    private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "PATCH");

    /**
     * 明確不套用通用限流的寫入 handler：{@code 類別#方法} → 一行理由。
     *
     * <p>新增豁免必須寫理由；死條目（handler 已不存在或已改標 {@code @RateLimited}）會被
     * {@link #exemptListMustNotContainStaleEntries()} 擋下。</p>
     */
    private static final Map<String, String> EXEMPT = new TreeMap<>(Map.ofEntries(
            Map.entry("AuthController#register", "auth 流程：已有 per-IP 註冊限流（A0113）"),
            Map.entry("AuthController#login", "auth 流程：已有 per-IP 與失敗鎖定限流（A0113）"),
            Map.entry("AuthController#verifyEmailCode", "auth 流程：已有 per-IP 與失敗次數限流（A0113）"),
            Map.entry("AuthController#resendVerification", "auth 流程：已有 per-email 與 per-IP 限流（A0113）"),
            Map.entry("AuthController#forgotPassword", "auth 流程：已有 per-email 與 per-IP 限流（A0113）"),
            Map.entry("AuthController#refresh", "auth 流程：需有效 refresh token cookie，無 token 即被拒，不產生內容寫入"),
            Map.entry("AuthController#logout", "auth 流程：需認證、冪等，僅撤銷自己的 session"),
            Map.entry("AuthController#verifyEmail", "auth 流程：一次性高熵驗證 token，無 token 無法猜測"),
            Map.entry("AuthController#resetPassword", "auth 流程：一次性高熵重設 token，無 token 無法猜測"),
            Map.entry("AdminArticleController#archiveArticle", "admin：受信任角色、量少（Yuan 2026-11-07 傾向豁免）"),
            Map.entry("AdminArticleController#unarchiveArticle", "admin：受信任角色、量少"),
            Map.entry("AdminCategoryController#createCategory", "admin：受信任角色、量少"),
            Map.entry("AdminCategoryController#updateCategory", "admin：受信任角色、量少"),
            Map.entry("AdminTagController#updateTag", "admin：受信任角色、量少"),
            Map.entry("AdminSearchController#reindex", "admin：受信任角色、量少（W7-f 另行處理 reindex 並發保護）"),
            Map.entry("ArticleController#rejectArticle", "管理審核動作（ADMIN_ARTICLE_REVIEW）：受信任角色、量少")));

    /** handler 是否為 POST／PUT／PATCH（含 {@code @RequestMapping(method=...)}；{@code @RequestMapping} 未指定 method 視為寫入） */
    private static boolean isWriteHandler(JavaMethod method) {
        for (JavaAnnotation<JavaMethod> a : method.getAnnotations()) {
            String name = a.getRawType().getSimpleName();
            if (name.equals("PostMapping") || name.equals("PutMapping") || name.equals("PatchMapping")) {
                return true;
            }
            if (name.equals("RequestMapping")) {
                // method 是 enum 陣列：ArchUnit 以 Object[]（JavaEnumConstant）回傳，需先展開才能比對名稱
                String methods = a.get("method")
                        .map(v -> v instanceof Object[] arr ? java.util.Arrays.toString(arr) : v.toString())
                        .orElse("[]");
                if (methods.equals("[]") || WRITE_METHODS.stream().anyMatch(methods::contains)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 方法層優先，其次類別層的規則名稱；沒有則為 null */
    private static String ruleOf(JavaMethod method) {
        return method.tryGetAnnotationOfType(RateLimited.class)
                .or(() -> method.getOwner().tryGetAnnotationOfType(RateLimited.class))
                .map(RateLimited::value)
                .orElse(null);
    }

    private static Map<String, String> actualRules(JavaClasses classes) {
        Map<String, String> actual = new TreeMap<>();
        for (JavaMethod method : classes.stream().flatMap(c -> c.getMethods().stream()).toList()) {
            if (isHandlerLike(method) && ruleOf(method) != null) {
                actual.put(method.getOwner().getSimpleName() + "#" + method.getName(), ruleOf(method));
            }
        }
        return actual;
    }

    /** 帶任何 MVC 映射標註的方法 */
    private static boolean isHandlerLike(JavaMethod method) {
        return method.getAnnotations().stream().anyMatch(a -> a.getRawType().getSimpleName().endsWith("Mapping"));
    }

    /** 所有「寫入 handler 卻沒有 @RateLimited 也不在 EXEMPT」的 {@code 類別#方法} */
    private static Set<String> unmarkedWriteHandlers(JavaClasses classes, Set<String> exempt) {
        Set<String> result = new TreeSet<>();
        for (JavaMethod method : classes.stream().flatMap(c -> c.getMethods().stream()).toList()) {
            String key = method.getOwner().getSimpleName() + "#" + method.getName();
            if (isWriteHandler(method) && ruleOf(method) == null && !exempt.contains(key)) {
                result.add(key);
            }
        }
        return result;
    }

    @Test
    @DisplayName("指定端點必須帶有對應的 @RateLimited 規則")
    void expectedEndpointsMustDeclareRule() {
        Map<String, String> actual = actualRules(importProduction());

        assertThat(actual)
                .as("A4 清單上的端點缺少 @RateLimited 或規則名稱不符；新增寫入端點請依決策表補上")
                .containsAllEntriesOf(EXPECTED);
    }

    @Test
    @DisplayName("防腐：@RateLimited 只能出現在清單內的端點（新增限流端點須有意識地更新清單與 yaml）")
    void noUnlistedRateLimitedEndpoints() {
        assertThat(actualRules(importProduction()).keySet())
                .as("出現清單外的 @RateLimited handler，請同步更新 EXPECTED 與 security.md 規則表")
                .isSubsetOf(EXPECTED.keySet());
    }

    @Test
    @DisplayName("守衛 M1：所有 POST／PUT／PATCH handler 必須帶 @RateLimited（方法或類別層）或列入 EXEMPT")
    void everyWriteHandlerMustBeRateLimitedOrExempt() {
        assertThat(unmarkedWriteHandlers(importProduction(), EXEMPT.keySet()))
                .as("以下寫入 handler 既沒有 @RateLimited 也不在 EXEMPT。新增寫入端點請決定門檻並標註，"
                        + "或在 EXEMPT 寫明不套用的理由（DELETE 不在守衛範圍）")
                .isEmpty();
    }

    @Test
    @DisplayName("防腐：EXEMPT 的 handler 必須存在，且確實沒有 @RateLimited")
    void exemptListMustNotContainStaleEntries() {
        JavaClasses classes = importProduction();
        Set<String> writeHandlers = new TreeSet<>();
        for (JavaMethod method : classes.stream().flatMap(c -> c.getMethods().stream()).toList()) {
            if (isWriteHandler(method) && ruleOf(method) == null) {
                writeHandlers.add(method.getOwner().getSimpleName() + "#" + method.getName());
            }
        }
        assertThat(EXEMPT.keySet())
                .as("EXEMPT 內的條目已不存在、已改成非寫入、或已標 @RateLimited，請移除")
                .allSatisfy(key -> assertThat(writeHandlers).contains(key));
        assertThat(EXEMPT.values()).allSatisfy(reason -> assertThat(reason).isNotBlank());
    }

    @Test
    @DisplayName("反向驗證：守衛抓得到各種寫法的未標註寫入 handler，且不誤判已標註、類別層、GET、DELETE")
    void guardDetectsUnmarkedWriteHandlers() {
        JavaClasses fixtures = new ClassFileImporter()
                .importPackages("dowob.xyz.blog.architecture.fixture.ratelimit");

        assertThat(unmarkedWriteHandlers(fixtures, Set.of()))
                .containsExactlyInAnyOrder(
                        "RateLimitWriteFixture#unmarkedPost",
                        "RateLimitWriteFixture#unmarkedPut",
                        "RateLimitWriteFixture#unmarkedPatch",
                        "RateLimitWriteFixture#unmarkedRequestMappingPost");
        assertThat(actualRules(fixtures)).containsEntry("ClassLevelMarked#post", "fixture");
    }

    @Test
    @DisplayName("application.yaml 的規則齊全，且門檻、窗口、計數主體等於 A4 決策")
    void yamlMatchesDecisionTable() throws Exception {
        RateLimitProperties props = bindProductionYaml();

        assertThat(props.rules().keySet()).containsExactlyInAnyOrder(
                COMMENT_WRITE, INTERACTION_WRITE, READING_PROGRESS, UPLOAD, ARTICLE_WRITE, ACCOUNT_WRITE,
                SEARCH, SEARCH_SUGGEST);
        assertRule(props, COMMENT_WRITE, 10, Duration.ofMinutes(1), RateLimitProperties.Subject.USER);
        assertRule(props, INTERACTION_WRITE, 60, Duration.ofMinutes(1), RateLimitProperties.Subject.USER);
        assertRule(props, READING_PROGRESS, 120, Duration.ofMinutes(1), RateLimitProperties.Subject.USER);
        assertRule(props, UPLOAD, 30, Duration.ofHours(1), RateLimitProperties.Subject.USER);
        assertRule(props, ARTICLE_WRITE, 30, Duration.ofMinutes(1), RateLimitProperties.Subject.USER);
        assertRule(props, ACCOUNT_WRITE, 30, Duration.ofMinutes(1), RateLimitProperties.Subject.USER);
        assertRule(props, SEARCH, 120, Duration.ofMinutes(1), RateLimitProperties.Subject.IP);
        assertRule(props, SEARCH_SUGGEST, 120, Duration.ofMinutes(1), RateLimitProperties.Subject.IP);
        assertThat(EXPECTED.values()).allSatisfy(rule -> assertThat(props.rules()).containsKey(rule));
    }

    private static void assertRule(RateLimitProperties props, String name, int limit, Duration window,
                                   RateLimitProperties.Subject subject) {
        assertThat(props.rules().get(name))
                .as("規則 %s", name)
                .isEqualTo(new RateLimitProperties.Rule(limit, window, subject));
    }

    /** 以 Spring Boot 的 placeholder 規則解析 application.yaml（環境變數未設定時取預設值） */
    private static RateLimitProperties bindProductionYaml() throws Exception {
        List<PropertySource<?>> loaded = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yaml"));
        MutablePropertySources sources = new MutablePropertySources();
        loaded.forEach(sources::addLast);
        sources.addFirst(new MapPropertySource("empty-env", Map.of()));
        return new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources))
                .bind("app.rate-limit", Bindable.of(RateLimitProperties.class))
                .orElseThrow(() -> new AssertionError("application.yaml 缺少 app.rate-limit"));
    }
}
