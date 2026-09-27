package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.infrastructure.config.UUIDTypeHandler;
import dowob.xyz.blog.module.article.model.PublishedArticleCriteria;
import dowob.xyz.blog.module.article.model.dto.request.ArticleListQuery;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ArticleMapper 公開列表動態 SQL 的渲染測試（不連資料庫）
 *
 * <p><b>存在理由</b>：service 層的單元測試 mock 掉 mapper，SQL 本身一行都不會被執行；
 * 能執行 SQL 的 {@code ArticleControllerIT} 需要 Docker。本測試以 MyBatis {@link Configuration}
 * 直接解析 mapper 註解並渲染 {@link BoundSql}，在無資料庫環境下驗證 mock 與 IT 之間的空隙：
 * {@code <script>} 為合法 XML、OGNL 條件能對 {@link PublishedArticleCriteria} 求值、
 * {@code <foreach>} 的展開與 bind parameter 的<b>順序與值</b>正確、列表與 count 的 WHERE 一致。</p>
 *
 * <p><b>不涵蓋</b>：PostgreSQL 的語法與語意（AND／OR 結果、排序、tie-breaker 確定性）——那是 IT 的範圍。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleMapper 公開列表動態 SQL 渲染")
class ArticleMapperCriteriaSqlTest {

    /** 分頁查詢的 mapped statement id */
    private static final String PAGE_STATEMENT = ArticleMapper.class.getName() + ".findPublishedPageByCriteria";

    /** count 查詢的 mapped statement id */
    private static final String COUNT_STATEMENT = ArticleMapper.class.getName() + ".countPublishedByCriteria";

    /** 固定的「現在」 */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 25, 12, 0);

    /** 僅含 ArticleMapper 的 MyBatis 設定（與正式環境同樣註冊 UUID type handler） */
    private static Configuration configuration;

    @BeforeAll
    static void setUp() {
        configuration = new Configuration();
        configuration.getTypeHandlerRegistry().register(UUIDTypeHandler.class);
        configuration.addMapper(ArticleMapper.class);
    }

    @Test
    @DisplayName("不篩選 → 只有 PUBLISHED 條件、依 published_at 排序附 id tie-breaker，僅 LIMIT/OFFSET 兩個參數")
    void unfiltered_rendersOnlyStatusConditionAndLatestOrder() {
        BoundSql sql = renderPage(PublishedArticleCriteria.of(ArticleListQuery.unfiltered(), NOW), 0L, 10);

        String text = normalize(sql.getSql());
        assertThat(text).startsWith("SELECT a.* FROM articles a WHERE a.status = 'PUBLISHED'");
        assertThat(text).doesNotContain("EXISTS", "HAVING", "users u", "published_at >=");
        assertThat(text).endsWith("ORDER BY a.published_at DESC NULLS LAST, a.id DESC LIMIT ? OFFSET ?");
        assertThat(parameterValues(sql)).containsExactly(10, 0L);
    }

    @Test
    @DisplayName("全部篩選 → 各條件依序出現，bind parameter 的順序與值正確")
    void allFilters_rendersEveryConditionWithOrderedParameters() {
        UUID author = UUID.randomUUID();
        ArticleListQuery query = new ArticleListQuery(
                List.of("java", "spring"), List.of("tech", "life"), List.of(author), 30, "latest");
        PublishedArticleCriteria criteria = PublishedArticleCriteria.of(query, NOW);

        BoundSql sql = renderPage(criteria, 20L, 10);

        String text = normalize(sql.getSql());
        assertThat(text).containsPattern(
                "AND EXISTS \\(SELECT 1 FROM article_categories ac INNER JOIN categories c ON c.id = ac.category_id "
                        + "WHERE ac.article_id = a.id AND c.slug IN \\(\\s*\\?\\s*,\\s*\\?\\s*\\)\\s*\\)");
        assertThat(text).containsPattern(
                "AND a.uuid IN \\(SELECT art.article_id FROM article_tags art INNER JOIN tags t ON t.id = art.tag_id "
                        + "WHERE t.slug IN \\(\\s*\\?\\s*,\\s*\\?\\s*\\)\\s*GROUP BY art.article_id "
                        + "HAVING COUNT\\(DISTINCT t.id\\) = \\?\\)");
        assertThat(text).containsPattern(
                "AND a.author_id IN \\(SELECT u.id FROM users u WHERE u.uuid IN \\(\\s*\\?::uuid\\s*\\)\\s*\\)");
        assertThat(text).contains("AND a.published_at >= ?");
        assertThat(parameterValues(sql)).containsExactly(
                "tech", "life", "java", "spring", 2, author, NOW.minusDays(30), 10, 20L);
    }

    @Test
    @DisplayName("sort=popular → 依 view_count 排序附 id tie-breaker")
    void popularSort_ordersByViewCount() {
        ArticleListQuery query = new ArticleListQuery(null, null, null, null, "popular");

        String text = normalize(renderPage(PublishedArticleCriteria.of(query, NOW), 0L, 10).getSql());

        assertThat(text).endsWith("ORDER BY a.view_count DESC, a.id DESC LIMIT ? OFFSET ?");
    }

    @Test
    @DisplayName("sort=commented → 依 comment_count 排序附 id tie-breaker")
    void commentedSort_ordersByCommentCount() {
        ArticleListQuery query = new ArticleListQuery(null, null, null, null, "commented");

        String text = normalize(renderPage(PublishedArticleCriteria.of(query, NOW), 0L, 10).getSql());

        assertThat(text).endsWith("ORDER BY a.comment_count DESC, a.id DESC LIMIT ? OFFSET ?");
    }

    @Test
    @DisplayName("count 查詢與分頁查詢的 WHERE 完全一致，且不帶 ORDER BY / LIMIT")
    void countQuery_sharesExactlyTheSameWhereClause() {
        ArticleListQuery query = new ArticleListQuery(
                List.of("java"), List.of("tech"), List.of(UUID.randomUUID()), 30, "popular");
        PublishedArticleCriteria criteria = PublishedArticleCriteria.of(query, NOW);

        String page = normalize(renderPage(criteria, 0L, 10).getSql());
        Map<String, Object> countParams = new HashMap<>();
        countParams.put("criteria", criteria);
        String count = normalize(configuration.getMappedStatement(COUNT_STATEMENT).getBoundSql(countParams).getSql());

        String pageWhere = page.substring("SELECT a.* FROM articles a ".length(), page.indexOf(" ORDER BY "));
        assertThat(count).isEqualTo("SELECT COUNT(*) FROM articles a " + pageWhere);
    }

    /**
     * 渲染分頁查詢。
     *
     * @param criteria 查詢條件
     * @param offset   偏移量
     * @param size     每頁筆數
     * @return 渲染後的 SQL 與參數對應
     */
    private BoundSql renderPage(PublishedArticleCriteria criteria, long offset, int size) {
        return configuration.getMappedStatement(PAGE_STATEMENT).getBoundSql(pageParams(criteria, offset, size));
    }

    /**
     * 建立與 MyBatis mapper proxy 相同形狀的參數 Map（{@code @Param} 名稱為 key）。
     *
     * @param criteria 查詢條件
     * @param offset   偏移量
     * @param size     每頁筆數
     * @return 參數 Map
     */
    private Map<String, Object> pageParams(PublishedArticleCriteria criteria, long offset, int size) {
        Map<String, Object> params = new HashMap<>();
        params.put("criteria", criteria);
        params.put("offset", offset);
        params.put("size", size);
        return params;
    }

    /**
     * 依 {@code ?} 的出現順序解析每個 bind parameter 的實際值。
     *
     * <p>{@code <foreach>} 的元素存於 BoundSql 的 additional parameters，其餘由參數 Map 解析——
     * 與 MyBatis {@code DefaultParameterHandler} 的取值規則相同。</p>
     *
     * @param sql 渲染後的 BoundSql
     * @return 依序排列的參數值
     */
    private List<Object> parameterValues(BoundSql sql) {
        MetaObject params = configuration.newMetaObject(sql.getParameterObject());
        return sql.getParameterMappings().stream()
                .map(ParameterMapping::getProperty)
                .map(property -> sql.hasAdditionalParameter(property)
                        ? sql.getAdditionalParameter(property)
                        : params.getValue(property))
                .toList();
    }

    /**
     * 將連續空白壓成單一空白，使比對不受字串串接時的換行與縮排影響。
     *
     * @param sql 原始 SQL
     * @return 正規化後的 SQL
     */
    private static String normalize(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
