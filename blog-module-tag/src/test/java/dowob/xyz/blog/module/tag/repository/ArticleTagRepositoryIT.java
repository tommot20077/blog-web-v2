package dowob.xyz.blog.module.tag.repository;

import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ArticleTagRepository} 刪除連動與校正用查詢的整合測試（真實 Postgres）。
 *
 * <p>keyset 分頁的排序必須由資料庫的 uuid 型別決定（見 {@code OrphanReconciliationJob#fetchReferencedUuids}），
 * 因此預期值一律由資料庫自己排序產生，而不是在 Java 端排序。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ArticleTagRepository 整合測試")
class ArticleTagRepositoryIT extends TagCleanupITSupport {

    @Autowired private ArticleTagRepository repository;

    private List<UUID> seedFiveArticlesWithTwoTagsEach() {
        UUID tagA = insertTag("java", 0);
        UUID tagB = insertTag("spring", 0);
        List<UUID> articles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            UUID article = UUID.randomUUID();
            articles.add(article);
            insertArticleTag(article, tagA);
            insertArticleTag(article, tagB);
        }
        return articles;
    }

    private List<UUID> dbSortedDistinctArticleIds() {
        return jdbc.query("SELECT DISTINCT article_id FROM article_tags ORDER BY article_id",
                (rs, i) -> rs.getObject(1, UUID.class));
    }

    @Test
    @DisplayName("deleteByArticleId：只刪該文章的關聯並回傳筆數；重複呼叫回傳 0")
    void deleteByArticleId_deletesOnlyThatArticleAndIsIdempotent() {
        List<UUID> articles = seedFiveArticlesWithTwoTagsEach();

        assertThat(repository.deleteByArticleId(articles.get(0))).isEqualTo(2);
        assertThat(repository.deleteByArticleId(articles.get(0))).isZero();

        assertThat(countArticleTags(articles.get(0))).isZero();
        assertThat(countArticleTags(articles.get(1))).isEqualTo(2);
        assertThat(countTags()).isEqualTo(2);
    }

    @Test
    @DisplayName("deleteByArticleIds：一次刪除多篇文章的關聯，其餘保留")
    void deleteByArticleIds_deletesAllGivenArticles() {
        List<UUID> articles = seedFiveArticlesWithTwoTagsEach();

        int deleted = repository.deleteByArticleIds(List.of(articles.get(0), articles.get(1), articles.get(2)));

        assertThat(deleted).isEqualTo(6);
        assertThat(countArticleTags(articles.get(0))).isZero();
        assertThat(countArticleTags(articles.get(2))).isZero();
        assertThat(countArticleTags(articles.get(3))).isEqualTo(2);
        assertThat(countArticleTags(articles.get(4))).isEqualTo(2);
    }

    @Test
    @DisplayName("findReferencedArticleIdsAfter：游標為 null 從頭開始，依資料庫的 uuid 順序、去重（每篇文章兩個標籤只出現一次）")
    void findReferencedArticleIdsAfter_nullCursor_returnsDistinctInDbOrder() {
        seedFiveArticlesWithTwoTagsEach();
        List<UUID> expected = dbSortedDistinctArticleIds();

        assertThat(repository.findReferencedArticleIdsAfter(null, 100)).containsExactlyElementsOf(expected);
        assertThat(repository.findReferencedArticleIdsAfter(null, 3)).containsExactlyElementsOf(expected.subList(0, 3));
    }

    /** 以游標逐頁走完全部，頁數有上限：超過上限（例如 keyset 漏掉 after 條件而永遠回傳同一頁）就讓測試失敗，而不是卡住。 */
    private List<UUID> pageThroughAll(int pageSize, int expectedTotal) {
        int maxPages = expectedTotal + 2;
        List<UUID> collected = new ArrayList<>();
        UUID cursor = null;
        for (int pages = 0; pages <= maxPages; pages++) {
            List<UUID> page = repository.findReferencedArticleIdsAfter(cursor, pageSize);
            if (page.isEmpty()) {
                return collected;
            }
            collected.addAll(page);
            cursor = page.get(page.size() - 1);
        }
        throw new AssertionError("分頁超過上限 " + maxPages + " 頁仍未結束（keyset 游標沒有前進？）已收集=" + collected);
    }

    @Test
    @DisplayName("findReferencedArticleIdsAfter：以上一頁最後一筆為游標（不含）續頁，直到沒有資料")
    void findReferencedArticleIdsAfter_cursorPagesThroughAllWithoutGapsOrDuplicates() {
        seedFiveArticlesWithTwoTagsEach();
        List<UUID> expected = dbSortedDistinctArticleIds();

        assertThat(pageThroughAll(2, expected.size())).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("位元組序邊界：00000000／7fffffff／80000000／ffffffff 依 PostgreSQL uuid 的無號位元組順序排列與分頁"
            + "（Java 的 UUID.compareTo 為有號比較，順序不同，不可在 Java 端排序）")
    void findReferencedArticleIdsAfter_unsignedByteOrderBoundaries() {
        UUID min = UUID.fromString("00000000-0000-0000-0000-000000000000");
        UUID justBelowHighBit = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID highBit = UUID.fromString("80000000-0000-0000-0000-000000000000");
        UUID max = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        UUID tag = insertTag("java", 0);
        // 刻意以與預期相反的順序寫入
        for (UUID article : List.of(max, highBit, justBelowHighBit, min)) {
            insertArticleTag(article, tag);
        }
        List<UUID> expected = List.of(min, justBelowHighBit, highBit, max);

        assertThat(repository.findReferencedArticleIdsAfter(null, 100)).containsExactlyElementsOf(expected);
        assertThat(repository.findReferencedArticleIdsAfter(justBelowHighBit, 100)).containsExactly(highBit, max);
        assertThat(repository.findReferencedArticleIdsAfter(highBit, 100)).containsExactly(max);
        assertThat(repository.findReferencedArticleIdsAfter(max, 100)).isEmpty();
        assertThat(pageThroughAll(1, expected.size())).containsExactlyElementsOf(expected);
    }
}
