package dowob.xyz.blog.module.article.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 歸檔頁投影查詢 {@link ArticleMapper#findArchiveRows()} 的 SQL 形狀守衛（PERF-04）。
 *
 * <p>歸檔頁一次列出全站已發布文章，只需要 uuid／title／slug／published_at。
 * 本測試鎖定三件事：不得 {@code SELECT *}、不得選到任何大文字欄位、排序與 V22 的 partial index
 * {@code idx_articles_published_latest} 完全一致（見 IT：{@link ArticleArchiveMapperIT}）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleMapper.findArchiveRows SQL 形狀")
class ArticleArchiveMapperSqlTest {

    private static String sql() throws NoSuchMethodException {
        Method m = ArticleMapper.class.getMethod("findArchiveRows");
        return String.join(" ", m.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");
    }

    @Test
    @DisplayName("明確選欄：不含 SELECT *／a.*，且不含任何正文欄位（content_md、content_html、toc、summary）")
    void selectsOnlyArchiveColumns_noWildcardNoBodyColumns() throws Exception {
        String sql = sql();

        assertThat(sql).doesNotContain("*");
        assertThat(sql).doesNotContainIgnoringCase("content_md");
        assertThat(sql).doesNotContainIgnoringCase("content_html");
        assertThat(sql).doesNotContainIgnoringCase("toc");
        assertThat(sql).doesNotContainIgnoringCase("summary");
        assertThat(sql).containsIgnoringCase("SELECT uuid, title, slug, published_at FROM articles");
    }

    @Test
    @DisplayName("只取已發布，且排序為 published_at DESC NULLS LAST, id DESC（與 idx_articles_published_latest 逐字一致，省掉 Sort）")
    void filtersPublished_andOrdersExactlyLikePublishedLatestIndex() throws Exception {
        String sql = sql();

        assertThat(sql).contains("WHERE status = 'PUBLISHED'");
        assertThat(sql).endsWith("ORDER BY published_at DESC NULLS LAST, id DESC");
    }
}
