package dowob.xyz.blog.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * article 模組與 file 模組「檔案綁定」解耦守衛（W5A／Q12）。
 *
 * <p>「檔案 → 文章」綁定已改由 file 模組消費 {@code ArticleContentChangedEvent} 非同步回填；
 * article 模組不得再依賴任何 file 綁定相關類別（曾經的 {@code ArticleFileBinder}、{@code FileFacade}）。
 * 一旦有人把同步呼叫加回來，會重新引入 article ⇄ file 的循環依賴風險，並讓「綁定以事件為唯一來源」的不變量失效。
 * 一般的跨模組 import 已由 {@link CrossModuleImportTest} 守住；這裡補的是它看不到的缺口：
 * article 對 {@code blog-infrastructure} 內 file 專屬 facade 的依賴，以及 article 自己重新長出的 {@code *FileBinder}。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("article 模組與 file 綁定解耦（W5A／Q12）")
class ArticleFileBindingDecouplingTest {

    private static final String ARTICLE_PACKAGE = "dowob.xyz.blog.module.article..";

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dowob.xyz.blog.module.article", "dowob.xyz.blog.infrastructure.facade");
    }

    @Test
    @DisplayName("article 模組不得依賴 blog-infrastructure 中以 File 開頭的 facade（例如已移除的 FileFacade）")
    void articleModule_mustNotDependOnFileFacades() {
        JavaClasses classes = productionClasses();

        noClasses().that().resideInAPackage(ARTICLE_PACKAGE)
                .should().dependOnClassesThat(
                        com.tngtech.archunit.base.DescribedPredicate.describe(
                                "是 blog-infrastructure facade 套件中以 File 開頭的類別",
                                (JavaClass c) -> c.getPackageName().startsWith("dowob.xyz.blog.infrastructure.facade")
                                        && c.getSimpleName().startsWith("File")))
                .because("檔案綁定由 file 模組消費 ArticleContentChangedEvent 回填（W5A／Q12），article 不得再同步呼叫")
                .check(classes);
    }

    @Test
    @DisplayName("article 模組不得自行再長出 *FileBinder 類別（綁定解析邏輯歸 file 模組）")
    void articleModule_mustNotHaveFileBinder() {
        List<String> binders = productionClasses().stream()
                .filter(c -> c.getPackageName().startsWith("dowob.xyz.blog.module.article"))
                .map(JavaClass::getSimpleName)
                .filter(n -> n.contains("FileBinder") || n.contains("FileBinding"))
                .toList();

        assertThat(binders).isEmpty();
    }

    @Test
    @DisplayName("防假陰性：確實掃到 article 模組的類別，否則守衛是空轉的")
    void scan_mustActuallyFindArticleClasses() {
        long articleClasses = productionClasses().stream()
                .filter(c -> c.getPackageName().startsWith("dowob.xyz.blog.module.article"))
                .count();

        assertThat(articleClasses).isGreaterThan(20);
    }
}
