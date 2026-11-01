package dowob.xyz.blog.module.tag.facade;

import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「被移除的標籤」重算整合測試（真實 Postgres）：文章更新時 {@code TagFacade.syncArticleTags}／
 * {@code deleteArticleTags} 知道新舊差異，於外層（文章）交易 commit 後對被移除的標籤重算——
 * 事件只涵蓋目前掛的標籤，被移除的標籤沒有任何事件會帶它。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("被移除標籤的重算 整合測試")
class TagFacadeRemovedTagsRecalculationIT extends TagCleanupITSupport {

    @Autowired private TagFacade tagFacade;
    @Autowired private PlatformTransactionManager txManager;

    private final Set<UUID> publishedArticles = new HashSet<>();

    @BeforeEach
    void setUp() {
        publishedArticles.clear();
        reset(articleFacade);
        when(articleFacade.filterPublishedUuids(anyCollection())).thenAnswer(inv -> {
            Set<UUID> result = new HashSet<>(inv.<Collection<UUID>>getArgument(0));
            result.retainAll(publishedArticles);
            return result;
        });
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(txManager);
    }

    /** 已發布文章 X 掛 A、B，A、B 的計數各為 1 */
    private UUID[] publishedArticleWithTagsAB() {
        UUID a = insertTag("tag-a", 1);
        UUID b = insertTag("tag-b", 1);
        UUID article = UUID.randomUUID();
        publishedArticles.add(article);
        insertArticleTag(article, a);
        insertArticleTag(article, b);
        return new UUID[]{article, a, b};
    }

    @Test
    @DisplayName("已發布文章標籤 [A,B] → [A]：commit 後 B=0、A=1")
    void syncArticleTags_removingATag_recalculatesRemovedTagAfterCommit() {
        UUID[] ids = publishedArticleWithTagsAB();

        tx().executeWithoutResult(status -> tagFacade.syncArticleTags(ids[0], List.of(ids[1])));

        assertThat(usageCountOf(ids[2])).as("被移除的 B 重算為 0").isZero();
        assertThat(usageCountOf(ids[1])).isEqualTo(1);
        assertThat(countArticleTags(ids[0])).isEqualTo(1);
    }

    @Test
    @DisplayName("已發布文章標籤改成 []（deleteArticleTags）：commit 後 A=0、B=0")
    void deleteArticleTags_recalculatesAllFormerTagsAfterCommit() {
        UUID[] ids = publishedArticleWithTagsAB();

        tx().executeWithoutResult(status -> tagFacade.deleteArticleTags(ids[0]));

        assertThat(usageCountOf(ids[1])).isZero();
        assertThat(usageCountOf(ids[2])).isZero();
        assertThat(countArticleTags(ids[0])).isZero();
    }

    @Test
    @DisplayName("外層交易 rollback：不重算（計數與關聯列都維持原狀）")
    void rollback_doesNotRecalculate() {
        UUID[] ids = publishedArticleWithTagsAB();

        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            tagFacade.syncArticleTags(ids[0], List.of(ids[1]));
            throw new IllegalStateException("文章交易失敗");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countArticleTags(ids[0])).as("關聯列已 rollback").isEqualTo(2);
        assertThat(usageCountOf(ids[2])).isEqualTo(1);
        verify(articleFacade, never()).filterPublishedUuids(anyCollection());
    }

    @Test
    @DisplayName("commit 後重算失敗：只記 WARN，不影響已 commit 的文章交易（由每日校正兜底）")
    void recalculationFailureAfterCommit_doesNotFailTheArticleTransaction() {
        UUID[] ids = publishedArticleWithTagsAB();
        /* B 另掛一篇已發布文章，重算時才會詢問 article facade（沒有任何關聯的標籤直接得 0，不需詢問） */
        UUID other = UUID.randomUUID();
        publishedArticles.add(other);
        insertArticleTag(other, ids[2]);
        jdbc.update("UPDATE tags SET usage_count = 5 WHERE id = ?", ids[2]);
        when(articleFacade.filterPublishedUuids(anyCollection())).thenThrow(new RuntimeException("facade down"));

        tx().executeWithoutResult(status -> tagFacade.syncArticleTags(ids[0], List.of(ids[1])));

        assertThat(countArticleTags(ids[0])).as("標籤變更已 commit").isEqualTo(1);
        assertThat(usageCountOf(ids[2])).as("計數暫時維持舊值，等每日校正").isEqualTo(5);
    }
}
