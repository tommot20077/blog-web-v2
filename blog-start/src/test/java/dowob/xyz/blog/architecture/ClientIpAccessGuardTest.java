package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client IP 取得入口守衛（findings SEC-01 第 3 層）。
 *
 * <p><b>規則</b>：除 {@link #ALLOWED ClientIpResolver} 外，{@code src/main} 不得自行取得 client IP：</p>
 * <ol>
 *   <li>(a) 不得呼叫下列「取 remote 位址」方法（含子型別、lambda 與方法參考；以 ArchUnit 分析 bytecode）：
 *       {@code ServletRequest#getRemoteAddr()}／{@code getRemoteHost()}、
 *       {@code org.springframework.http.server.ServerHttpRequest#getRemoteAddress()}
 *       （例如 {@code new ServletServerHttpRequest(r).getRemoteAddress()}）、
 *       {@code WebAuthenticationDetails#getRemoteAddress()}
 *       （例如 {@code new WebAuthenticationDetailsSource().buildDetails(r).getRemoteAddress()}）；</li>
 *   <li>(b) 不得出現下列 header 名稱的字面值（不分大小寫）：{@code X-Forwarded-*}（For／Proto／Host／Port 與任何前綴）、
 *       {@code Forwarded}（RFC 7239，只比對整個字串字面值）、{@code X-Original-Forwarded-For}、{@code X-Real-IP}、
 *       {@code X-Client-IP}、{@code CF-Connecting-IP}、{@code True-Client-IP}。
 *       ArchUnit 看不到字串常數，故掃描各模組 {@code src/main/java} 的原始碼（先剝除註解，避免文件敘述誤報）。
 *       <b>例外依檔案與 token 各自限定</b>（{@link #ALLOWED_TOKENS_BY_FILE}）：{@code ClientIpResolver.java} 只能出現
 *       {@code X-Forwarded-For}；{@code TrustedForwardedRequestFilter.java} 只能出現
 *       {@code X-Forwarded-Proto／Host／Port}（它不處理任何 IP，故讀 XFF 或 {@code Forwarded} 仍違規）。</li>
 * </ol>
 *
 * <p><b>為何需要守衛</b>：SEC-01 的根因是「橫切關注點被複製進兩個 Controller 的 private method，
 * 兩份對可信 IP 的假設互相矛盾」。信任鏈只能有一個定義處（{@code app.http.trusted-proxy-count}），
 * 新增的 controller 若自行讀 XFF／remoteAddr，就會悄悄重開繞過。</p>
 *
 * <p><b>防假陰性</b>：每條規則各有反向驗證——fixture／內嵌原始碼片段的故意違規必須被抓到、合規與白名單必須放行；
 * 且不套白名單時，正式碼必須掃到 {@code ClientIpResolver} 與 {@code TrustedForwardedRequestFilter} 本身
 * （證明掃描沒有空轉）。</p>
 *
 * <p><b>已知限制（靜態守衛無法完全擋住，需靠 code review）</b>：</p>
 * <ul>
 *   <li>字串<b>拼接</b>無法完全抓到：{@code "X-Forwarded-" + "For"}（前綴 {@code x-forwarded-} 仍在同一字面值內）
 *       會被抓到，但 {@code "X-" + "Forwarded-For"}、{@code "For" + "warded"}、{@code String.join}、
 *       {@code StringBuilder}、由設定檔／常數表／環境變數讀入 header 名稱，或逐一列舉
 *       {@code getHeaderNames()} 後以動態名稱比對，都不在偵測範圍；</li>
 *   <li>反射、{@code MethodHandle} 與第三方函式庫內部自行讀 header（例如啟用 {@code ForwardedHeaderFilter}、
 *       Tomcat {@code RemoteIpValve}）不在此守衛範圍，後者由 {@code ClientIpTrustConfigTest} 守設定；</li>
 *   <li>{@code getLocalAddr}、{@code getRemotePort} 等與 client IP 無關的存取不擋。</li>
 * </ul>
 *
 * @author Yuan
 * @version 2.0
 */
class ClientIpAccessGuardTest {

    /** 正式碼根套件 */
    private static final String PRODUCTION_ROOT = "dowob.xyz.blog";

    /** fixture 根套件 */
    private static final String FIXTURE_ROOT = "dowob.xyz.blog.architecture.fixture.clientip";

    /** 唯一允許取得 client IP 的類別（規則 (a)），及其原始碼檔名（規則 (b)） */
    private static final String ALLOWED = "dowob.xyz.blog.infrastructure.web.ClientIpResolver";

    /** 唯一允許出現 client IP header 字面值的原始碼檔名 */
    private static final String ALLOWED_SOURCE_FILE = "ClientIpResolver.java";

    /** 新增的 TrustedForwardedRequestFilter 原始碼檔名（唯一允許讀 X-Forwarded-Proto／Host／Port） */
    private static final String FORWARDED_FILTER_SOURCE_FILE = "TrustedForwardedRequestFilter.java";

    /**
     * 規則 (a) 要擋的取 remote 位址呼叫：型別（含所有子型別）→ 方法名稱。
     * <ul>
     *   <li>{@code ServletRequest}：{@code getRemoteAddr}／{@code getRemoteHost}；</li>
     *   <li>{@code ServerHttpRequest}（含 {@code ServletServerHttpRequest}）：{@code getRemoteAddress}；</li>
     *   <li>{@code WebAuthenticationDetails}：{@code getRemoteAddress}
     *       （{@code WebAuthenticationDetailsSource#buildDetails} 的回傳型別）。</li>
     * </ul>
     */
    private static final Map<String, Set<String>> FORBIDDEN_CALLS = Map.of(
            "jakarta.servlet.ServletRequest", Set.of("getRemoteAddr", "getRemoteHost"),
            "org.springframework.http.server.ServerHttpRequest", Set.of("getRemoteAddress"),
            "org.springframework.security.web.authentication.WebAuthenticationDetails", Set.of("getRemoteAddress"));

    /**
     * 規則 (b)：client IP／forwarded 相關 header 名稱（不分大小寫，比對整份去註解後的原始碼）。
     * 連字號 header 名稱只可能出現在字串字面值中；{@code x-forwarded-} 前綴用來抓 {@code "X-Forwarded-" + "For"} 拼接。
     */
    private static final Pattern IP_HEADER_TOKEN = Pattern.compile(
            "x-original-forwarded-for|x-forwarded(?:-[a-z]*)?|x-real-ip|x-client-ip|cf-connecting-ip|true-client-ip",
            Pattern.CASE_INSENSITIVE);

    /** 規則 (b)：RFC 7239 的 {@code Forwarded} 只比對「整個字串字面值就是它」，避免誤報 ForwardedHeaderFilter 等識別字與訊息 */
    private static final Pattern FORWARDED_STRING_LITERAL = Pattern.compile(
            "\"\\s*forwarded\\s*\"", Pattern.CASE_INSENSITIVE);

    /** 每個原始碼檔名允許出現的 header token（小寫）：兩個檔案各自只能碰自己職責範圍內的 header */
    private static final Map<String, Set<String>> ALLOWED_TOKENS_BY_FILE = Map.of(
            ALLOWED_SOURCE_FILE, Set.of("x-forwarded-for"),
            FORWARDED_FILTER_SOURCE_FILE, Set.of("x-forwarded-proto", "x-forwarded-host", "x-forwarded-port"));

    /** 區塊註解（含 JavaDoc） */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** 行註解（需在行首或空白後，避免吃掉字串內的 {@code http://}） */
    private static final Pattern LINE_COMMENT = Pattern.compile("(?m)(^|\\s)//.*$");

    @Test
    @DisplayName("規則 (a)：除 ClientIpResolver 外，正式碼不得呼叫 getRemoteAddr()／getRemoteHost()")
    void productionMustNotReadRemoteAddrOutsideResolver() {
        assertThat(findRemoteAddrCallers(importProduction(), Set.of(ALLOWED)))
                .as("取得 client IP 的唯一入口是 ClientIpResolver（信任鏈由 app.http.trusted-proxy-count 控制）。"
                  + "請注入 ClientIpResolver 並呼叫 resolve(request)。")
                .isEmpty();
    }

    @Test
    @DisplayName("規則 (b)：除 ClientIpResolver 外，正式碼不得出現 X-Forwarded-For 等 client IP header 字面值")
    void productionMustNotMentionIpHeadersOutsideResolver() {
        Path root = repositoryRoot();

        assertThat(findHeaderLiteralFiles(root, ALLOWED_TOKENS_BY_FILE))
                .as("直接讀 XFF 等 header 會繞過信任鏈設定（SEC-01）。請改用 ClientIpResolver。")
                .isEmpty();
    }

    @Test
    @DisplayName("防假陰性 (a)：不套白名單時，正式碼必須掃到 ClientIpResolver 的 getRemoteAddr() 呼叫")
    void productionScanMustFindResolverItself() {
        assertThat(findRemoteAddrCallers(importProduction(), Set.of()))
                .as("ClientIpResolver 必然呼叫 getRemoteAddr()；掃不到代表偵測壞了（守衛空轉）")
                .contains(ALLOWED + "#resolve");
    }

    @Test
    @DisplayName("防假陰性 (b)：不套白名單時，原始碼掃描必須掃到 ClientIpResolver.java 的 header 字面值")
    void sourceScanMustFindResolverItself() {
        assertThat(findHeaderLiteralFiles(repositoryRoot(), Map.of()))
                .as("ClientIpResolver.java 必然含 X-Forwarded-For 字面值；掃不到代表掃描路徑或剝註解壞了（守衛空轉）")
                .anyMatch(path -> path.endsWith(ALLOWED_SOURCE_FILE));
    }

    @Test
    @DisplayName("防假陰性 (b)：不套白名單時，原始碼掃描必須掃到 TrustedForwardedRequestFilter.java 的 X-Forwarded-Proto 字面值")
    void sourceScanMustFindForwardedFilterItself() {
        assertThat(findHeaderLiteralFiles(repositoryRoot(), Map.of()))
                .as("TrustedForwardedRequestFilter.java 必然含 X-Forwarded-Proto 字面值；掃不到代表守衛空轉")
                .anyMatch(path -> path.endsWith(FORWARDED_FILTER_SOURCE_FILE));
    }

    @Test
    @DisplayName("tripwire：正式碼匯入不得包含 architecture.fixture（否則故意違規會污染結果）")
    void productionScanMustNotContainFixtures() {
        assertThat(importProduction().stream()
                .filter(c -> c.getPackageName().contains("architecture.fixture"))
                .map(JavaClass::getName)
                .toList()).isEmpty();
    }

    @Test
    @DisplayName("反向驗證 (a)：抓得到 HttpServletRequest／ServletRequest／getRemoteHost／方法參考／lambda，不誤判無關 accessor")
    void guardDetectsRemoteAddrReads() {
        String fixture = FIXTURE_ROOT + ".RemoteAddrReadFixture";

        List<String> violations = findRemoteAddrCallers(importFixtures(), Set.of());

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .contains(fixture + "#viaHttpServletRequest")
                .contains(fixture + "#viaServletRequest")
                .contains(fixture + "#viaRemoteHost")
                .contains(fixture + "#viaMethodReference")
                .contains(fixture + "#viaLambda")
                .as("getMethod／getRemotePort 與 client IP 無關")
                .doesNotContain(fixture + "#compliantUnrelatedAccessor");
    }

    @Test
    @DisplayName("反向驗證 (a 白名單)：白名單能放行指定類別，其他違規不受影響")
    void allowListSkipsOnlyListedClasses() {
        String allowed = FIXTURE_ROOT + ".AllowedRemoteAddrReader";
        String other = FIXTURE_ROOT + ".RemoteAddrReadFixture";

        List<String> withAllowList = findRemoteAddrCallers(importFixtures(), Set.of(allowed));

        assertThat(findRemoteAddrCallers(importFixtures(), Set.of())).contains(allowed + "#direct");
        assertThat(withAllowList)
                .noneMatch(v -> v.startsWith(allowed + "#"))
                .contains(other + "#viaHttpServletRequest");
    }

    @Test
    @DisplayName("反向驗證 (b)：程式碼中的 header 字面值（任意大小寫、四種 header）被抓到")
    void literalGuardDetectsHeaderNamesInCode() {
        assertThat(containsHeaderLiteral("String h = request.getHeader(\"X-Forwarded-For\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = request.getHeader(\"x-forwarded-for\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = request.getHeader(\"X-REAL-IP\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"CF-Connecting-IP\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"True-Client-IP\");")).isTrue();
        assertThat(containsHeaderLiteral("String u = \"http://x\"; String h = \"X-Forwarded-For\";")).isTrue();
    }

    @Test
    @DisplayName("反向驗證 (b)：註解中的敘述與無關 header 不誤報")
    void literalGuardIgnoresCommentsAndUnrelatedHeaders() {
        assertThat(containsHeaderLiteral("/** 取 X-Forwarded-For 由 ClientIpResolver 負責 */ int a = 1;")).isFalse();
        assertThat(containsHeaderLiteral("/*\n * X-Real-IP\n */\nint a = 1;")).isFalse();
        assertThat(containsHeaderLiteral("int a = 1; // X-Forwarded-For 見 ClientIpResolver")).isFalse();
        assertThat(containsHeaderLiteral("String h = request.getHeader(\"Authorization\");")).isFalse();
        // F3 後 X-Forwarded-Proto 也在清單內（唯一例外是 TrustedForwardedRequestFilter），故不再列為「無關 header」
        assertThat(containsHeaderLiteral("String h = request.getHeader(\"Content-Type\");")).isFalse();
        assertThat(containsHeaderLiteral("String h = request.getHeader(\"Origin\");")).isFalse();
    }

    @Test
    @DisplayName("反向驗證 (b)：目錄掃描抓得到違規檔、放行白名單檔名與非 main 路徑")
    void directoryScanFlagsViolatorsAndHonorsAllowList(@TempDir Path tmp) throws IOException {
        write(tmp, "blog-module-x/src/main/java/a/Bad.java", "class Bad { String s = \"X-Forwarded-For\"; }");
        write(tmp, "blog-module-x/src/main/java/a/Good.java", "class Good { /* X-Forwarded-For */ }");
        write(tmp, "blog-module-y/src/main/java/b/ClientIpResolver.java", "class R { String s = \"X-Forwarded-For\"; }");
        write(tmp, "blog-module-y/src/test/java/b/SomeTest.java", "class T { String s = \"X-Forwarded-For\"; }");

        List<String> flagged = findHeaderLiteralFiles(tmp, ALLOWED_TOKENS_BY_FILE);

        assertThat(flagged).hasSize(1).allMatch(p -> p.endsWith("Bad.java"));
    }

    @Test
    @DisplayName("F3 反向驗證 (b)：新增的 header 名稱（Forwarded／X-Client-IP／X-Original-Forwarded-For 等）被抓到")
    void literalGuardDetectsExtendedHeaderNames() {
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"Forwarded\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"forwarded\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = r.getHeader(\" FORWARDED \");")).isTrue();
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"X-Client-IP\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"x-client-ip\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"X-Original-Forwarded-For\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"CF-Connecting-IP\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"True-Client-IP\");")).isTrue();
    }

    @Test
    @DisplayName("F3 反向驗證 (b)：X-Forwarded-Proto／Host／Port 也在清單內（唯一例外是 TrustedForwardedRequestFilter）")
    void literalGuardDetectsForwardedProtoHostPort() {
        assertThat(containsHeaderLiteral("r.getHeader(\"X-Forwarded-Proto\");")).isTrue();
        assertThat(containsHeaderLiteral("r.getHeader(\"x-forwarded-host\");")).isTrue();
        assertThat(containsHeaderLiteral("r.getHeader(\"X-FORWARDED-PORT\");")).isTrue();
    }

    @Test
    @DisplayName("F3 反向驗證 (b)：\"X-Forwarded-\" + \"For\" 前綴拼接被抓到；其餘拼接方式屬已知限制（見類別 JavaDoc）")
    void literalGuardDetectsXForwardedPrefixConcatenation() {
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"X-Forwarded-\" + \"For\");")).isTrue();
        assertThat(containsHeaderLiteral("String h = r.getHeader(\"x-forwarded-\".concat(\"for\"));")).isTrue();
    }

    @Test
    @DisplayName("F3 反向驗證 (b)：\"Forwarded\" 只比對完整字面值，不誤報含 Forwarded 的識別字、訊息與其他 header")
    void literalGuardDoesNotOverMatchForwardedWord() {
        assertThat(containsHeaderLiteral("ForwardedHeaderFilter f = null;")).isFalse();
        assertThat(containsHeaderLiteral("String m = \"Forwarded request failed\";")).isFalse();
        assertThat(containsHeaderLiteral("boolean forwarded = true;")).isFalse();
        assertThat(containsHeaderLiteral("String m = \"forwardedCount\";")).isFalse();
    }

    @Test
    @DisplayName("F3 反向驗證 (b)：允許集合依檔案區分——resolver 只能有 XFF、新 filter 只能有 Proto/Host/Port")
    void allowListIsPerFileAndPerToken(@TempDir Path tmp) throws IOException {
        write(tmp, "blog-infrastructure/src/main/java/w/ClientIpResolver.java",
                "class R { String s = \"X-Forwarded-For\"; }");
        write(tmp, "blog-infrastructure/src/main/java/w/TrustedForwardedRequestFilter.java",
                "class F { String a = \"X-Forwarded-Proto\"; String b = \"X-Forwarded-Host\"; String c = \"X-Forwarded-Port\"; }");
        // 越權：resolver 讀 Proto、filter 讀 XFF／Forwarded
        write(tmp, "blog-module-x/src/main/java/a/ClientIpResolver.java",
                "class R { String s = \"X-Forwarded-Proto\"; }");
        write(tmp, "blog-module-y/src/main/java/b/TrustedForwardedRequestFilter.java",
                "class F { String a = \"X-Forwarded-Proto\"; String b = \"X-Forwarded-For\"; }");
        write(tmp, "blog-module-z/src/main/java/c/TrustedForwardedRequestFilter.java",
                "class F { String a = \"Forwarded\"; }");
        // 其他檔案讀 Proto
        write(tmp, "blog-module-w/src/main/java/d/SomeController.java",
                "class C { String a = \"X-Forwarded-Proto\"; }");

        List<String> flagged = findHeaderLiteralFiles(tmp, ALLOWED_TOKENS_BY_FILE);

        assertThat(flagged)
                .hasSize(4)
                .noneMatch(p -> p.contains("blog-infrastructure"))
                .anyMatch(p -> p.contains("blog-module-x"))
                .anyMatch(p -> p.contains("blog-module-y"))
                .anyMatch(p -> p.contains("blog-module-z"))
                .anyMatch(p -> p.contains("blog-module-w"));
    }

    @Test
    @DisplayName("F3 反向驗證 (a)：抓得到 ServerHttpRequest#getRemoteAddress 與 WebAuthenticationDetails#getRemoteAddress，不誤判無關 accessor")
    void guardDetectsSpringRemoteAddressReads() {
        String fixture = FIXTURE_ROOT + ".SpringRemoteAddressReadFixture";

        List<String> violations = findRemoteAddrCallers(importFixtures(), Set.of());

        assertThat(violations)
                .contains(fixture + "#viaServletServerHttpRequest")
                .contains(fixture + "#viaServerHttpRequestInterface")
                .contains(fixture + "#viaAuthenticationDetailsSource")
                .contains(fixture + "#viaAuthenticationDetailsConstructor")
                .contains(fixture + "#viaMethodReference")
                .as("getSessionId／getURI 與 client IP 無關")
                .doesNotContain(fixture + "#compliantUnrelatedAccessors");
    }

    @Test
    @DisplayName("F3：正式碼（白名單外）不得出現 Spring 的 getRemoteAddress 讀取")
    void productionMustNotUseSpringRemoteAddressAccessors() {
        assertThat(findRemoteAddrCallers(importProduction(), Set.of(ALLOWED)))
                .as("ServerHttpRequest／WebAuthenticationDetails 的 getRemoteAddress 同樣會繞過 ClientIpResolver")
                .isEmpty();
    }

    // ───────────────────────── 守衛判定邏輯 ─────────────────────────

    /**
     * 匯入正式碼（不含測試 source，故 fixture 不會被掃進來）。
     *
     * @return 正式碼類集合
     */
    private static JavaClasses importProduction() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(PRODUCTION_ROOT);
    }

    /**
     * 匯入反向驗證 fixture。
     *
     * @return fixture 類集合
     */
    private static JavaClasses importFixtures() {
        return new ClassFileImporter().importPackages(FIXTURE_ROOT);
    }

    /**
     * 規則 (a)：找出白名單外呼叫 servlet {@code getRemoteAddr()}／{@code getRemoteHost()} 的程式碼。
     *
     * @param classes   待掃描的類集合
     * @param allowList 允許呼叫的最外層類別全名
     * @return {@code 呼叫端最外層類別全名#方法名}（排序、去重）
     */
    private static List<String> findRemoteAddrCallers(JavaClasses classes, Set<String> allowList) {
        return classes.stream()
                .flatMap(c -> c.getCodeUnitAccessesFromSelf().stream())
                .filter(ClientIpAccessGuardTest::isRemoteAddrAccess)
                .filter(a -> !allowList.contains(topLevelName(a.getOriginOwner())))
                .map(a -> topLevelName(a.getOriginOwner()) + "#" + a.getOrigin().getName())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * 是否為 {@link #FORBIDDEN_CALLS} 內的存取（含子型別、方法參考與 lambda）。
     *
     * @param access 程式碼單元存取
     * @return 是則 true
     */
    private static boolean isRemoteAddrAccess(JavaAccess<?> access) {
        return FORBIDDEN_CALLS.entrySet().stream()
                .anyMatch(rule -> rule.getValue().contains(access.getName())
                        && access.getTargetOwner().isAssignableTo(rule.getKey()));
    }

    /**
     * 規則 (b)：掃描各模組 {@code src/main/java}，回傳白名單檔名以外、程式碼（非註解）含 client IP header 字面值的檔案。
     *
     * @param root            repo 根目錄
     * @param allowedFileName 允許的檔名（例如 {@code ClientIpResolver.java}）
     * @return 違規檔案的絕對路徑（排序）
     */
    private static List<String> findHeaderLiteralFiles(Path root, Map<String, Set<String>> allowedTokensByFile) {
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .filter(p -> hasDisallowedToken(read(p),
                            allowedTokensByFile.getOrDefault(p.getFileName().toString(), Set.of())))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 原始碼是否含「不在允許集合內」的 client IP header token。
     *
     * @param source        Java 原始碼
     * @param allowedTokens 此檔案允許的 token（小寫）
     * @return 是則 true
     */
    private static boolean hasDisallowedToken(String source, Set<String> allowedTokens) {
        return !headerTokens(source).stream().filter(t -> !allowedTokens.contains(t)).toList().isEmpty();
    }

    /**
     * 剝除註解後，原始碼是否含 client IP header 名稱。
     *
     * @param source Java 原始碼
     * @return 是則 true
     */
    private static boolean containsHeaderLiteral(String source) {
        return !headerTokens(source).isEmpty();
    }

    /**
     * 剝除註解後，找出原始碼中所有 client IP header token（小寫）。
     *
     * @param source Java 原始碼
     * @return token 集合
     */
    private static Set<String> headerTokens(String source) {
        String withoutBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        String code = LINE_COMMENT.matcher(withoutBlock).replaceAll(" ");
        Set<String> tokens = new LinkedHashSet<>();
        Matcher matcher = IP_HEADER_TOKEN.matcher(code);
        while (matcher.find()) {
            tokens.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        if (FORWARDED_STRING_LITERAL.matcher(code).find()) {
            tokens.add("forwarded");
        }
        return tokens;
    }

    /**
     * 讀檔。
     *
     * @param path 檔案路徑
     * @return 檔案內容
     */
    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 寫入測試用檔案（自動建立父目錄）。
     *
     * @param root     根目錄
     * @param relative 相對路徑
     * @param content  內容
     * @throws IOException 寫入失敗
     */
    private static void write(Path root, String relative, String content) throws IOException {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /**
     * repo 根目錄（surefire 的工作目錄為 {@code blog-start}，其上一層即根目錄）。
     *
     * @return repo 根目錄
     */
    private static Path repositoryRoot() {
        Path parent = Path.of("").toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalStateException("無法由工作目錄推得 repo 根目錄");
        }
        return parent;
    }

    /**
     * 取最外層類別全名（內部類別、lambda 歸屬於外層）。
     *
     * @param javaClass 類別
     * @return 最外層類別全名
     */
    private static String topLevelName(JavaClass javaClass) {
        String name = javaClass.getName();
        int dollar = name.indexOf('$');
        return dollar < 0 ? name : name.substring(0, dollar);
    }
}
