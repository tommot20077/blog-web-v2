package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跨模組邊界守衛（architecture.md §Cross-Module Boundary Rules）。
 *
 * <p>守衛 #5：模組的 SQL 字面值不得出現<b>他模組的表</b>名。表與 owner 模組的對照見
 * {@link ModuleTableOwnership}（涵蓋所有表，與 {@link NoCrossModuleForeignKeyIT} 共用）。
 * Z2 起的範圍：</p>
 * <ul>
 *   <li><b>{@code users} 不再豁免</b>：只有 user 模組可 FROM／JOIN／UPDATE／INSERT／DELETE {@code users}；
 *       跨模組取使用者資料一律走 {@code UserFacade}。</li>
 *   <li><b>tag 參照資料例外（Yuan 2026-10-31 決定）</b>：article 模組可唯讀 FROM／JOIN {@code tags}、{@code article_tags}，
 *       其他模組不行；寫入仍只有 tag 模組。具名常數與拆服務時的處置見
 *       {@link ModuleTableOwnership#TAG_REFERENCE_DATA_TABLES}。</li>
 *   <li><b>三種 SQL 來源</b>：MyBatis 註解（{@code @Select}／{@code @Insert}／{@code @Update}／{@code @Delete}）、
 *       MyBatis XML（{@code classpath*:mapper/*.xml}，以元素種類區分讀寫）、
 *       Spring Data JDBC {@code @Query}（帶 {@code @Modifying} 視為寫入）。模組以類別 package（XML 用 namespace）判定。</li>
 * </ul>
 *
 * <p><b>認表方式</b>：{@code FROM}／{@code JOIN}／{@code UPDATE}／{@code INTO}／{@code USING}／{@code TRUNCATE}
 * 後緊接表名（容許 {@code public.} schema 與雙引號識別字），加上同一個 FROM 子句內逗號之後的表名
 * （{@code FROM a, users u}）與 TRUNCATE 多表清單。{@code TRUNCATE} 一律視為寫入。
 * XML 的 {@code <sql>} 共用片段依內容判定讀寫：含 insert／update／delete／truncate／merge 才算寫入。</p>
 *
 * <p><b>界限</b>：動態組字串的 SQL（執行期才拼表名）認不出；{@code @SelectProvider} 等 provider
 * 類標註不在掃描內。守衛擋的是常見寫法的回退，不是完整的 SQL 解析。</p>
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

    /** 業務模組根套件，其下第一層為模組名 */
    private static final String MODULE_ROOT = "dowob.xyz.blog.module";

    /** 反向驗證 fixture（bytecode）的模組根套件 */
    private static final String FIXTURE_MODULE_ROOT = "dowob.xyz.blog.architecture.fixture.tableaccess.module";

    /** 正式碼 XML mapper 的 classpath 位置 */
    private static final String PRODUCTION_MAPPER_XML = "classpath*:mapper/*.xml";

    /** 反向驗證 fixture（XML）的 classpath 位置 */
    private static final String FIXTURE_MAPPER_XML = "classpath*:archunit-fixture/mapper/*.xml";

    /** 從 package／namespace 取出模組名：{@code dowob.xyz.blog.module.<模組>.…} */
    private static final Pattern MODULE_OF_NAME = Pattern.compile("(?:^|\\.)module\\.(\\w+)(?:\\.|$)");

    /** 無法判定模組時的占位名（不等於任何 owner，所以任何存取都算違規） */
    private static final String UNKNOWN_MODULE = "<unknown>";

    /** MyBatis 註解 → 讀寫種類 */
    private static final Map<String, Access> MYBATIS_ANNOTATIONS = Map.of(
            "org.apache.ibatis.annotations.Select", Access.READ,
            "org.apache.ibatis.annotations.Insert", Access.WRITE,
            "org.apache.ibatis.annotations.Update", Access.WRITE,
            "org.apache.ibatis.annotations.Delete", Access.WRITE);

    /** Spring Data JDBC 的 {@code @Query} */
    private static final String SPRING_QUERY = "org.springframework.data.jdbc.repository.query.Query";

    /** Spring Data JDBC 的 {@code @Modifying}（標在 {@code @Query} 方法上表示寫入） */
    private static final String SPRING_MODIFYING = "org.springframework.data.jdbc.repository.query.Modifying";

    /**
     * XML mapper 中承載 SQL 的元素 → 讀寫種類。{@code <sql>} 共用片段無法由元素本身得知被誰引用，
     * 改依內容判定，見 {@link #SQL_FRAGMENT_WRITE}，所以不在此表。
     */
    private static final Map<String, Access> XML_ELEMENTS = Map.of(
            "select", Access.READ,
            "insert", Access.WRITE,
            "update", Access.WRITE,
            "delete", Access.WRITE);

    /** {@code <sql>} 片段元素名 */
    private static final String SQL_FRAGMENT_ELEMENT = "sql";

    /**
     * {@code <sql>} 片段含有這些寫入關鍵字才算寫入，否則算讀取（唯讀 JOIN tag 參照資料的共用片段才不會被誤判，
     * Yuan 2026-10-31 決定）。含 {@code FOR UPDATE} 的片段會被保守地視為寫入。
     */
    private static final Pattern SQL_FRAGMENT_WRITE =
            Pattern.compile("(?is)\\b(insert|update|delete|truncate|merge)\\b");

    /** 有 SQL 的業務模組（search、recommend 沒有自己的表與 SQL） */
    private static final Set<String> MODULES_WITH_SQL =
            Set.of("article", "comment", "file", "reading", "series", "tag", "user", "version");

    /**
     * 表名 → 該表名的 SQL 比對 pattern（每個表各自一條組，非硬編碼）。
     *
     * <p>表參照寫成 {@code (?:"?public"?\.)?"?<表>"?(?![\w])}：schema 前綴與表名都容許可選的雙引號
     * （{@code "users"}、{@code public."users"}、{@code "public"."users"}），結尾以 {@code (?![\w])} 而非 {@code \b}
     * 收尾，才不會把 {@code users_count}、{@code articles_x} 誤判成 {@code users}／{@code articles}
     * （底線是 word 字元，{@code article_tags} 同理不會被當成 {@code articles}）。</p>
     *
     * <p>每張表有三條 pattern，只要任一條命中就算存取：</p>
     * <ol>
     *   <li>主 pattern：{@code FROM／JOIN／UPDATE／INTO／USING／TRUNCATE [TABLE] [ONLY]} 後緊接表名；</li>
     *   <li>逗號式 JOIN：同一個 FROM 子句內、出現在逗號之後（{@code FROM comments c, users u}）。
     *       子句範圍 {@code [^;()]*?} 不跨分號與括號，並在遇到 {@code WHERE／GROUP／ORDER／HAVING／LIMIT／…}
     *       等關鍵字時截斷，所以 {@code SELECT a, users_count FROM comments}（逗號在 FROM 之前）、
     *       {@code IN (1, 2)}（括號內）與 {@code ORDER BY c.id, users_count}（子句已結束）都不會誤判；</li>
     *   <li>TRUNCATE 多表：{@code TRUNCATE comments, users}。</li>
     * </ol>
     */
    private static final Map<String, TablePatterns> TABLE_PATTERNS = ModuleTableOwnership.OWNER_BY_TABLE.keySet().stream()
            .collect(Collectors.toMap(table -> table, TablePatterns::of));

    /** 逗號式 JOIN 的子句截斷關鍵字：遇到即視為 FROM 子句已結束 */
    private static final String FROM_CLAUSE_STOP =
            "(?:(?!\\b(?:where|group|order|having|limit|offset|union|intersect|except|window|fetch|returning|select|set)\\b)[^;()])*?";

    /**
     * 一張表的 SQL 比對 pattern 組。
     *
     * @param primary       {@code FROM／JOIN／UPDATE／INTO／USING} 後緊接表名
     * @param truncate      {@code TRUNCATE [TABLE] [ONLY]} 後緊接表名
     * @param commaJoin     同一個 FROM 子句內逗號之後的表名
     * @param truncateList  TRUNCATE 多表清單中逗號之後的表名
     */
    private record TablePatterns(Pattern primary, Pattern truncate, Pattern commaJoin, Pattern truncateList) {

        static TablePatterns of(String table) {
            String ref = "(?:\"?public\"?\\.)?\"?" + Pattern.quote(table) + "\"?(?![\\w])";
            return new TablePatterns(
                    Pattern.compile("(?is)\\b(?:from|join|update|into|using)\\s+" + ref),
                    Pattern.compile("(?is)\\btruncate\\s+(?:table\\s+)?(?:only\\s+)?" + ref),
                    Pattern.compile("(?is)\\bfrom\\s+" + FROM_CLAUSE_STOP + ",\\s*" + ref),
                    Pattern.compile("(?is)\\btruncate\\s+" + FROM_CLAUSE_STOP + ",\\s*" + ref));
        }
    }

    /** SQL 的讀寫種類 */
    private enum Access { READ, WRITE }

    /**
     * 一段待檢查的 SQL。
     *
     * @param kind     來源種類（如 {@code @Select}、{@code xml:<delete>}），供防空轉斷言分類計數
     * @param module   發出 SQL 的模組名
     * @param location 位置（{@code 類別#方法} 或 {@code 檔名#statementId}）
     * @param access   讀寫種類
     * @param sql      SQL 文字
     */
    private record SqlSource(String kind, String module, String location, Access access, String sql) {
    }

    // ─── 守衛 #5：註解（MyBatis ＋ Spring Data JDBC @Query）───

    @Test
    @DisplayName("守衛 #5：mapper／repository 註解 SQL 不得存取他模組的表（users 不豁免；tag 參照資料僅 article 可唯讀）")
    void mapperMustNotAccessForeignModuleBusinessTable() {
        assertThat(findViolations(annotationSources(productionClasses(), MODULE_ROOT)))
                .as("SQL 不得存取他模組的表（對照表見 ModuleTableOwnership）。"
                  + "跨模組取資料請走 facade；唯一例外是 article 唯讀 tags／article_tags（Yuan 2026-10-31 決定）。")
                .isEmpty();
    }

    @Test
    @DisplayName("防假陰性：註解 SQL 掃描必須涵蓋 @Select/@Insert/@Update/@Delete/@Query/@Modifying @Query 與所有有 SQL 的模組")
    void productionAnnotationScanMustActuallyFindSql() {
        List<SqlSource> sources = annotationSources(productionClasses(), MODULE_ROOT);

        assertThat(sources.stream().map(SqlSource::kind).collect(Collectors.toSet()))
                .as("每一種 SQL 標註都必須在正式碼掃到至少一筆；缺任何一種，該種標註的守衛就是空轉的")
                .containsExactlyInAnyOrder("@Select", "@Insert", "@Update", "@Delete", "@Query", "@Modifying @Query");
        assertThat(sources.stream().map(SqlSource::module).collect(Collectors.toSet()))
                .as("註解 SQL 所在的模組")
                .containsAll(Set.of("article", "comment", "file", "reading", "series", "tag", "user", "version"));
    }

    @Test
    @DisplayName("防假陰性：每個有 SQL 的模組都必須掃到命中「自己的表」，users 也必須被 user 模組命中（表名 pattern 有效）")
    void productionScanMustMatchOwnTablesOfEveryModule() {
        List<SqlSource> sources = new ArrayList<>(annotationSources(productionClasses(), MODULE_ROOT));
        sources.addAll(xmlSources(PRODUCTION_MAPPER_XML));
        Map<String, Set<String>> ownTablesHitByModule = new HashMap<>();
        for (SqlSource source : sources) {
            for (String table : touchedTables(source.sql()).keySet()) {
                if (ModuleTableOwnership.OWNER_BY_TABLE.get(table).equals(source.module())) {
                    ownTablesHitByModule.computeIfAbsent(source.module(), m -> new TreeSet<>()).add(table);
                }
            }
        }

        assertThat(ownTablesHitByModule.keySet())
                .as("表名 pattern 若對 owner 自己的 SQL 都命中不了，對他模組的 SQL 也一樣會漏，守衛等於空轉")
                .containsAll(MODULES_WITH_SQL);
        assertThat(ownTablesHitByModule.get("user"))
                .as("Z2 的重點：users 不再豁免，pattern 必須真的認得 users")
                .contains("users");
    }

    @Test
    @DisplayName("tripwire：正式碼掃描不得掃進 architecture.fixture")
    void productionScanMustNotContainFixtures() {
        assertThat(annotationSources(productionClasses(), MODULE_ROOT))
                .as("fixture 的故意違規若被掃進正式碼結果，會污染守衛")
                .noneMatch(s -> s.location().contains(".architecture.fixture"));
    }

    @Test
    @DisplayName("反向驗證（註解）：抓得到 users／tags／articles／user_preferences 的跨模組存取（含 Insert/Delete/@Query/陣列寫法），不誤判合規者")
    void annotationScanDetectsViolationsWithoutFalsePositive() {
        List<String> violations = findViolations(annotationSources(fixtureClasses(), FIXTURE_MODULE_ROOT));

        assertThat(violations)
                .as("守衛若抓不到已知違規，「正向掃描為空」就沒有意義。"
                  + "合規者（comment 讀寫自己的表、article 唯讀 tag 參照資料、user 讀寫 users、series 的 @Query 讀 series）不得出現")
                .containsExactlyInAnyOrder(
                        "[comment] " + fx("comment.mapper.CommentUsersJoinMapper") + "#joinUsers -> users",
                        "[comment] " + fx("comment.mapper.CommentUsersJoinMapper") + "#readTags -> tags",
                        "[series] " + fx("series.mapper.SeriesWriteUsersMapper") + "#insertUser -> users",
                        "[series] " + fx("series.mapper.SeriesWriteUsersMapper") + "#updateUser -> users",
                        "[series] " + fx("series.mapper.SeriesWriteUsersMapper") + "#deleteUser -> users",
                        "[series] " + fx("series.repository.SeriesQueryRepository") + "#readUsers -> users",
                        "[series] " + fx("series.repository.SeriesQueryRepository") + "#deleteArticles -> articles",
                        "[article] " + fx("article.mapper.ArticleUsersJoinMapper") + "#joinUsers -> users",
                        "[article] " + fx("article.mapper.ArticleTagWriterMapper") + "#insertArticleTag -> article_tags",
                        "[article] " + fx("article.mapper.ArticleTagWriterMapper") + "#deleteTag -> tags",
                        "[article] " + fx("article.mapper.ArticleTagWriterMapper") + "#truncateDisguisedAsSelect -> article_tags",
                        "[comment] " + fx("comment.mapper.CommentSqlSyntaxMapper") + "#commaJoin -> users",
                        "[comment] " + fx("comment.mapper.CommentSqlSyntaxMapper") + "#commaJoinThird -> users",
                        "[comment] " + fx("comment.mapper.CommentSqlSyntaxMapper") + "#quotedTable -> users",
                        "[comment] " + fx("comment.mapper.CommentSqlSyntaxMapper") + "#quotedTableWithSchema -> users",
                        "[comment] " + fx("comment.mapper.CommentSqlSyntaxMapper") + "#fullyQuoted -> users",
                        "[comment] " + fx("comment.mapper.CommentSqlSyntaxMapper") + "#truncateTable -> users",
                        "[comment] " + fx("comment.mapper.CommentSqlSyntaxMapper") + "#truncateList -> users",
                        "[reading] " + fx("reading.mapper.ReadingVersionTableMapper") + "#readPreferences -> user_preferences");
    }

    @Test
    @DisplayName("存取規則：owner 一律可；tag 參照資料僅 article 可唯讀；users 除 user 外一律不可")
    void accessRuleMatrix() {
        assertThat(ModuleTableOwnership.mayAccess("user", "users", false)).isTrue();
        assertThat(ModuleTableOwnership.mayAccess("article", "users", true)).isFalse();
        assertThat(ModuleTableOwnership.mayAccess("comment", "users", true)).isFalse();
        assertThat(ModuleTableOwnership.mayAccess("tag", "tags", false)).isTrue();
        assertThat(ModuleTableOwnership.mayAccess("article", "tags", true)).isTrue();
        assertThat(ModuleTableOwnership.mayAccess("article", "article_tags", true)).isTrue();
        assertThat(ModuleTableOwnership.mayAccess("article", "tags", false)).isFalse();
        assertThat(ModuleTableOwnership.mayAccess("article", "user_tag_follows", true))
                .as("放行只及 tags／article_tags，不含 user_tag_follows").isFalse();
        assertThat(ModuleTableOwnership.mayAccess("comment", "tags", true)).isFalse();
        assertThat(ModuleTableOwnership.mayAccess("version", "articles", true)).isFalse();
    }

    @Test
    @DisplayName("對照表只能出現已知業務模組，且涵蓋所有有 SQL 的模組")
    void ownershipMapMustOnlyNameKnownModules() {
        assertThat(ModuleTableOwnership.businessModules())
                .containsAll(MODULES_WITH_SQL)
                .allSatisfy(m -> assertThat(Set.of("article", "comment", "file", "reading", "recommend", "search",
                        "series", "tag", "user", "version")).contains(m));
    }

    /**
     * 攤平 annotation value：MyBatis {@code @Select}/{@code @Update} 的
     * {@code value()} 宣告為陣列型別（{@code String[]}），依 JVMS §4.7.16.1，
     * ArchUnit 讀出來的 {@code value()} 永遠是 {@code Object[]}（見上方 class
     * javadoc「annotation value 一律是陣列」），不論原始碼寫成單一字串
     * {@code @Select("...")} 還是陣列字面值 {@code @Select({"...", ...})}。
     * 陣列以空白 join 避免片段黏成單一 token，讓多元素形式（MyBatis 動態 SQL
     * {@code <script>}/{@code <foreach>} 慣用寫法）也能正確比對。
     * Spring Data 的 {@code @Query.value()} 是單一 {@code String}，走 fallback 分支。
     */
    private static String flattenAnnotationValue(Object raw) {
        return (raw instanceof Object[] parts)
                ? Arrays.stream(parts).map(String::valueOf).collect(Collectors.joining(" "))
                : String.valueOf(raw);
    }

    // ─── 守衛 #5：XML mapper ───

    @Test
    @DisplayName("守衛 #5（XML）：XML mapper 不得在 SQL 中存取他模組的表")
    void xmlMapperMustNotAccessForeignModuleBusinessTable() {
        assertThat(findViolations(xmlSources(PRODUCTION_MAPPER_XML)))
                .as("XML mapper 與註解式 mapper 受同一條規則約束。"
                  + "本項存在的原因：註解式掃描讀的是 bytecode 的註解，"
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
    @DisplayName("防假陰性：XML 守衛必須實際解析到 <select> 語句，否則元素解析失效時恆綠")
    void productionXmlScanMustActuallyParseStatements() {
        List<SqlSource> sources = xmlSources(PRODUCTION_MAPPER_XML);

        assertThat(sources)
                .as("解析出的 SQL 元素數；為 0 代表 DOM 解析或元素名判讀失效")
                .isNotEmpty();
        assertThat(sources).anySatisfy(s -> {
            assertThat(s.kind()).isEqualTo("xml:<select>");
            assertThat(s.module()).isEqualTo("article");
        });
    }

    @Test
    @DisplayName("反向驗證（XML）：抓得到 article JOIN users 與 article 寫 article_tags，不誤判 owner 自查與 tag 唯讀")
    void xmlScanDetectsViolationWithoutFalsePositive() {
        List<String> violations = findViolations(xmlSources(FIXTURE_MAPPER_XML));

        assertThat(violations)
                .as("守衛若抓不到已知違規，正向掃描為空就沒有意義——這正是 29a1000 修過的靜默假陰性形態。"
                  + "CompliantArticleMapper（article 查自己的 articles、唯讀 JOIN tags）、CompliantArticleSqlFragmentMapper"
                  + "（article 的唯讀 <sql> 片段 JOIN tags）與 CompliantUserMapper（user 讀寫 users）不得出現")
                .containsExactlyInAnyOrder(
                        "[series] ViolatingSeriesMapper.xml#countPublishedArticles -> articles",
                        "[article] ViolatingUsersJoinMapper.xml#findWithAuthor -> users",
                        "[article] ViolatingTagWriteMapper.xml#removeTags -> article_tags",
                        "[article] ViolatingTagWriteMapper.xml#writeFragment -> article_tags",
                        "[comment] ViolatingCommentSqlFragmentMapper.xml#tagJoin -> tags");
    }

    // ─── 判定邏輯 ───

    /**
     * 找出存取他模組表的 SQL。
     *
     * @param sources 待檢查的 SQL
     * @return 違規描述 {@code [模組] 位置 -> 表}，已去重排序
     */
    private static List<String> findViolations(List<SqlSource> sources) {
        Set<String> violations = new TreeSet<>();
        for (SqlSource source : sources) {
            touchedTables(source.sql()).forEach((table, truncated) -> {
                boolean readOnly = source.access() == Access.READ && !truncated;
                if (!ModuleTableOwnership.mayAccess(source.module(), table, readOnly)) {
                    violations.add("[" + source.module() + "] " + source.location() + " -> " + table);
                }
            });
        }
        return List.copyOf(violations);
    }

    /**
     * SQL 文字存取的、已登記於對照表的表，及其是否被 TRUNCATE（TRUNCATE 一律視為寫入，不論 SQL 標在哪種標註上）。
     *
     * @param sql SQL 文字
     * @return 表名 → 是否被 TRUNCATE
     */
    private static Map<String, Boolean> touchedTables(String sql) {
        Map<String, Boolean> tables = new java.util.LinkedHashMap<>();
        TABLE_PATTERNS.forEach((table, patterns) -> {
            boolean truncated = patterns.truncate().matcher(sql).find() || patterns.truncateList().matcher(sql).find();
            boolean touched = truncated
                    || patterns.primary().matcher(sql).find()
                    || patterns.commaJoin().matcher(sql).find();
            if (touched) {
                tables.put(table, truncated);
            }
        });
        return tables;
    }

    // ─── SQL 來源蒐集 ───

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(MODULE_ROOT);
    }

    private static JavaClasses fixtureClasses() {
        return new ClassFileImporter().importPackages(FIXTURE_MODULE_ROOT);
    }

    private static String fx(String relativeName) {
        return FIXTURE_MODULE_ROOT + "." + relativeName;
    }

    /**
     * 蒐集 bytecode 上的 SQL 註解（MyBatis 四種 ＋ Spring Data JDBC {@code @Query}）。
     *
     * @param classes    匯入的類別
     * @param moduleRoot 模組根套件，其下第一層為模組名
     * @return SQL 來源
     */
    private static List<SqlSource> annotationSources(JavaClasses classes, String moduleRoot) {
        List<SqlSource> sources = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            if (!javaClass.getPackageName().startsWith(moduleRoot + ".")) {
                continue;
            }
            String module = moduleOfPackage(javaClass.getPackageName(), moduleRoot);
            for (JavaMethod method : javaClass.getMethods()) {
                method.getAnnotations().forEach(annotation -> {
                    String type = annotation.getRawType().getName();
                    String location = javaClass.getName() + "#" + method.getName();
                    Optional<Object> value = annotation.get("value");
                    String sql = flattenAnnotationValue(value.orElse(""));
                    if (MYBATIS_ANNOTATIONS.containsKey(type)) {
                        sources.add(new SqlSource("@" + annotation.getRawType().getSimpleName(), module, location,
                                MYBATIS_ANNOTATIONS.get(type), sql));
                    } else if (SPRING_QUERY.equals(type)) {
                        boolean modifying = method.isAnnotatedWith(SPRING_MODIFYING);
                        sources.add(new SqlSource(modifying ? "@Modifying @Query" : "@Query", module, location,
                                modifying ? Access.WRITE : Access.READ, sql));
                    }
                });
            }
        }
        return sources;
    }

    private static String moduleOfPackage(String packageName, String moduleRoot) {
        String rest = packageName.substring(moduleRoot.length() + 1);
        int dot = rest.indexOf('.');
        return dot < 0 ? rest : rest.substring(0, dot);
    }

    /**
     * 蒐集 XML mapper 的 SQL：逐個 {@code <select>}／{@code <insert>}／{@code <update>}／{@code <delete>}／{@code <sql>}
     * 元素取文字（DOM 不含 XML 註解）。模組由 {@code <mapper namespace="…">} 的 package 判定——
     * namespace 的值即 mapper 介面全名，其 package 與註解式 mapper 同構。
     *
     * @param locationPattern classpath 位置樣式
     * @return SQL 來源
     */
    private static List<SqlSource> xmlSources(String locationPattern) {
        List<SqlSource> sources = new ArrayList<>();
        for (Resource resource : resolve(locationPattern)) {
            Element mapper = parse(resource).getDocumentElement();
            String namespace = mapper.getAttribute("namespace");
            Matcher matcher = MODULE_OF_NAME.matcher(namespace);
            String module = matcher.find() ? matcher.group(1) : UNKNOWN_MODULE;
            for (Node child = mapper.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element element
                        && (XML_ELEMENTS.containsKey(element.getTagName())
                        || SQL_FRAGMENT_ELEMENT.equals(element.getTagName()))) {
                    String text = element.getTextContent();
                    Access access = SQL_FRAGMENT_ELEMENT.equals(element.getTagName())
                            ? (SQL_FRAGMENT_WRITE.matcher(text).find() ? Access.WRITE : Access.READ)
                            : XML_ELEMENTS.get(element.getTagName());
                    sources.add(new SqlSource("xml:<" + element.getTagName() + ">", module,
                            resource.getFilename() + "#" + element.getAttribute("id"), access, text));
                }
            }
        }
        return sources;
    }

    /**
     * 列出指定位置實際掃到的 XML mapper 檔名。
     *
     * <p>供防假陰性測試使用：違規清單為空有兩種可能——真的沒有違規，或根本沒掃到檔案。
     * 分開這兩者才能讓守衛的「綠」有意義。</p>
     *
     * @param locationPattern classpath 位置樣式
     * @return 掃到的檔名清單
     */
    private static List<String> listScannedMappers(String locationPattern) {
        return Arrays.stream(resolve(locationPattern))
                .map(Resource::getFilename)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
    }

    private static Resource[] resolve(String locationPattern) {
        try {
            return new PathMatchingResourcePatternResolver().getResources(locationPattern);
        } catch (IOException e) {
            throw new UncheckedIOException("無法掃描 XML mapper: " + locationPattern, e);
        }
    }

    /** 解析 mapper XML；DOCTYPE 指向的外部 DTD 一律以空內容取代，避免測試時連網。 */
    private static Document parse(Resource resource) {
        try {
            DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
            return builder.parse(resource.getInputStream());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("無法解析 " + resource.getFilename(), e);
        } catch (IOException e) {
            throw new UncheckedIOException("無法讀取 " + resource.getFilename(), e);
        }
    }
}
