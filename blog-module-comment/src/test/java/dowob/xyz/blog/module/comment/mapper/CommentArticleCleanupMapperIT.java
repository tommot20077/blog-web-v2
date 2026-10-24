package dowob.xyz.blog.module.comment.mapper;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.comment.support.CommentCleanupITSupport;
import dowob.xyz.blog.module.comment.support.SeededUsers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommentArticleCleanupMapper} 整合測試（真實 Postgres）：keyset 分頁的排序／去重／游標，以及批次刪除。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("CommentArticleCleanupMapper 整合測試")
class CommentArticleCleanupMapperIT extends CommentCleanupITSupport {

    /** PostgreSQL uuid 的排序：位元組由高到低、以無號數比較（與 {@link UUID#compareTo} 不同） */
    private static final Comparator<UUID> PG_UUID_ORDER = Comparator
            .comparingLong((UUID u) -> u.getMostSignificantBits() ^ Long.MIN_VALUE)
            .thenComparingLong(u -> u.getLeastSignificantBits() ^ Long.MIN_VALUE);

    @Autowired private CommentArticleCleanupMapper mapper;

    @Test
    @DisplayName("findDistinctArticleUuidsAfter：去重、依資料庫 uuid 順序分頁，游標不含自身，最後一頁不足 limit")
    void findDistinctArticleUuidsAfter_pagesDistinctInDatabaseOrder() {
        // 固定兩個極值 UUID：Java 的 UUID.compareTo（高低 64 位元各自以有號數比較）會把 ffff... 排在 0000...01 之前，
        // 而 PostgreSQL 的 uuid 以無號位元組比較，排在其後——兩者順序必然不同，才能驗出排序確實由資料庫決定。
        // 這兩個 UUID 沒有對應文章，以 insertOrphanComment 寫入（V25 起無 FK，可直接寫入）。
        UUID lowest = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID highest = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        assertThat(highest.compareTo(lowest)).as("前置條件：Java 排序與 PG 排序不同").isNegative();
        List<Article> articles = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            Article article = createArticle(ArticleStatus.PUBLISHED);
            articles.add(article);
            createComment(article, null);
        }
        createComment(articles.get(0), null); // 同一篇兩則留言，結果只能出現一次
        insertOrphanComment(lowest, null);
        insertOrphanComment(highest, null);
        List<UUID> expected = new ArrayList<>(articles.stream().map(Article::getUuid).toList());
        expected.add(lowest);
        expected.add(highest);
        expected.sort(PG_UUID_ORDER);
        assertThat(expected.get(0)).isEqualTo(lowest);
        assertThat(expected.get(expected.size() - 1)).isEqualTo(highest);

        List<String> page1 = mapper.findDistinctArticleUuidsAfter(null, 2);
        List<String> page2 = mapper.findDistinctArticleUuidsAfter(UUID.fromString(page1.get(1)), 2);
        List<String> page3 = mapper.findDistinctArticleUuidsAfter(UUID.fromString(page2.get(1)), 2);
        List<String> page4 = mapper.findDistinctArticleUuidsAfter(UUID.fromString(page3.get(0)), 2);

        assertThat(page1).hasSize(2);
        assertThat(page2).hasSize(2);
        assertThat(page3).as("最後一頁不足 limit").hasSize(1);
        assertThat(page4).as("游標已在最後一筆之後").isEmpty();
        List<String> all = new ArrayList<>(page1);
        all.addAll(page2);
        all.addAll(page3);
        assertThat(all.stream().map(UUID::fromString).toList()).containsExactlyElementsOf(expected);
    }

    @Test
    @DisplayName("deleteByArticleUuids：一次刪除多篇文章的留言與留言讚，其餘保留")
    void deleteByArticleUuids_deletesOnlyGivenArticles() {
        Article a = createArticle(ArticleStatus.PUBLISHED);
        Article b = createArticle(ArticleStatus.PUBLISHED);
        Article keep = createArticle(ArticleStatus.PUBLISHED);
        createLike(createComment(a, null).getId(), SeededUsers.USER_UUID);
        createComment(b, null);
        createComment(b, null);
        createComment(keep, null);

        int deleted = mapper.deleteByArticleUuids(List.of(a.getUuid(), b.getUuid()));

        assertThat(deleted).isEqualTo(3);
        assertThat(countComments(a.getUuid())).isZero();
        assertThat(countComments(b.getUuid())).isZero();
        assertThat(countComments(keep.getUuid())).isEqualTo(1);
        assertThat(countAllLikes()).isZero();
    }
}
