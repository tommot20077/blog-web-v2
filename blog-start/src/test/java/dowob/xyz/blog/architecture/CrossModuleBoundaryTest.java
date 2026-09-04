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
 * <p><b>陣列型 {@code @Select({...})} 的處理</b>：MyBatis 的動態 SQL
 * （{@code <script>} / {@code <foreach>}）慣用陣列型 annotation value，
 * ArchUnit 對這種成員回傳的是陣列物件而非單一字串——
 * 若只用 {@code String.valueOf(...)} 會拿到類似
 * {@code [Ljava.lang.String;@1b6d3586} 的字串，對陣列型 SQL 完全失明。
 * 本守衛因此把陣列攤平、以空白 join 回單一字串再比對，
 * 空白是必要的：{@code "... WHERE id IN"} 與 {@code "<foreach ...>"}
 * 若直接串接（不留分隔）可能黏成不含空白的 token，讓 "from articles" 這類
 * 關鍵字比對失真。</p>
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
     * 攤平 annotation value：{@code @Select("...")}（單一字串）與
     * {@code @Select({"...", "...", ...})}（陣列，MyBatis 動態 SQL 慣用形式）
     * 都要處理，陣列以空白 join 避免片段黏成單一 token。
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
