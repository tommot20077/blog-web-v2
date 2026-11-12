package dowob.xyz.blog.module.series.model;

import dowob.xyz.blog.module.series.mapper.SeriesMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DATA-10 / 決策 B6 的結構守衛：{@code series.article_count} 反正規化計數已整個移除。
 *
 * <p>篇數一律由 article facade 即時計數；series 的實體與 mapper 不得再有任何維護或讀取該欄位的入口，
 * 否則漂移的來源就又回來了。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("series.article_count 已移除（DATA-10）")
class SeriesNoArticleCountTest {

    @Test
    @DisplayName("Series 實體沒有 articleCount 欄位")
    void entityHasNoArticleCountField() {
        assertThat(Arrays.stream(Series.class.getDeclaredFields()).map(f -> f.getName()))
                .doesNotContain("articleCount");
    }

    @Test
    @DisplayName("SeriesMapper 沒有任何以 ArticleCount 命名的增減方法")
    void mapperHasNoArticleCountMethods() {
        assertThat(Arrays.stream(SeriesMapper.class.getDeclaredMethods()).map(Method::getName))
                .noneMatch(name -> name.contains("ArticleCount"));
    }
}
