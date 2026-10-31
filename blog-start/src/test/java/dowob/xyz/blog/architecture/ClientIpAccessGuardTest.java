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
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client IP 取得入口守衛（findings SEC-01 第 3 層）。
 *
 * <p><b>規則</b>：除 {@link #ALLOWED ClientIpResolver} 外，{@code src/main} 不得自行取得 client IP：</p>
 * <ol>
 *   <li>(a) 不得呼叫 {@code ServletRequest#getRemoteAddr()}／{@code getRemoteHost()}
 *       （含 {@code HttpServletRequest} 等子型別、lambda 與方法參考）。以 ArchUnit 分析 bytecode；</li>
 *   <li>(b) 不得出現 client IP 相關 header 名稱的字面值（{@code X-Forwarded-For}、{@code X-Real-IP}、
 *       {@code CF-Connecting-IP}、{@code True-Client-IP}，不分大小寫）。ArchUnit 看不到字串常數，
 *       故掃描各模組 {@code src/main/java} 的原始碼（先剝除註解，避免文件敘述誤報）。</li>
 * </ol>
 *
 * <p><b>為何需要守衛</b>：SEC-01 的根因是「橫切關注點被複製進兩個 Controller 的 private method，
 * 兩份對可信 IP 的假設互相矛盾」。信任鏈只能有一個定義處（{@code app.http.trusted-proxy-count}），
 * 新增的 controller 若自行讀 XFF／remoteAddr，就會悄悄重開繞過。</p>
 *
 * <p><b>防假陰性</b>：兩條規則各有反向驗證——fixture 的故意違規必須被抓到、合規與白名單必須放行；
 * 且不套白名單時，正式碼必須掃到 {@code ClientIpResolver} 本身（證明掃描沒有空轉）。</p>
 *
 * <p>已知範圍：只看 servlet 的 {@code getRemoteAddr()}／{@code getRemoteHost()} 與上述 header 字面值；
 * 以字串拼接、反射或常數表間接組出 header 名稱不在範圍（code review 負責）。</p>
 *
 * @author Yuan
 * @version 1.0
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

    /** 規則 (a) 要擋的 servlet 方法名稱 */
    private static final Set<String> FORBIDDEN_METHODS = Set.of("getRemoteAddr", "getRemoteHost");

    /** {@code ServletRequest} 的全限定名（所有 request 型別的共同父介面） */
    private static final String SERVLET_REQUEST = "jakarta.servlet.ServletRequest";

    /** 規則 (b)：client IP 相關 header 名稱 */
    private static final Pattern IP_HEADER_LITERAL = Pattern.compile(
            "x-forwarded-for|x-real-ip|cf-connecting-ip|true-client-ip", Pattern.CASE_INSENSITIVE);

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

        assertThat(findHeaderLiteralFiles(root, ALLOWED_SOURCE_FILE))
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
        assertThat(findHeaderLiteralFiles(repositoryRoot(), "<none>"))
                .as("ClientIpResolver.java 必然含 X-Forwarded-For 字面值；掃不到代表掃描路徑或剝註解壞了（守衛空轉）")
                .anyMatch(path -> path.endsWith(ALLOWED_SOURCE_FILE));
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
        assertThat(containsHeaderLiteral("String h = request.getHeader(\"X-Forwarded-Proto\");")).isFalse();
    }

    @Test
    @DisplayName("反向驗證 (b)：目錄掃描抓得到違規檔、放行白名單檔名與非 main 路徑")
    void directoryScanFlagsViolatorsAndHonorsAllowList(@TempDir Path tmp) throws IOException {
        write(tmp, "blog-module-x/src/main/java/a/Bad.java", "class Bad { String s = \"X-Forwarded-For\"; }");
        write(tmp, "blog-module-x/src/main/java/a/Good.java", "class Good { /* X-Forwarded-For */ }");
        write(tmp, "blog-module-y/src/main/java/b/ClientIpResolver.java", "class R { String s = \"X-Forwarded-For\"; }");
        write(tmp, "blog-module-y/src/test/java/b/SomeTest.java", "class T { String s = \"X-Forwarded-For\"; }");

        List<String> flagged = findHeaderLiteralFiles(tmp, ALLOWED_SOURCE_FILE);

        assertThat(flagged).hasSize(1).allMatch(p -> p.endsWith("Bad.java"));
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
     * 是否為 servlet request 的 {@code getRemoteAddr}／{@code getRemoteHost} 存取（含子型別與方法參考）。
     *
     * @param access 程式碼單元存取
     * @return 是則 true
     */
    private static boolean isRemoteAddrAccess(JavaAccess<?> access) {
        return FORBIDDEN_METHODS.contains(access.getName())
                && access.getTargetOwner().isAssignableTo(SERVLET_REQUEST);
    }

    /**
     * 規則 (b)：掃描各模組 {@code src/main/java}，回傳白名單檔名以外、程式碼（非註解）含 client IP header 字面值的檔案。
     *
     * @param root            repo 根目錄
     * @param allowedFileName 允許的檔名（例如 {@code ClientIpResolver.java}）
     * @return 違規檔案的絕對路徑（排序）
     */
    private static List<String> findHeaderLiteralFiles(Path root, String allowedFileName) {
        try (Stream<Path> files = Files.walk(root)) {
            return files
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> p.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/target/"))
                    .filter(p -> !p.getFileName().toString().equals(allowedFileName))
                    .filter(p -> containsHeaderLiteral(read(p)))
                    .map(Path::toString)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 剝除註解後，原始碼是否含 client IP header 名稱。
     *
     * @param source Java 原始碼
     * @return 是則 true
     */
    private static boolean containsHeaderLiteral(String source) {
        String withoutBlock = BLOCK_COMMENT.matcher(source).replaceAll(" ");
        String code = LINE_COMMENT.matcher(withoutBlock).replaceAll(" ");
        return IP_HEADER_LITERAL.matcher(code).find();
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
