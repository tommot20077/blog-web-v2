package dowob.xyz.blog.module.comment.mapper;

import dowob.xyz.blog.common.api.enums.ArticleStatus;
import dowob.xyz.blog.module.article.model.Article;
import dowob.xyz.blog.module.comment.support.CommentCleanupITSupport;
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
        List<Article> articles = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Article article = createArticle(ArticleStatus.PUBLISHED);
            articles.add(article);
            createComment(article, null);
        }
        createComment(articles.get(0), null); // 同一篇兩則留言，結果只能出現一次
        List<UUID> expected = articles.stream().map(Article::getUuid).sorted(PG_UUID_ORDER).toList();

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
        createLike(createComment(a, null).getId(), 1L);
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
