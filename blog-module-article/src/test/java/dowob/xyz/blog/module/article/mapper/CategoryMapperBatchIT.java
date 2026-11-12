package dowob.xyz.blog.module.article.mapper;

import dowob.xyz.blog.module.article.support.ArticleSeriesITSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文章-分類關聯批次 SQL 的真實 PostgreSQL 驗證（PERF-27）：批次新增、只刪被移除的、未變動列的 {@code xmin} 不變。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("CategoryMapper 文章-分類關聯批次寫入 IT（PERF-27）")
class CategoryMapperBatchIT extends ArticleSeriesITSupport {

    @Autowired private CategoryMapper mapper;

    @AfterEach
    void cleanCategories() {
        jdbc.update("DELETE FROM article_categories");
        jdbc.update("DELETE FROM categories");
    }

    private long insertCategory(String slug) {
        return jdbc.queryForObject(
                "INSERT INTO categories (name, slug) VALUES (?, ?) RETURNING id", Long.class, slug, slug);
    }

    private long articleId() {
        UUID uuid = insertArticle(null, null, "DRAFT");
        return jdbc.queryForObject("SELECT id FROM articles WHERE uuid = ?", Long.class, uuid);
    }

    private Map<Long, Long> xminByCategory(long articleId) {
        Map<Long, Long> result = new HashMap<>();
        jdbc.query("SELECT category_id, xmin::text::bigint AS x FROM article_categories WHERE article_id = ?",
                rs -> {
                    result.put(rs.getLong("category_id"), rs.getLong("x"));
                }, articleId);
        return result;
    }

    @Test
    @DisplayName("insertArticleCategories：單一語句寫入多筆；findCategoryIdsByArticleId 讀得回")
    void insertArticleCategories_writesAllInOneStatement() {
        long article = articleId();
        long a = insertCategory("cat-a");
        long b = insertCategory("cat-b");
        long c = insertCategory("cat-c");

        mapper.insertArticleCategories(article, List.of(a, b, c));

        assertThat(mapper.findCategoryIdsByArticleId(article)).containsExactlyInAnyOrder(a, b, c);
    }

    @Test
    @DisplayName("deleteArticleCategories：只刪指定分類，其餘列的 xmin 不變（未被刪除重插）")
    void deleteArticleCategories_onlyRemovesGivenOnes_othersUntouched() {
        long article = articleId();
        long a = insertCategory("cat-a");
        long b = insertCategory("cat-b");
        long c = insertCategory("cat-c");
        mapper.insertArticleCategories(article, List.of(a, b, c));
        Map<Long, Long> before = xminByCategory(article);

        mapper.deleteArticleCategories(article, List.of(c));
        mapper.insertArticleCategories(article, List.of(c));

        Map<Long, Long> after = xminByCategory(article);
        assertThat(after.keySet()).containsExactlyInAnyOrder(a, b, c);
        assertThat(after.get(a)).isEqualTo(before.get(a));
        assertThat(after.get(b)).isEqualTo(before.get(b));
        assertThat(after.get(c)).as("被刪除重插的 C 拿到新的 xmin").isNotEqualTo(before.get(c));
    }

    @Test
    @DisplayName("deleteArticleCategories 不影響其他文章的同分類關聯")
    void deleteArticleCategories_scopedToArticle() {
        long article = articleId();
        long other = articleId();
        long a = insertCategory("cat-a");
        mapper.insertArticleCategories(article, List.of(a));
        mapper.insertArticleCategories(other, List.of(a));

        mapper.deleteArticleCategories(article, List.of(a));

        assertThat(mapper.findCategoryIdsByArticleId(article)).isEmpty();
        assertThat(mapper.findCategoryIdsByArticleId(other)).containsExactly(a);
    }
}
