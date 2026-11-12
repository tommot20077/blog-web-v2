package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.util.Set;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * {@link TagArticleDeletedConsumer} 整合測試（真實 Postgres）：文章刪除後其 {@code article_tags} 列被刪除，
 * 與原本 {@code article_id → articles(uuid) ON DELETE CASCADE} 的行為一致；{@code tags} 本身不動，
 * 但受影響標籤的 {@code usage_count} 會重算為「已發布文章數」（DATA-01）。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TagArticleDeletedConsumer 整合測試")
class TagArticleDeletedConsumerIT extends TagCleanupITSupport {
    @Autowired private TagArticleDeletedConsumer consumer;

    private static ArticleDeletedEvent event(UUID articleUuid) {
        return new ArticleDeletedEvent(UUID.randomUUID(), articleUuid, List.of(), List.of(), Instant.now(), null, null);
    }

    @Test
    @DisplayName("刪除事件：該文章的所有標籤關聯被刪除；其他文章的關聯與 tags 不動；受影響標籤 usage_count 重算；正常返回")
    void onArticleDeleted_deletesOnlyThatArticlesTagLinks() throws IOException {
        UUID tagA = insertTag("java", 3);
        UUID tagB = insertTag("spring", 5);
        UUID doomed = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insertArticleTag(doomed, tagA);
        insertArticleTag(doomed, tagB);
        insertArticleTag(other, tagA);
        /* other 是已發布文章；doomed 已刪除（不存在）。DATA-01：usage_count 重算為已發布文章數 */
        reset(articleFacade);
        when(articleFacade.filterPublishedUuids(anyCollection())).thenReturn(Set.of(other));

        consumer.onArticleDeleted(event(doomed));

        assertThat(countArticleTags(doomed)).as("被刪文章的關聯清空").isZero();
        assertThat(countArticleTags(other)).as("其他文章的關聯保留").isEqualTo(1);
        assertThat(countTags()).as("tags 本身不動").isEqualTo(2);
        assertThat(usageCountOf(tagA)).as("tagA 剩一篇已發布的 other → 重算為 1（原 3 為灌水值）").isEqualTo(1);
        assertThat(usageCountOf(tagB)).as("tagB 已無任何文章 → 重算為 0").isZero();
    }

    @Test
    @DisplayName("重複投遞同一事件（或文章本來就沒有標籤）：不出錯、不影響他人，仍正常返回")
    void onArticleDeleted_redelivery_isIdempotent() throws IOException {
        UUID tag = insertTag("java", 1);
        UUID doomed = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insertArticleTag(doomed, tag);
        insertArticleTag(other, tag);
        ArticleDeletedEvent same = event(doomed);

        consumer.onArticleDeleted(same);
        consumer.onArticleDeleted(same);
        consumer.onArticleDeleted(event(UUID.randomUUID()));

        assertThat(countArticleTags(doomed)).isZero();
        assertThat(countArticleTags(other)).isEqualTo(1);
    }

    @Test
    @DisplayName("舊 payload 缺 articleUuid：不刪任何資料，仍正常返回")
    void onArticleDeleted_missingArticleUuid_deletesNothingAndAcks() throws IOException {
        UUID tag = insertTag("java", 1);
        UUID someArticle = UUID.randomUUID();
        insertArticleTag(someArticle, tag);

        consumer.onArticleDeleted(event(null));

        assertThat(countArticleTags(someArticle)).isEqualTo(1);
    }
}
