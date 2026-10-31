package dowob.xyz.blog.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模組 pom 依賴守衛（設計 {@code 2026-10-24-cross-module-uuid-design.md} §6 全域守衛、實作計畫 Task Z2 ⑤）。
 *
 * <p>每個 {@code blog-module-*&#47;pom.xml} 不得以 compile（未寫 scope 即預設 compile）、runtime、provided
 * 等<b>會進入 main classpath</b> 的 scope 依賴其他 {@code blog-module-*}：模組之間只能經 facade 與共用事件契約
 * （皆在 blog-infrastructure）溝通。pom 層擋住之後，跨模組引用連編譯都過不了，
 * {@link CrossModuleImportTest} 提到的「編譯期常數內聯使 bytecode 掃描看不到 import」盲點自然消失。</p>
 *
 * <p><b>test scope 允許</b>（整合測試的 TestApplication 需要把相鄰模組載進 classpath），
 * 但會由 {@link #listTestScopedModuleDependencies()} 列出並輸出到測試 log，供審查時留意。</p>
 *
 * <p>掃描範圍：{@code project/dependencies} 與 {@code project/profiles/profile/dependencies}；
 * {@code dependencyManagement}（只宣告版本、不產生依賴）與 plugin 內的 dependency 不在此限。</p>
 *
 * <p><b>為何附反向驗證與防空轉</b>：見 {@link EndpointAuthorizationTest}。判定邏輯以「repo 根目錄」為參數，
 * 正式碼用真實 repo 根，反向驗證用 {@code archunit-fixture/modulepom} 下的假 pom 樹；
 * 另斷言掃到的模組 pom 數等於根 pom {@code <modules>} 中 {@code blog-module-*} 的數量（也等於磁碟上的目錄數），
 * 新增模組卻沒被掃到時守衛會先紅，而不是靜默失效。</p>
 *
 * @author Yuan
 * @version 1.0
 */
class ModulePomDependencyTest {

    /** 業務模組的 artifactId／目錄名稱前綴 */
    private static final String MODULE_PREFIX = "blog-module-";

    /** 唯一允許的 scope：不進入 main classpath */
    private static final String TEST_SCOPE = "test";

    /** Maven 未寫 scope 時的預設值 */
    private static final String DEFAULT_SCOPE = "compile";

    /** 反向驗證 fixture 的根目錄（classpath 資源） */
    private static final String FIXTURE_ROOT_RESOURCE = "/archunit-fixture/modulepom";

    /**
     * 一筆模組間依賴。
     *
     * @param module     宣告依賴的模組（artifactId）
     * @param dependency 被依賴的模組（artifactId）
     * @param scope      生效的 scope（未寫則為 compile）
     */
    private record ModuleDependency(String module, String dependency, String scope) {

        String describe() {
            return module + " -> " + dependency + " (" + scope + ")";
        }
    }

    @Test
    @DisplayName("業務模組不得以 compile／runtime／provided scope 依賴其他業務模組")
    void moduleMustNotDependOnOtherModuleOutsideTestScope() {
        assertThat(findViolations(repositoryRoot()))
                .as("模組間只能經 facade 與共用事件契約（blog-infrastructure）溝通，不得在 pom 以 main classpath 依賴其他業務模組。"
                  + "需要相鄰模組的整合測試請改為 test scope。")
                .isEmpty();
    }

    @Test
    @DisplayName("列出 test scope 的模組間依賴（輸出到 log 供審查）")
    void listTestScopedModuleDependencies() {
        List<String> testScoped = scanDependencies(repositoryRoot()).stream()
                .filter(d -> TEST_SCOPE.equals(d.scope()))
                .map(ModuleDependency::describe)
                .sorted()
                .toList();

        System.out.println("[ModulePomDependencyTest] test-scoped module dependencies (" + testScoped.size() + " entries):");
        testScoped.forEach(line -> System.out.println("[ModulePomDependencyTest]   " + line));

        assertThat(testScoped)
                .as("test scope 依賴被允許，但每一筆都應是整合測試載入相鄰模組所需；請在審查時逐筆確認")
                .allSatisfy(line -> assertThat(line).endsWith("(test)"));
    }

    @Test
    @DisplayName("防假陰性：掃到的模組 pom 數必須等於根 pom <modules> 中 blog-module-* 的數量")
    void productionScanMustCoverEveryModuleDeclaredInRootPom() {
        Path root = repositoryRoot();
        List<String> declared = declaredModules(root);
        List<String> onDisk = moduleDirectoriesOnDisk(root);

        assertThat(declared)
                .as("根 pom 至少要宣告一個業務模組，否則守衛空轉")
                .isNotEmpty();
        assertThat(scannedModulePoms(root))
                .as("掃到的模組 pom 數必須 == 根 pom <modules> 的 blog-module-* 數量")
                .hasSameSizeAs(declared)
                .containsExactlyInAnyOrderElementsOf(declared);
        assertThat(onDisk)
                .as("磁碟上的 blog-module-* 目錄必須都登記在根 pom（否則該模組根本不在 reactor，也不會被建置守衛）")
                .containsExactlyInAnyOrderElementsOf(declared);
    }

    @Test
    @DisplayName("防假陰性：正式碼掃描必須實際解析到依賴（含非模組依賴），否則 XPath 失效時守衛恆綠")
    void productionScanMustActuallyParseDependencies() {
        Path root = repositoryRoot();
        long totalDependencyElements = declaredModules(root).stream()
                .mapToLong(module -> countDependencyElements(root.resolve(module).resolve("pom.xml")))
                .sum();

        assertThat(totalDependencyElements)
                .as("各模組 pom 的 project/dependencies/dependency 總數；為 0 代表解析方式失效")
                .isGreaterThan(0);
        assertThat(scanDependencies(root))
                .as("正式碼目前確實存在 test scope 的模組間依賴（如 comment 的 TestApplication 載入 reading）；"
                  + "解析到 0 筆代表 scope／artifactId 判讀失效")
                .isNotEmpty();
    }

    @Test
    @DisplayName("反向驗證：抓得到 compile／未寫 scope／runtime／provided／profile 內依賴，不誤判 test scope 與 dependencyManagement")
    void guardDetectsNonTestScopeModuleDependencies() {
        Path fixture = fixtureRoot();

        assertThat(findViolations(fixture))
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義")
                .containsExactlyInAnyOrder(
                        "blog-module-alpha -> blog-module-beta (compile)",
                        "blog-module-alpha -> blog-module-gamma (runtime)",
                        "blog-module-alpha -> blog-module-gamma (compile)",
                        "blog-module-alpha -> blog-module-delta (provided)");
    }

    @Test
    @DisplayName("反向驗證：fixture 的 test scope 依賴被列出，且掃到的 pom 數等於 fixture 根 pom 宣告數")
    void fixtureScanListsTestScopedAndCoversAllModules() {
        Path fixture = fixtureRoot();

        assertThat(scanDependencies(fixture).stream()
                .filter(d -> TEST_SCOPE.equals(d.scope()))
                .map(ModuleDependency::describe))
                .containsExactlyInAnyOrder(
                        "blog-module-alpha -> blog-module-epsilon (test)",
                        "blog-module-beta -> blog-module-alpha (test)");
        assertThat(scannedModulePoms(fixture))
                .hasSameSizeAs(declaredModules(fixture))
                .hasSize(5);
    }

    // ─── 判定邏輯 ───

    /**
     * 找出所有「不在 test scope 的模組間依賴」。
     *
     * @param root repo（或 fixture）根目錄
     * @return 違規描述 {@code 模組 -> 被依賴模組 (scope)}，已排序
     */
    private static List<String> findViolations(Path root) {
        return scanDependencies(root).stream()
                .filter(d -> !TEST_SCOPE.equals(d.scope()))
                .map(ModuleDependency::describe)
                .sorted()
                .toList();
    }

    /**
     * 掃描根 pom 宣告的所有業務模組，取出其對其他業務模組的依賴（任何 scope）。
     *
     * @param root repo（或 fixture）根目錄
     * @return 模組間依賴
     */
    private static List<ModuleDependency> scanDependencies(Path root) {
        List<ModuleDependency> result = new ArrayList<>();
        for (String module : declaredModules(root)) {
            Document pom = parse(root.resolve(module).resolve("pom.xml"));
            NodeList dependencies = evaluate(pom,
                    "/project/dependencies/dependency | /project/profiles/profile/dependencies/dependency");
            for (int i = 0; i < dependencies.getLength(); i++) {
                Node dependency = dependencies.item(i);
                String artifactId = text(dependency, "artifactId");
                if (artifactId != null && artifactId.startsWith(MODULE_PREFIX)) {
                    String scope = text(dependency, "scope");
                    result.add(new ModuleDependency(module, artifactId, scope == null ? DEFAULT_SCOPE : scope));
                }
            }
        }
        return result;
    }

    /** 根 pom {@code <modules>} 中宣告的 {@code blog-module-*}（排序）。 */
    private static List<String> declaredModules(Path root) {
        NodeList modules = evaluate(parse(root.resolve("pom.xml")), "/project/modules/module");
        List<String> result = new ArrayList<>();
        for (int i = 0; i < modules.getLength(); i++) {
            String name = modules.item(i).getTextContent().trim();
            if (name.startsWith(MODULE_PREFIX)) {
                result.add(name);
            }
        }
        return result.stream().sorted().toList();
    }

    /** 實際被解析到 pom 的模組名（目錄下 pom.xml 存在且可解析者）。 */
    private static List<String> scannedModulePoms(Path root) {
        return declaredModules(root).stream()
                .filter(module -> parse(root.resolve(module).resolve("pom.xml")) != null)
                .sorted()
                .toList();
    }

    private static List<String> moduleDirectoriesOnDisk(Path root) {
        try (Stream<Path> children = Files.list(root)) {
            return children
                    .filter(Files::isDirectory)
                    .filter(p -> p.getFileName().toString().startsWith(MODULE_PREFIX))
                    .filter(p -> Files.exists(p.resolve("pom.xml")))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("無法列出 " + root, e);
        }
    }

    private static long countDependencyElements(Path pomFile) {
        return evaluate(parse(pomFile), "/project/dependencies/dependency").getLength();
    }

    // ─── XML / 路徑輔助 ───

    private static Document parse(Path pomFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return factory.newDocumentBuilder().parse(pomFile.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("無法解析 " + pomFile, e);
        } catch (IOException e) {
            throw new UncheckedIOException("無法讀取 " + pomFile, e);
        }
    }

    private static NodeList evaluate(Document document, String expression) {
        try {
            XPath xpath = XPathFactory.newInstance().newXPath();
            return (NodeList) xpath.evaluate(expression, document, XPathConstants.NODESET);
        } catch (XPathExpressionException e) {
            throw new IllegalStateException("XPath 錯誤: " + expression, e);
        }
    }

    /** 取子元素的文字；不存在則回傳 {@code null}。 */
    private static String text(Node parent, String childName) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE && childName.equals(child.getNodeName())) {
                return child.getTextContent().trim();
            }
        }
        return null;
    }

    /** repo 根目錄：surefire 的工作目錄是 blog-start，上一層即根（與 CrossModuleImportTest 相同假設）。 */
    private static Path repositoryRoot() {
        Path parent = Path.of("").toAbsolutePath().getParent();
        if (parent == null) {
            throw new IllegalStateException("無法由工作目錄推得 repo 根目錄");
        }
        return parent;
    }

    private static Path fixtureRoot() {
        URL url = ModulePomDependencyTest.class.getResource(FIXTURE_ROOT_RESOURCE);
        if (url == null) {
            throw new IllegalStateException("找不到 fixture 資源 " + FIXTURE_ROOT_RESOURCE);
        }
        try {
            return Path.of(url.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
