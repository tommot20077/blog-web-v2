package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.module.article.model.ArticleArchiveRow;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleMapper#findArchiveRows()} 對真實 PostgreSQL 的整合測試（PERF-04）。
 *
 * <p>驗證：只回已發布、依發布時間新到舊（同刻以 id 由新到舊、NULL 墊底）、回傳列確實映射四個欄位；
 * 並以 EXPLAIN 證明查詢走既有的 partial index {@code idx_articles_published_latest}（V22）而不需要 Sort。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleMapper.findArchiveRows 整合測試")
class ArticleArchiveMapperIT extends ArticleSeriesITSupport {

    @Autowired
    private ArticleMapper mapper;

    private UUID insert(String status, String title, LocalDateTime publishedAt, String contentMd) {
        UUID uuid = UUID.randomUUID();
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, content_html, status, published_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                uuid, UUID.randomUUID(), title, "slug-" + uuid, contentMd, "<p>" + contentMd + "</p>", status,
                publishedAt == null ? null : Timestamp.valueOf(publishedAt));
        return uuid;
    }

    @Test
    @DisplayName("只回 PUBLISHED（DRAFT／PENDING_REVIEW／REJECTED／ARCHIVED 全排除），依 published_at 由新到舊")
    void findArchiveRows_onlyPublished_newestFirst() {
        UUID older = insert("PUBLISHED", "舊", LocalDateTime.of(2024, 1, 1, 0, 0), "x");
        UUID newer = insert("PUBLISHED", "新", LocalDateTime.of(2026, 1, 1, 0, 0), "x");
        for (String other : List.of("DRAFT", "PENDING_REVIEW", "REJECTED", "ARCHIVED")) {
            insert(other, "非公開-" + other, LocalDateTime.of(2025, 1, 1, 0, 0), "x");
        }

        List<ArticleArchiveRow> rows = mapper.findArchiveRows();

        assertThat(rows).extracting(ArticleArchiveRow::getUuid).containsExactly(newer, older);
    }

    @Test
    @DisplayName("映射 uuid／title／slug／publishedAt 四個欄位")
    void findArchiveRows_mapsAllFourColumns() {
        LocalDateTime at = LocalDateTime.of(2026, 3, 4, 5, 6, 7);
        UUID uuid = insert("PUBLISHED", "標題", at, "x");

        ArticleArchiveRow row = mapper.findArchiveRows().get(0);

        assertThat(row.getUuid()).isEqualTo(uuid);
        assertThat(row.getTitle()).isEqualTo("標題");
        assertThat(row.getSlug()).isEqualTo("slug-" + uuid);
        assertThat(row.getPublishedAt()).isEqualTo(at);
    }

    @Test
    @DisplayName("同一發布時間以 id 由新到舊（輸出穩定、可重現）；published_at 為 NULL 者墊底")
    void findArchiveRows_tieBrokenById_nullsLast() {
        LocalDateTime same = LocalDateTime.of(2026, 1, 1, 0, 0);
        UUID first = insert("PUBLISHED", "先插入", same, "x");
        UUID second = insert("PUBLISHED", "後插入", same, "x");
        UUID noDate = insert("PUBLISHED", "無發布時間", null, "x");

        List<ArticleArchiveRow> rows = mapper.findArchiveRows();

        assertThat(rows).extracting(ArticleArchiveRow::getUuid).containsExactly(second, first, noDate);
    }

    @Test
    @DisplayName("無已發布文章 → 空清單")
    void findArchiveRows_noPublished_isEmpty() {
        insert("DRAFT", "草稿", null, "x");

        assertThat(mapper.findArchiveRows()).isEmpty();
    }

    @Test
    @DisplayName("EXPLAIN：關閉 seqscan 後走 idx_articles_published_latest，計畫中沒有 Sort 節點")
    void explain_usesPublishedLatestIndex_withoutSort() throws Exception {
        insert("PUBLISHED", "A", LocalDateTime.of(2026, 1, 1, 0, 0), "x");

        List<String> plan = explain(archiveSql());

        assertThat(String.join("\n", plan)).contains("idx_articles_published_latest");
        assertThat(String.join("\n", plan)).doesNotContain("Sort");
    }

    @Test
    @DisplayName("反例：沒有 NULLS LAST／id 的舊寫法（ORDER BY published_at DESC）無法使用該索引的順序，計畫必含 Sort——這就是排序子句必須與索引逐字一致的原因")
    void explain_legacyOrderByWithoutNullsLast_needsSort() throws Exception {
        insert("PUBLISHED", "A", LocalDateTime.of(2026, 1, 1, 0, 0), "x");

        List<String> plan = explain(
                "SELECT uuid, title, slug, published_at FROM articles WHERE status = 'PUBLISHED' ORDER BY published_at DESC");

        assertThat(String.join("\n", plan)).contains("Sort");
    }

    private String archiveSql() throws NoSuchMethodException {
        return String.join(" ", ArticleMapper.class.getMethod("findArchiveRows").getAnnotation(Select.class).value());
    }

    /**
     * 在單一連線、交易內以 {@code SET LOCAL enable_seqscan = off}、{@code enable_sort = off} 取得 EXPLAIN 計畫（測試資料列數太少，
     * 不關閉 seqscan 時 planner 本就會選全表掃描，無法驗證索引可用性；關閉 sort 讓結果不受過時統計影響），結束即 rollback，不污染連線池。
     */
    private List<String> explain(String sql) {
        return jdbc.execute((java.sql.Connection con) -> {
            boolean auto = con.getAutoCommit();
            con.setAutoCommit(false);
            try (Statement st = con.createStatement()) {
                st.execute("SET LOCAL enable_seqscan = off");
                // 計畫不可受統計值影響：autovacuum 可能在別的測試資料上 analyze，留下過時統計，planner 便改選
                // Bitmap Heap Scan（不保序）＋ Sort（CI 曾因此紅）。關閉 sort 後，若索引能提供順序就不會出現 Sort；
                // 若索引無法提供順序（例如排序子句與索引不一致），Sort 仍無可避免地出現，斷言語意不變。
                st.execute("SET LOCAL enable_sort = off");
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
