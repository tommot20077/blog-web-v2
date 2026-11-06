package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.infrastructure.config.UUIDTypeHandler;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.article.model.PublishedArticleCriteria;
import dowob.xyz.blog.module.article.model.dto.request.ArticleListQuery;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.relational.core.mapping.Column;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文章「列表／摘要」查詢的 SQL 形狀守衛（PERF-06）。
 *
 * <p>列表只組 {@code ArticleSummaryResponse}／{@code ArticleData}，用不到 {@code content_md}、
 * {@code content_html}、{@code toc} 三個 TEXT 欄位；{@code SELECT *} 會讓 PostgreSQL 把整頁文章的正文也讀出並傳輸。
 * 本測試鎖定：列表類查詢不得 {@code *}、不得選到三個正文欄位，且必須涵蓋 {@link Article} 其餘所有欄位
 * （避免日後新增欄位時列表悄悄讀到 null）。詳情／ES 索引路徑（{@code findAllPublished}）不在此限。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleMapper 列表查詢 SQL 形狀（PERF-06）")
class ArticleMapperListColumnsSqlTest {

    /** 列表／摘要用途的 mapper 方法 */
    private static final List<String> LIST_STATEMENTS = List.of(
            "findPublishedPageByCriteria",
            "findByAuthorUuidAndStatus",
            "findByAuthorUuidPaged",
            "findPendingReviewPage",
            "findBySeriesUuidOrderByPosition");

    /** 列表不得讀取的正文欄位 */
    private static final Set<String> BODY_COLUMNS = Set.of("content_md", "content_html", "toc");

    private static Configuration configuration;

    @BeforeAll
    static void setUp() {
        configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(UUIDTypeHandler.class);
        configuration.addMapper(ArticleMapper.class);
    }

    private static String render(String method) {
        Map<String, Object> params = new HashMap<>();
        params.put("criteria", PublishedArticleCriteria.of(ArticleListQuery.unfiltered(), LocalDateTime.now()));
        params.put("authorUuid", UUID.randomUUID());
        params.put("status", ArticleStatus.PUBLISHED);
        params.put("seriesUuid", UUID.randomUUID());
        params.put("offset", 0L);
        params.put("size", 10);
        return configuration.getMappedStatement(ArticleMapper.class.getName() + "." + method)
                .getBoundSql(params).getSql().replaceAll("\\s+", " ");
    }

    /** 實體上除正文三欄外的全部欄位對應的 DB 欄名 */
    private static List<String> nonBodyColumns() {
        return java.util.Arrays.stream(Article.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(ArticleMapperListColumnsSqlTest::columnOf)
                .filter(c -> !BODY_COLUMNS.contains(c))
                .toList();
    }

    private static String columnOf(Field f) {
        Column column = f.getAnnotation(Column.class);
        return column != null ? column.value() : f.getName();
    }

    @Test
    @DisplayName("實體欄位盤點：正文三欄確實是 content_md／content_html／toc（守衛前提）")
    void entityBodyColumns_arePinned() {
        List<String> all = java.util.Arrays.stream(Article.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic()).map(ArticleMapperListColumnsSqlTest::columnOf).toList();
        assertThat(all).containsAll(BODY_COLUMNS);
    }

    @Test
    @DisplayName("列表類查詢全部改為明確選欄：不含 * 與三個正文欄位")
    void listStatements_noWildcard_noBodyColumns() {
        for (String method : LIST_STATEMENTS) {
            String sql = render(method);
            String select = sql.substring(0, sql.toUpperCase().indexOf(" FROM "));
            assertThat(select).as(method).doesNotContain("*");
            for (String body : BODY_COLUMNS) {
                assertThat(select).as(method + " 不得選 " + body).doesNotContainPattern("\\b" + body + "\\b");
            }
        }
    }

    @Test
    @DisplayName("列表類查詢涵蓋實體其餘全部欄位（新增欄位時須同步補進列表欄位清單）")
    void listStatements_selectEveryNonBodyColumn() {
        for (String method : LIST_STATEMENTS) {
            String sql = render(method);
            String select = sql.substring(0, sql.toUpperCase().indexOf(" FROM "));
            for (String column : nonBodyColumns()) {
                assertThat(select).as(method + " 應選 " + column).containsPattern("\\b" + column + "\\b");
            }
        }
    }

    @Test
    @DisplayName("ES 索引用的 findAllPublished 需要正文：明寫 content_md AS content，且不撈 content_html／toc")
    void findAllPublished_selectsBodyAsContent() {
        String sql = render("findAllPublished");
        assertThat(sql).contains("content_md AS content");
        assertThat(sql).doesNotContainPattern("\\bcontent_html\\b").doesNotContainPattern("\\btoc\\b");
    }
}
