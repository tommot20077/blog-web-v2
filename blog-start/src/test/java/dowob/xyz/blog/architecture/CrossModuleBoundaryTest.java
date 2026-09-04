package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
        String lower = sql.toLowerCase();
        return BUSINESS_TABLE_OWNERS.entrySet().stream().anyMatch(e -> {
            boolean touches = lower.contains("from " + e.getKey())
                           || lower.contains("join " + e.getKey())
                           || lower.contains("update " + e.getKey());
            return touches && !packageName.contains(e.getValue());
        });
    }
}
