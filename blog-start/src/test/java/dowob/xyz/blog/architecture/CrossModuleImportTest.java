package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 跨模組 import 守衛（設計 {@code 2026-10-24-cross-module-uuid-design.md} §3.1「跨模組 import 守衛（Q11）」）。
 *
 * <p>{@code dowob.xyz.blog.module.<A>..} 不得依賴 {@code dowob.xyz.blog.module.<B>..}（A≠B）：
 * 模組之間只能透過 facade 與共用事件契約（皆位於 blog-infrastructure）溝通。
 * 「依賴」涵蓋 ArchUnit 的所有直接依賴種類（import 的型別、欄位、方法簽章與呼叫、繼承、標註、泛型參數等）。</p>
 *
 * <p><b>兩個來源取聯集</b>：bytecode 掃描（ArchUnit）與 source 掃描。只看 bytecode 有盲點——
 * 編譯期常數（{@code static final String} 等）會被內聯進呼叫端，{@code import} 在 bytecode 裡不留痕跡
 * （例如 {@code import ...ArticleRabbitMqConfig} 只為了取 queue 名稱常數）。因此另外掃描
 * {@code blog-module-*&#47;src/main/java} 的原始碼（先去除註解，再比對
 * {@code dowob.xyz.blog.module.<B>.} 的完整類名；萬用字元 import 一律視為違規），
 * 以及 {@code src/main/resources} 下 XML 的 {@code resultType}／{@code type} 等屬性。
 * 例外清單與防死條目都對「聯集」比對。</p>
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

    /** 業務模組目錄的名稱前綴 */
    private static final String MODULE_DIR_PREFIX = "blog-module-";

    /** package 宣告 */
    private static final Pattern PACKAGE_DECL = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");

    /** 完整類名（或套件名）：{@code dowob.xyz.blog.module.<B>.<其餘>}；其餘可含 {@code .} 與 {@code $} */
    private static final Pattern MODULE_FQN = Pattern.compile("dowob\\.xyz\\.blog\\.module\\.(\\w+)\\.([\\w.$]*)");

    /** 指向他模組套件的萬用字元 import（group 1 為含 {@code .*} 的整串，group 2 為目標模組） */
    private static final Pattern WILDCARD_IMPORT = Pattern.compile(
            "import\\s+(?:static\\s+)?(dowob\\.xyz\\.blog\\.module\\.(\\w+)\\.(?:[\\w.]*\\.)?\\*)\\s*;");

    /** XML 註解 */
    private static final Pattern XML_COMMENT = Pattern.compile("(?s)<!--.*?-->");

    /** MyBatis XML 的 namespace（即 mapper 介面全名） */
    private static final Pattern XML_NAMESPACE = Pattern.compile("namespace\\s*=\\s*\"([^\"]+)\"");

    /** MyBatis XML 中會放類名的屬性 */
    private static final Pattern XML_TYPE_ATTRIBUTE = Pattern.compile(
            "\\b(resultType|type|parameterType|ofType|javaType)\\s*=\\s*\"([^\"]*)\"");

    /** 預期的業務模組目錄（{@code blog-module-*} 去掉前綴）；新增模組時須同步更新此處 */
    private static final List<String> EXPECTED_MODULES = List.of(
            "article", "comment", "file", "reading", "recommend", "search", "series", "tag", "user", "version");

    /** 快取的 bytecode 匯入結果（匯入約數秒，各測試共用） */
    private static JavaClasses productionClasses;

    /** key 與 value 之間的箭頭 */
    private static final String ARROW = " -> ";

    /**
     * 現存跨模組依賴的例外清單：{@code 來源類別 -> 目標類別} → 「階段：說明」。
     */
    private static final Map<String, String> EXEMPT = Map.ofEntries(
            // 預計 M2（reading 階段）：改走 ArticleFacade.getSummariesByUuids，回應 JSON 不變
            // （ArticleSummaryResponse 已於 M2-a 搬至 blog-infrastructure，對它的條目隨之移除）
            Map.entry("dowob.xyz.blog.module.reading.service.BookmarkQueryService"
                    + ARROW + "dowob.xyz.blog.module.article.service.ArticleQueryService",
                    "M2：reading 階段改走 ArticleFacade"),
            // 預計 M4（series 階段）：移除對 ArticleQueryService 的直接依賴，改走 ArticleFacade
            // （ArticleSummaryResponse 已於 M2-a 搬至 blog-infrastructure，對它的條目隨之移除）
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
    @DisplayName("模組不得依賴他模組的類別（bytecode ∪ source 聯集；例外清單以外不得有）")
    void moduleMustNotDependOnAnotherModule() {
        List<String> violations = findAllCrossModuleDependencies().stream()
                .filter(v -> !EXEMPT.containsKey(v))
                .toList();

        assertThat(violations)
                .as("模組之間只能透過 facade 與共用事件契約（blog-infrastructure）溝通，不得直接 import 他模組的類別。"
                  + "若為尚未改造的模組，加入 EXEMPT（key 為 來源類別 -> 目標類別）並標明清除階段。")
                .isEmpty();
    }

    @Test
    @DisplayName("防死條目：例外清單的每一項都必須仍是實際的跨模組依賴（對 bytecode ∪ source 聯集比對）")
    void exemptListMustNotContainStaleEntries() {
        List<String> actual = findAllCrossModuleDependencies();

        assertThat(EXEMPT.keySet())
                .as("條目對應的依賴若已被移除（改走 facade／共用事件）或類別已刪除，就成了死條目，"
                  + "會靜靜掩蓋日後同一組來源→目標的真違規，請移除。")
                .allSatisfy(exempt -> assertThat(actual).contains(exempt));
    }

    @Test
    @DisplayName("例外清單每個條目都必須標明預計清除的階段（M1～M8 或 Z）")
    void everyExemptEntryMustDeclareClearingStage() {
        assertThat(EXEMPT.values()).allSatisfy(v -> assertThat(v).matches(STAGE_PREFIX));
    }

    @Test
    @DisplayName("防假陰性：bytecode 掃描必須實際掃到多個模組，否則守衛是空轉的")
    void productionScanMustActuallyFindModules() {
        List<String> modules = importProduction().stream()
                .map(c -> moduleOf(c.getPackageName(), MODULE_ROOT))
                .filter(m -> m != null)
                .distinct()
                .toList();

        assertThat(modules)
                .as("若掃不到模組類別，「無違規」就毫無意義；每個業務模組都應被掃到")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_MODULES);
    }

    @Test
    @DisplayName("防假陰性：source 掃描必須找到 10 個 blog-module-* 目錄，找不到要明確失敗")
    void sourceScanMustActuallyFindModuleDirectories() {
        assertThat(listModuleNames(repositoryRoot()))
                .as("source 掃描以 surefire cwd（blog-start）的上一層為根；找不到模組目錄代表路徑假設破了，"
                  + "此時 source 掃描會空轉成恆綠，必須先在這裡紅")
                .hasSize(10)
                .containsExactlyInAnyOrderElementsOf(EXPECTED_MODULES);
    }

    @Test
    @DisplayName("防假陰性：每個業務模組的 src/main/java 都必須實際讀到原始檔")
    void sourceScanMustActuallyReadJavaFilesOfEveryModule() {
        Path root = repositoryRoot();

        assertThat(EXPECTED_MODULES).allSatisfy(module -> assertThat(
                listFiles(root.resolve(MODULE_DIR_PREFIX + module).resolve("src/main/java"), ".java"))
                .as("模組 " + module + " 的原始檔").isNotEmpty());
    }

    @Test
    @DisplayName("tripwire：bytecode 掃描不得掃進 architecture.fixture")
    void productionScanMustNotContainFixtures() {
        assertThat(importProduction().stream().map(JavaClass::getPackageName))
                .as("正式碼匯入若包含測試 source 的 fixture，故意違規的 fixture 會污染守衛結果")
                .noneMatch(p -> p.contains(".architecture.fixture"));
    }

    @Test
    @DisplayName("反向驗證（bytecode）：守衛抓得到跨模組依賴，且不誤判同模組依賴")
    void guardDetectsCrossModuleDependency() {
        List<String> violations = findCrossModuleDependencies(
                new ClassFileImporter().importPackages(FIXTURE_MODULE_ROOT), FIXTURE_MODULE_ROOT);

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .containsExactly(FIXTURE_MODULE_ROOT + ".beta.BetaViolator" + ARROW
                        + FIXTURE_MODULE_ROOT + ".alpha.AlphaTarget");
    }

    @Test
    @DisplayName("反向驗證（source）：抓得到只存在於 source 的依賴，忽略註解與同模組，萬用字元視為違規")
    void sourceScanDetectsInlinedAndWildcardDependencies(@TempDir Path root) throws IOException {
        writeSource(root, "alpha", "service/AlphaService.java", """
                package dowob.xyz.blog.module.alpha.service;

                import dowob.xyz.blog.module.beta.service.BetaService;
                import dowob.xyz.blog.module.beta.model.Outer.Inner;
                import static dowob.xyz.blog.module.gamma.util.Helper.doIt;
                import dowob.xyz.blog.module.delta.api.*;
                import dowob.xyz.blog.module.alpha.model.Own;
                // import dowob.xyz.blog.module.zeta.LineComment;
                /* dowob.xyz.blog.module.zeta.BlockComment */
                /** {@link dowob.xyz.blog.module.zeta.JavadocLink} */
                public class AlphaService {
                    private static final String Q = "http://example.com/a"; // dowob.xyz.blog.module.zeta.TrailingComment
                    private static final char QUOTE = '"'; // dowob.xyz.blog.module.zeta.AfterCharLiteral
                    private dowob.xyz.blog.module.epsilon.model.Fqn fqn;
                    private final Class<?> reflective = load("dowob.xyz.blog.module.eta.StringFqn");
                }
                """);
        writeResource(root, "alpha", "mapper/AlphaMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="dowob.xyz.blog.module.alpha.mapper.AlphaMapper">
                    <!-- resultType="dowob.xyz.blog.module.zeta.XmlComment" -->
                    <select id="a" resultType="dowob.xyz.blog.module.theta.model.XmlRow">select 1</select>
                    <resultMap id="m" type="dowob.xyz.blog.module.alpha.model.OwnRow"/>
                </mapper>
                """);
        String src = "dowob.xyz.blog.module.alpha.service.AlphaService" + ARROW + "dowob.xyz.blog.module.";

        assertThat(findSourceDependencies(root))
                .as("source 掃描要抓到 import、巢狀類別歸併、static import、萬用字元、完整類名、字串類名與 XML 屬性，"
                  + "並忽略行註解、區塊註解、Javadoc 與同模組依賴")
                .containsExactlyInAnyOrder(
                        src + "beta.service.BetaService",
                        src + "beta.model.Outer",
                        src + "gamma.util.Helper",
                        src + "delta.api.*",
                        src + "epsilon.model.Fqn",
                        src + "eta.StringFqn",
                        "dowob.xyz.blog.module.alpha.mapper.AlphaMapper" + ARROW
                                + "dowob.xyz.blog.module.theta.model.XmlRow");
    }

    @Test
    @DisplayName("反向驗證（source）：找不到任何 blog-module-* 目錄時明確失敗，不得空轉")
    void sourceScanFailsLoudlyWhenNoModuleDirectories(@TempDir Path emptyRoot) {
        assertThatThrownBy(() -> findSourceDependencies(emptyRoot))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blog-module-");
    }

    /**
     * 匯入正式碼的業務模組（不含測試 source），結果快取供各測試共用。
     *
     * @return 待掃描的類集合
     */
    private static synchronized JavaClasses importProduction() {
        if (productionClasses == null) {
            productionClasses = new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(MODULE_ROOT);
        }
        return productionClasses;
    }

    /**
     * 取得實際的跨模組依賴：bytecode 掃描與 source 掃描的聯集。
     *
     * @return {@code 來源類別 -> 目標類別}（排序、去重）
     */
    private static List<String> findAllCrossModuleDependencies() {
        TreeSet<String> union = new TreeSet<>(findCrossModuleDependencies(importProduction(), MODULE_ROOT));
        union.addAll(findSourceDependencies(repositoryRoot()));
        return List.copyOf(union);
    }

    /**
     * 取得 repo 根目錄：surefire 的工作目錄是 {@code blog-start}，其上一層即 repo 根。
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
     * 列出 repo 根目錄下的業務模組名（{@code blog-module-*} 去掉前綴）。
     *
     * @param root repo 根目錄
     * @return 模組名（排序）
     */
    private static List<String> listModuleNames(Path root) {
        try (Stream<Path> children = Files.list(root)) {
            return children
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(MODULE_DIR_PREFIX))
                    .map(n -> n.substring(MODULE_DIR_PREFIX.length()))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("無法列出模組目錄: " + root, e);
        }
    }

    /**
     * source 掃描：找出 {@code blog-module-*} 的 {@code src/main/java} 與 {@code src/main/resources} 中
     * 指向他模組類別的引用。
     *
     * @param root repo 根目錄
     * @return {@code 來源類別 -> 目標類別}（排序、去重）
     * @throws IllegalStateException 找不到任何 {@code blog-module-*} 目錄時
     */
    private static List<String> findSourceDependencies(Path root) {
        List<String> modules = listModuleNames(root);
        if (modules.isEmpty()) {
            throw new IllegalStateException("在 " + root + " 下找不到任何 " + MODULE_DIR_PREFIX
                    + "* 目錄：source 掃描的路徑假設（surefire cwd 為 blog-start）已失效，守衛會空轉");
        }
        TreeSet<String> result = new TreeSet<>();
        for (String module : modules) {
            Path moduleDir = root.resolve(MODULE_DIR_PREFIX + module);
            for (Path file : listFiles(moduleDir.resolve("src/main/java"), ".java")) {
                collectJavaSourceDependencies(module, stripJavaComments(read(file)), file, result);
            }
            for (Path file : listFiles(moduleDir.resolve("src/main/resources"), ".xml")) {
                collectXmlDependencies(module, stripXmlComments(read(file)), file, result);
            }
        }
        return List.copyOf(result);
    }

    /**
     * 從一份已去註解的 Java 原始碼收集跨模組引用。
     *
     * @param module 來源檔所屬模組（目錄名）
     * @param code   已去除註解的原始碼
     * @param file   原始檔路徑（用於推得沒有 package 宣告時的來源名稱）
     * @param result 收集結果
     */
    private static void collectJavaSourceDependencies(String module, String code, Path file, TreeSet<String> result) {
        Matcher pkg = PACKAGE_DECL.matcher(code);
        String simple = file.getFileName().toString().replaceFirst("\\.java$", "");
        String source = (pkg.find() ? pkg.group(1) + "." : "") + simple;
        Matcher wildcard = WILDCARD_IMPORT.matcher(code);
        while (wildcard.find()) {
            if (!wildcard.group(2).equals(module)) {
                result.add(source + ARROW + wildcard.group(1));
            }
        }
        collectFqnReferences(module, source, code, result);
    }

    /**
     * 從一份已去註解的 XML 收集跨模組引用（{@code resultType}、{@code type} 等屬性值）。
     *
     * @param module 來源檔所屬模組（目錄名）
     * @param xml    已去除註解的 XML
     * @param file   XML 路徑
     * @param result 收集結果
     */
    private static void collectXmlDependencies(String module, String xml, Path file, TreeSet<String> result) {
        Matcher ns = XML_NAMESPACE.matcher(xml);
        String source = ns.find() ? ns.group(1) : file.getFileName().toString();
        Matcher attr = XML_TYPE_ATTRIBUTE.matcher(xml);
        while (attr.find()) {
            collectFqnReferences(module, source, attr.group(2), result);
        }
    }

    /**
     * 在文字中找出所有 {@code dowob.xyz.blog.module.<B>.} 開頭的完整類名，B 不等於來源模組者加入結果。
     *
     * <p>目標類別取第一個大寫開頭的 segment，內部類別與 static import 的成員因此歸併到最外層類別；
     * 找不到大寫 segment（純套件字串）者略過，萬用字元 import 由呼叫端另外處理。</p>
     *
     * @param module 來源模組
     * @param source 來源類別全名
     * @param text   待掃描的文字
     * @param result 收集結果
     */
    private static void collectFqnReferences(String module, String source, String text, TreeSet<String> result) {
        Matcher m = MODULE_FQN.matcher(text);
        while (m.find()) {
            String targetModule = m.group(1);
            if (targetModule.equals(module)) {
                continue;
            }
            StringBuilder target = new StringBuilder(MODULE_ROOT).append('.').append(targetModule);
            boolean found = false;
            for (String segment : m.group(2).split("[.$]")) {
                if (segment.isEmpty()) {
                    continue;
                }
                target.append('.').append(segment);
                if (Character.isUpperCase(segment.charAt(0))) {
                    found = true;
                    break;
                }
            }
            if (found) {
                result.add(source + ARROW + target);
            }
        }
    }

    /**
     * 列出目錄下指定副檔名的所有檔案；目錄不存在回傳空清單。
     *
     * @param dir    目錄
     * @param suffix 副檔名（含點）
     * @return 檔案路徑（排序）
     */
    private static List<Path> listFiles(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(suffix))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("無法掃描目錄: " + dir, e);
        }
    }

    /**
     * 以 UTF-8 讀取檔案。
     *
     * @param file 檔案
     * @return 檔案內容
     */
    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("無法讀取 " + file, e);
        }
    }

    /**
     * 去除 Java 原始碼中的行註解與區塊註解（含 Javadoc），字串、字元與 text block 內的內容原樣保留。
     *
     * @param code 原始碼
     * @return 去註解後的原始碼
     */
    static String stripJavaComments(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        int n = code.length();
        while (i < n) {
            if (code.startsWith("\"\"\"", i)) {
                int end = findStringEnd(code, i + 3, "\"\"\"");
                out.append(code, i, end);
                i = end;
            } else if (code.charAt(i) == '"') {
                int end = findStringEnd(code, i + 1, "\"");
                out.append(code, i, end);
                i = end;
            } else if (code.charAt(i) == '\'') {
                int end = findStringEnd(code, i + 1, "'");
                out.append(code, i, end);
                i = end;
            } else if (code.startsWith("//", i)) {
                while (i < n && code.charAt(i) != '\n') {
                    i++;
                }
            } else if (code.startsWith("/*", i)) {
                int end = code.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else {
                out.append(code.charAt(i++));
            }
        }
        return out.toString();
    }

    /**
     * 找出字串／字元／text block 的結尾位置（含結尾分隔符之後）。
     *
     * @param code      原始碼
     * @param from      開頭分隔符之後的位置
     * @param delimiter 結尾分隔符
     * @return 結尾分隔符之後的位置；找不到回傳原始碼長度
     */
    private static int findStringEnd(String code, int from, String delimiter) {
        int i = from;
        while (i < code.length()) {
            if (code.charAt(i) == '\\') {
                i += 2;
            } else if (code.startsWith(delimiter, i)) {
                return i + delimiter.length();
            } else {
                i++;
            }
        }
        return code.length();
    }

    /**
     * 去除 XML 註解。
     *
     * @param xml XML 內容
     * @return 去註解後的內容
     */
    private static String stripXmlComments(String xml) {
        return XML_COMMENT.matcher(xml).replaceAll(" ");
    }

    /**
     * 在測試用的假模組目錄下寫入一個 Java 原始檔。
     *
     * @param root     假 repo 根目錄
     * @param module   模組名
     * @param relative 相對於該模組套件目錄的路徑
     * @param content  檔案內容
     * @throws IOException 寫檔失敗時
     */
    private static void writeSource(Path root, String module, String relative, String content) throws IOException {
        Path file = root.resolve("blog-module-" + module).resolve("src/main/java/dowob/xyz/blog/module")
                .resolve(module).resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    /**
     * 在測試用的假模組目錄下寫入一個 resources 檔案。
     *
     * @param root     假 repo 根目錄
     * @param module   模組名
     * @param relative 相對於 {@code src/main/resources} 的路徑
     * @param content  檔案內容
     * @throws IOException 寫檔失敗時
     */
    private static void writeResource(Path root, String module, String relative, String content) throws IOException {
        Path file = root.resolve("blog-module-" + module).resolve("src/main/resources").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content, StandardCharsets.UTF_8);
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
