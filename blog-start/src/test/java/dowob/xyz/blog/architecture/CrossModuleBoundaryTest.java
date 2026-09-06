package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模組邊界守衛（architecture.md §Cross-Module Boundary Rules）。
 *
 * <p>守衛 #5：模組的 mapper SQL 字面值不得出現<b>他模組的業務表</b>名。
 * reference data（{@code users} / {@code tags}）依規範可直接 JOIN，故不在此限——
 * 這條豁免是必要的，否則會誤判 PERF-01（reindex 改 JOIN users）為違規。</p>
 *
 * <p><b>annotation value 一律是陣列（重要更正）</b>：MyBatis 的 {@code @Select} /
 * {@code @Update} 宣告的是 {@code String[] value()}——陣列型 annotation member。
 * 依 JVMS §4.7.16.1，陣列型 annotation member 在 classfile 中<b>一律以陣列編碼</b>，
 * 與原始碼寫成單一字串 {@code @Select("...")} 或陣列字面值 {@code @Select({...})}
 * 這種來源語法無關——ArchUnit 讀到的 {@code value()} 因此永遠是 {@code String[]}，
 * 不存在「單一字串」這種另一型態。早期草稿誤以為 {@code @Select("...")} 是純量、
 * 只有 {@code @Select({...})} 才是陣列，因而只用
 * {@code String.valueOf(a.get("value").orElse(""))} 讀值——這個假設是錯的，
 * 後果也比原先認知的更嚴重：它讓本守衛對<b>本 repo 每一個</b>
 * {@code @Select}/{@code @Update}（不分寫法，不只是陣列字面值那 11 處）都完全失明，
 * 拿到的是 {@code [Ljava.lang.String;@1b6d3586} 這種物件位址字串，不含任何 SQL 內容。
 * 本守衛因此改為偵測 {@code Object[]} 並把陣列攤平、以空白 join 回單一字串再比對，
 * 空白是必要的：{@code "... WHERE id IN"} 與 {@code "<foreach ...>"}
 * 若直接串接（不留分隔）可能黏成不含空白的 token，讓 "from articles" 這類
 * 關鍵字比對失真。</p>
 *
 * <p>給未來寫類似守衛的人的通用教訓：<b>任何宣告為陣列型別的 annotation member，
 * 讀出來一律是陣列，與呼叫端語法（單一字串或陣列字面值）無關。</b></p>
 */
class CrossModuleBoundaryTest {

    /** 業務表 → 擁有它的模組套件片段。只有非 owner 模組碰到才算違規。 */
    private static final Map<String, String> BUSINESS_TABLE_OWNERS = Map.of(
            "articles", "module.article",
            "comments", "module.comment"
    );

    /**
     * 業務表 → 該表名的 SQL 比對 pattern（每個表各自一條，非硬編碼）。
     *
     * <p>要求 word boundary（{@code \b}）＋容許任意空白（含換行，SQL text block
     * 常見寫法如 {@code FROM\n    articles}）＋可選 {@code public.} schema 前綴。
     * {@code \b} 是防 false positive 的關鍵：{@code article_tags} /
     * {@code article_categories} / {@code article_versions} 這些以 {@code article}
     * 開頭但接底線的真實表名，因為 {@code articles} 字面值本身在其中根本不出現
     * （少了結尾的 {@code s} 緊接在 {@code e} 後面），加上 {@code \b} 雙重保險，
     * 兩者都不會被誤判為 {@code articles}。（詳見 CrossModuleBoundaryTest 對應
     * false-positive 驗證，task-7_5-report.md）</p>
     */
    private static final Map<String, Pattern> BUSINESS_TABLE_PATTERNS = BUSINESS_TABLE_OWNERS.keySet().stream()
            .collect(Collectors.toMap(
                    table -> table,
                    table -> Pattern.compile(
                            "(?is)\\b(from|join|update)\\s+(public\\.)?" + Pattern.quote(table) + "\\b")));

    /**
     * 模組擁有權 pattern：owner 套件片段（如 {@code module.article}）必須以
     * 完整 segment 出現在 mapper 的 package name 中，不可只是子字串前綴。
     *
     * <p>修這個是因為原本用 {@code packageName.contains("module.article")}，
     * 未來若有模組取名為 {@code ...module.articleWorkflow.mapper}，
     * {@code "module.articleWorkflow".contains("module.article")} 為 true，
     * 會被誤判為 articles 的 owner，靜默豁免——這正是要堵的假陰性。
     * 用 {@code (^|\.)owner(\.|$)} 要求 owner 片段前後都是 package 分隔點
     * 或字串邊界，{@code module.articleWorkflow} 就不會匹配
     * {@code module.article}。</p>
     */
    private static final Map<String, Pattern> OWNER_PACKAGE_PATTERNS = BUSINESS_TABLE_OWNERS.values().stream()
            .distinct()
            .collect(Collectors.toMap(
                    owner -> owner,
                    owner -> Pattern.compile("(^|\\.)" + Pattern.quote(owner) + "(\\.|$)")));

    @Test
    @DisplayName("mapper 不得在 SQL 中存取他模組的業務表")
    void mapperMustNotAccessForeignModuleBusinessTable() {
        JavaClasses classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dowob.xyz.blog.module");

        List<String> violations = classes.stream()
                .filter(c -> c.getPackageName().contains(".mapper"))
                .flatMap(c -> c.getMethods().stream()
                        .flatMap(m -> m.getAnnotations().stream()
                                .filter(a -> a.getRawType().getName().endsWith(".Select")
                                          || a.getRawType().getName().endsWith(".Update"))
                                .map(a -> flattenAnnotationValue(a.get("value").orElse("")))
                                .filter(sql -> hasForeignBusinessTable(sql, c.getPackageName()))
                                .map(sql -> c.getName() + "#" + m.getName())))
                .distinct()
                .toList();

        assertThat(violations)
                .as("mapper SQL 不得存取他模組業務表；reference data（users/tags）不在此限。"
                  + "若需跨模組集合查詢，改由 owner 模組提供 facade 集合述詞方法。")
                .isEmpty();
    }

    /**
     * 攤平 annotation value：MyBatis {@code @Select}/{@code @Update} 的
     * {@code value()} 宣告為陣列型別（{@code String[]}），依 JVMS §4.7.16.1，
     * ArchUnit 讀出來的 {@code value()} 永遠是 {@code Object[]}（見上方 class
     * javadoc「annotation value 一律是陣列」），不論原始碼寫成單一字串
     * {@code @Select("...")} 還是陣列字面值 {@code @Select({"...", ...})}。
     * 陣列以空白 join 避免片段黏成單一 token，讓多元素形式（MyBatis 動態 SQL
     * {@code <script>}/{@code <foreach>} 慣用寫法）也能正確比對。
     * {@code instanceof Object[]} 以外的 fallback 分支純屬防禦性寫法
     * （供非陣列型 annotation member 誤用本 helper 時不至於丟例外），
     * 在本守衛實際掃描的 {@code @Select}/{@code @Update} 場景下不會被觸發。
     */
    private static String flattenAnnotationValue(Object raw) {
        return (raw instanceof Object[] parts)
                ? Arrays.stream(parts).map(String::valueOf).collect(Collectors.joining(" "))
                : String.valueOf(raw);
    }

    private static boolean hasForeignBusinessTable(String sql, String packageName) {
        return BUSINESS_TABLE_OWNERS.entrySet().stream().anyMatch(e -> {
            boolean touches = BUSINESS_TABLE_PATTERNS.get(e.getKey()).matcher(sql).find();
            boolean isOwner = OWNER_PACKAGE_PATTERNS.get(e.getValue()).matcher(packageName).find();
            return touches && !isOwner;
        });
    }

    // ─── XML mapper 掃描（PR #71 review 補洞）───

    /** MyBatis XML mapper 的 namespace 宣告，其值即該 mapper 的所屬型別全名 */
    private static final Pattern XML_NAMESPACE = Pattern.compile("namespace\s*=\s*\"([^\"]+)\"");

    /** 正式碼 XML mapper 的 classpath 位置 */
    private static final String PRODUCTION_MAPPER_XML = "classpath*:mapper/*.xml";

    @Test
    @DisplayName("守衛 #5（XML）：XML mapper 不得在 SQL 中存取他模組的業務表")
    void xmlMapperMustNotAccessForeignModuleBusinessTable() {
        assertThat(findXmlViolations(PRODUCTION_MAPPER_XML))
                .as("XML mapper 與註解式 mapper 受同一條規則約束。"
                  + "本項存在的原因：註解式掃描讀的是 bytecode 的 @Select/@Update，"
                  + "對 XML 完全失明，使 XML 成為繞過本守衛的合法路徑（PR #71 review 指出）。")
                .isEmpty();
    }

    @Test
    @DisplayName("防假陰性：正式碼 XML mapper 掃描必須實際掃到檔案，否則本守衛是空轉的")
    void productionXmlScanMustActuallyFindResources() {
        List<String> scanned = listScannedMappers(PRODUCTION_MAPPER_XML);

        assertThat(scanned)
                .as("若 classpath 上掃不到任何 mapper XML，上面那條「無違規」的斷言就毫無意義——"
                  + "它會因為什麼都沒掃而恆綠。build 配置變動導致 XML 不進 classpath 時，"
                  + "本項會先失敗，而不是讓守衛靜默失效。"
                  + "目前已知至少有 ArticleRecommendMapper.xml。")
                .isNotEmpty()
                .anySatisfy(name -> assertThat(name).endsWith(".xml"));
    }

    @Test
    @DisplayName("反向驗證：XML 掃描抓得到違規，且不誤判 owner 自查與 reference data")
    void xmlScanDetectsViolationWithoutFalsePositive() {
        List<String> violations = findXmlViolations("classpath*:archunit-fixture/mapper/*.xml");

        assertThat(violations)
                .as("守衛若抓不到已知違規，正向掃描為空就沒有意義——"
                  + "這正是 29a1000 修過的靜默假陰性形態")
                .anySatisfy(v -> assertThat(v).contains("ViolatingSeriesMapper"));
        assertThat(violations)
                .as("article 模組的 XML 查自己的 articles、JOIN reference data（users/tags）皆合規")
                .noneMatch(v -> v.contains("CompliantArticleMapper"));
    }

    /**
     * 列出指定位置實際掃到的 XML mapper 檔名。
     *
     * <p>供防假陰性測試使用：{@link #findXmlViolations(String)} 回傳空清單有兩種可能——
     * 真的沒有違規，或根本沒掃到檔案。分開這兩者才能讓守衛的「綠」有意義。</p>
     *
     * @param locationPattern classpath 位置樣式
     * @return 掃到的檔名清單
     */
    private static List<String> listScannedMappers(String locationPattern) {
        try {
            return Arrays.stream(new PathMatchingResourcePatternResolver().getResources(locationPattern))
                    .map(Resource::getFilename)
                    .filter(java.util.Objects::nonNull)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("無法掃描 XML mapper: " + locationPattern, e);
        }
    }

    /**
     * 掃描 XML mapper，找出存取他模組業務表者。
     *
     * <p>owner 模組由 {@code <mapper namespace="...">} 解析而得——namespace 的值即該
     * mapper 介面的型別全名，其 package 與註解式 mapper 的 package 同構，故可直接
     * 複用 {@link #hasForeignBusinessTable(String, String)} 這份判定邏輯，
     * 兩種來源共用同一組表名 pattern 與 owner 豁免規則。</p>
     *
     * <p>比對對象為整份 XML 的內容而非逐個 {@code <select>} 元素：表名 pattern 要求
     * {@code from}／{@code join}／{@code update} 前綴，故 namespace 宣告本身不會被誤判；
     * 這個取捨換來的是不必在測試裡引入 XML 解析。</p>
     *
     * @param locationPattern classpath 位置樣式
     * @return 違規的 {@code 檔名#namespace} 描述，無違規則為空清單
     */
    private static List<String> findXmlViolations(String locationPattern) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            throw new UncheckedIOException("無法掃描 XML mapper: " + locationPattern, e);
        }

        List<String> violations = new ArrayList<>();
        for (Resource resource : resources) {
            String content;
            try {
                content = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("無法讀取 " + resource.getFilename(), e);
            }
            Matcher matcher = XML_NAMESPACE.matcher(content);
            if (!matcher.find()) {
                continue;
            }
            String namespace = matcher.group(1);
            int lastDot = namespace.lastIndexOf('.');
            String packageName = lastDot < 0 ? namespace : namespace.substring(0, lastDot);
            if (hasForeignBusinessTable(content, packageName)) {
                violations.add(resource.getFilename() + "#" + namespace);
            }
        }
        return violations.stream().distinct().sorted().toList();
    }
}
