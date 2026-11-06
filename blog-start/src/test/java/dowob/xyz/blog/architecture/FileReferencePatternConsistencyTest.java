package dowob.xyz.blog.architecture;

import dowob.xyz.blog.module.article.mapper.ArticleAuthorCleanupMapper;
import dowob.xyz.blog.module.file.service.ArticleFileBindingService;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守衛：「內文引用了哪些檔案」的判定 regex 在兩處各寫一份（DATA-07）——
 * article 的 SQL（{@code ArticleAuthorCleanupMapper#findReferencedFileUuidsByAuthorAndStatuses}，決定刪檔時什麼算「仍被引用」）
 * 與 file 的 Java（{@code ArticleFileBindingService}，決定綁定回填）。一邊是 SQL、一邊是 Java，無法共用常數，
 * 但兩者漂移會讓清理判斷與綁定判斷不一致（例如誤刪仍被引用的圖），所以這裡釘住兩者的 pattern 字面相同。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("檔案引用 regex：article SQL 與 file Java 的 pattern 必須字面相同")
class FileReferencePatternConsistencyTest {

    @Test
    @DisplayName("ArticleAuthorCleanupMapper 的 SQL 內含與 ArticleFileBindingService.FILE_CONTENT_URL_PATTERN 完全相同的 pattern 字串")
    void sqlAndJavaPatternsAreLiterallyEqual() throws Exception {
        Pattern javaPattern = (Pattern) ReflectionTestUtils.getField(ArticleFileBindingService.class, "FILE_CONTENT_URL_PATTERN");
        Method method = ArticleAuthorCleanupMapper.class.getMethod("findReferencedFileUuidsByAuthorAndStatuses",
                java.util.UUID.class, java.util.List.class);
        String sql = String.join("", method.getAnnotation(Select.class).value());

        assertThat(javaPattern).isNotNull();
        assertThat(sql).contains("'" + javaPattern.pattern() + "'");
    }
}
