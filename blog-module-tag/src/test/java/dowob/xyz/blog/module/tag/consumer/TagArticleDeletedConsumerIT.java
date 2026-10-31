package dowob.xyz.blog.module.tag.consumer;

import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * {@link TagArticleDeletedConsumer} 整合測試（真實 Postgres）：文章刪除後其 {@code article_tags} 列被刪除，
 * 與原本 {@code article_id → articles(uuid) ON DELETE CASCADE} 的行為一致；{@code tags} 本身與
 * {@code usage_count} 不動（原 CASCADE 亦不會動它，計數重算為 DATA-01 另案）。
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
    @DisplayName("刪除事件：該文章的所有標籤關聯被刪除；其他文章的關聯、tags 與 usage_count 不動；正常返回")
    void onArticleDeleted_deletesOnlyThatArticlesTagLinks() throws IOException {
        UUID tagA = insertTag("java", 3);
        UUID tagB = insertTag("spring", 5);
        UUID doomed = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insertArticleTag(doomed, tagA);
        insertArticleTag(doomed, tagB);
        insertArticleTag(other, tagA);

        consumer.onArticleDeleted(event(doomed));

        assertThat(countArticleTags(doomed)).as("被刪文章的關聯清空").isZero();
        assertThat(countArticleTags(other)).as("其他文章的關聯保留").isEqualTo(1);
        assertThat(countTags()).as("tags 本身不動").isEqualTo(2);
        assertThat(usageCountOf(tagA)).as("usage_count 不動（DATA-01 另案）").isEqualTo(3);
        assertThat(usageCountOf(tagB)).isEqualTo(5);
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
