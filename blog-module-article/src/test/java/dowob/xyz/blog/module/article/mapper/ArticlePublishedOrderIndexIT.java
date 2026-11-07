package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「依 published_at 由新到舊」的查詢必須能使用 V22 的 partial index（PERF-08）。
 *
 * <p>原本的兩條之一 {@code findAllPublished}（ES 全量索引）已由 keyset 分批的 {@code findPublishedForIndexAfter}
 * 取代（PERF-01，依 uuid 排序、走 {@code articles_uuid_key}），其計畫守衛移至 {@code ArticleIndexBatchReadIT}。</p>
 *
 * <p>{@code idx_articles_published_latest} 為 {@code (published_at DESC NULLS LAST, id DESC) WHERE status = 'PUBLISHED'}。
 * {@code ORDER BY published_at DESC} 預設 NULLS FIRST，與索引順序不符，planner 只能全掃再 Sort；
 * 補上 {@code NULLS LAST} 後即可依索引順序讀取。已發布文章的 published_at 恆有值，加 NULLS LAST 不改變結果。</p>
 *
 * <p>EXPLAIN 在單一連線內以 {@code SET LOCAL enable_seqscan = off} 取得計畫（測試資料列數太少，不關閉時 planner 本就選全表掃描），
 * 方法與 {@link ArticleArchiveMapperIT} 相同。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("已發布文章 published_at 排序走 partial index IT（PERF-08）")
class ArticlePublishedOrderIndexIT extends ArticleSeriesITSupport {

    private void insertPublished(String title, LocalDateTime at) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, published_at) "
                        + "VALUES (?, ?, ?, ?, 'x', 'PUBLISHED', ?)",
                uuid, UUID.randomUUID(), title, "slug-" + uuid, Timestamp.valueOf(at));
    }

    private static String sqlOf(Class<?> mapper, String method) {
        for (Method m : mapper.getMethods()) {
            if (m.getName().equals(method) && m.getAnnotation(Select.class) != null) {
                return String.join(" ", m.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");
            }
        }
        throw new IllegalArgumentException(method);
    }

    @Test
    @DisplayName("findRecentPublished（推薦補位）：計畫使用 idx_articles_published_latest 且無 Sort")
    void findRecentPublished_usesPublishedLatestIndex() {
        insertPublished("A", LocalDateTime.of(2026, 1, 1, 0, 0));
        String sql = sqlOf(ArticleRecommendMapper.class, "findRecentPublished")
                .replace("#{excludeUuid}", "'" + UUID.randomUUID() + "'")
                .replace("#{limit}", "10");

        String plan = String.join("\n", explain(sql));

        assertThat(plan).contains("idx_articles_published_latest");
        assertThat(plan).doesNotContain("Sort");
    }

    @Test
    @DisplayName("SQL 文字：查詢的排序明寫 published_at DESC NULLS LAST")
    void bothQueries_declareNullsLast() {
        assertThat(sqlOf(ArticleRecommendMapper.class, "findRecentPublished"))
                .contains("ORDER BY a.published_at DESC NULLS LAST");
    }

    private List<String> explain(String sql) {
        return jdbc.execute((java.sql.Connection con) -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.execute("SET LOCAL enable_seqscan = off");
                List<String> lines = new ArrayList<>();
                try (ResultSet rs = st.executeQuery("EXPLAIN " + sql)) {
                    while (rs.next()) {
                        lines.add(rs.getString(1));
                    }
                }
                return lines;
            } finally {
                con.rollback();
                con.setAutoCommit(auto);
            }
        });
    }
}
