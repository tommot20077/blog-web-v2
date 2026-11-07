package dowob.xyz.blog.module.article.facade;

import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.infrastructure.facade.ArticleIndexData;
import dowob.xyz.blog.module.article.mapper.ArticleMapper;
import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Method;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ES 全量重建的分批讀取（真實 PostgreSQL，PERF-01）。
 *
 * <p>鎖定：keyset 分頁走完所有 PUBLISHED 文章、不重複不遺漏、不含其他狀態；每篇的標籤由每批一次的批次查詢正確分組；
 * 增量補齊用的 {@code findPublishedForIndexByUuids} 只回傳仍為 PUBLISHED 者；keyset 查詢依 {@code articles_uuid_key}
 * 順序讀取、不需要 Sort（每批成本與批次大小成正比，而不是與已讀過的列數成正比）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ES 全量重建分批讀取 IT（keyset ＋ 批次標籤）")
class ArticleIndexBatchReadIT extends ArticleSeriesITSupport {

    @Autowired private ArticleFacade articleFacade;

    /** 本類別建立的標籤（articles 由基底類別清空，標籤關聯由這裡清） */
    @AfterEach
    void cleanUpTags() {
        jdbc.update("DELETE FROM article_tags");
        jdbc.update("DELETE FROM tags WHERE slug LIKE 'w7f-%'");
    }

    private UUID insertTag(String slug) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO tags (id, name, slug) VALUES (?, ?, ?)", id, slug.toUpperCase(), slug);
        return id;
    }

    private void tag(UUID articleUuid, UUID tagId) {
        jdbc.update("INSERT INTO article_tags (article_id, tag_id) VALUES (?, ?)", articleUuid, tagId);
    }

    private static String sqlOf(String method) {
        for (Method m : ArticleMapper.class.getMethods()) {
            if (m.getName().equals(method) && m.getAnnotation(Select.class) != null) {
                return String.join(" ", m.getAnnotation(Select.class).value()).replaceAll("\\s+", " ");
            }
        }
        throw new IllegalArgumentException(method);
    }

    @Test
    @DisplayName("keyset 分頁（每批 2 篇）走完全部 PUBLISHED 文章：不重複、不遺漏、不含草稿／待審／下架")
    void keysetPaging_readsEveryPublishedArticleExactlyOnce() {
        Set<UUID> published = new HashSet<>();
        for (int i = 0; i < 7; i++) {
            published.add(insertArticle(null, null, "PUBLISHED"));
        }
        insertArticle(null, null, "DRAFT");
        insertArticle(null, null, "PENDING_REVIEW");
        insertArticle(null, null, "ARCHIVED");

        List<UUID> seen = new ArrayList<>();
        UUID cursor = null;
        int batches = 0;
        while (true) {
            List<ArticleIndexData> page = articleFacade.findPublishedForIndexAfter(cursor, 2);
            batches++;
            page.forEach(d -> seen.add(d.articleUuid()));
            if (page.size() < 2) {
                break;
            }
            cursor = page.get(page.size() - 1).articleUuid();
        }

        assertThat(seen).hasSize(7).doesNotHaveDuplicates();
        assertThat(new HashSet<>(seen)).isEqualTo(published);
        assertThat(batches).isEqualTo(4);
        assertThat(articleFacade.countPublished()).isEqualTo(7L);
    }

    @Test
    @DisplayName("每篇文章的標籤由批次查詢正確分組（無標籤者為空清單），內文取自 content_md 並去除 Markdown")
    void batch_groupsTagsPerArticle_andStripsContent() {
        UUID withTwoTags = insertArticle(null, null, "PUBLISHED");
        UUID withoutTags = insertArticle(null, null, "PUBLISHED");
        jdbc.update("UPDATE articles SET content_md = '# 標題\n\n**只有正文才有的詞** kiwi-31' WHERE uuid = ?", withTwoTags);
        UUID java = insertTag("w7f-java");
        UUID spring = insertTag("w7f-spring");
        tag(withTwoTags, java);
        tag(withTwoTags, spring);

        List<ArticleIndexData> page = articleFacade.findPublishedForIndexAfter(null, 10);

        ArticleIndexData tagged = page.stream().filter(d -> d.articleUuid().equals(withTwoTags)).findFirst().orElseThrow();
        ArticleIndexData untagged = page.stream().filter(d -> d.articleUuid().equals(withoutTags)).findFirst().orElseThrow();
        assertThat(tagged.tags()).extracting(ArticleIndexData.TagData::slug)
                .containsExactlyInAnyOrder("w7f-java", "w7f-spring");
        assertThat(untagged.tags()).isEmpty();
        assertThat(tagged.contentText()).isEqualTo("標題 只有正文才有的詞 kiwi-31");
        assertThat(tagged.title()).isEqualTo("t-" + withTwoTags);
    }

    @Test
    @DisplayName("findPublishedForIndexByUuids：只回傳仍為 PUBLISHED 者；已下架與不存在的 uuid 不回傳")
    void byUuids_returnsOnlyStillPublished() {
        UUID published = insertArticle(null, null, "PUBLISHED");
        UUID archived = insertArticle(null, null, "ARCHIVED");
        UUID missing = UUID.randomUUID();

        List<ArticleIndexData> result = articleFacade.findPublishedForIndexByUuids(List.of(published, archived, missing));

        assertThat(result).extracting(ArticleIndexData::articleUuid).containsExactly(published);
    }

    /**
     * 只有幾列時 planner 會選 partial index {@code idx_articles_published_latest}（已涵蓋 status 條件）再 Sort——
     * 對 1 列的表那確實最便宜，但不代表正式資料量的計畫。這裡灌入 3,000 篇並 ANALYZE，讓統計接近真實分佈，
     * 再斷言 planner 選擇「依 uuid 索引順序讀、到 LIMIT 即停」的計畫。
     */
    @Test
    @DisplayName("資料量接近真實時（3,000 篇、已 ANALYZE），keyset 查詢依 articles_uuid_key 順序讀取，計畫中沒有 Sort")
    void keysetQuery_usesUuidIndexWithoutSort() {
        jdbc.update("INSERT INTO articles (uuid, author_uuid, title, slug, content_md, status, published_at) "
                + "SELECT uuid_generate_v4(), uuid_generate_v4(), 't' || g, 'w7f-explain-' || g, 'x', "
                + "CASE WHEN g % 10 = 0 THEN 'DRAFT' ELSE 'PUBLISHED' END, now() FROM generate_series(1, 3000) g");
        jdbc.execute("ANALYZE articles");
        String sql = sqlOf("findPublishedForIndexAfter")
                .replace("<script>", "").replace("</script>", "")
                .replaceAll("<if test='afterUuid != null'>(.*?)</if>", "$1")
                /* 固定游標在 uuid 空間最前端：隨機 uuid 落在高端時符合列太少，planner 會合理地改選 bitmap scan + Sort，測試因此 flaky */
                .replace("#{afterUuid}", "'00000000-0000-0000-0000-000000000001'")
                .replace("#{limit}", "200");

        String plan = String.join("\n", explain(sql));

        assertThat(plan).contains("articles_uuid_key");
        assertThat(plan).doesNotContain("Sort");
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
