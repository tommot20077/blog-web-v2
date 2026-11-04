package dowob.xyz.blog.module.tag.job;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * {@link TagUsageReconciliationJob} 整合測試（真實 Postgres＋Redis）：每日全量校正會把被人為改錯／
 * 被舊實作灌水的 {@code usage_count} 修回「已發布文章數」。
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("TagUsageReconciliationJob 整合測試")
class TagUsageReconciliationJobIT extends TagCleanupITSupport {

    @Autowired private TagUsageReconciliationJob job;
    @Autowired private StringRedisTemplate redis;

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
        redis.delete(RedisKeyConstant.getReconciliationLockKey("tag", "tags.usage_count"));
        redis.delete(RedisKeyConstant.TAG_HOT_KEY);
    }

    private UUID publishedArticle() {
        UUID uuid = UUID.randomUUID();
        publishedArticles.add(uuid);
        return uuid;
    }

    @Test
    @DisplayName("全量重算：灌水的、被改成負數的、沒有任何文章的計數都被修正；本來正確的不動")
    void run_fixesWrongCountsAndLeavesCorrectOnesAlone() {
        UUID inflated = insertTag("inflated", 99);
        UUID negative = insertTag("negative", -3);
        UUID orphanTag = insertTag("no-articles", 5);
        UUID correct = insertTag("correct", 1);
        UUID a1 = publishedArticle();
        UUID a2 = publishedArticle();
        UUID draft = UUID.randomUUID();
        insertArticleTag(a1, inflated);
        insertArticleTag(a2, inflated);
        insertArticleTag(draft, inflated);
        insertArticleTag(a1, negative);
        insertArticleTag(a1, correct);

        TagUsageReconciliationJob.Result result = job.run();

        assertThat(result.executed()).isTrue();
        assertThat(result.scanned()).isEqualTo(4);
        assertThat(result.corrected()).isEqualTo(3);
        assertThat(result.failedPages()).isZero();
        assertThat(usageCountOf(inflated)).isEqualTo(2);
        assertThat(usageCountOf(negative)).isEqualTo(1);
        assertThat(usageCountOf(orphanTag)).isZero();
        assertThat(usageCountOf(correct)).isEqualTo(1);
        assertThat(redis.opsForZSet().score(RedisKeyConstant.TAG_HOT_KEY, inflated.toString())).isEqualTo(2.0);
        assertThat(redis.opsForZSet().score(RedisKeyConstant.TAG_HOT_KEY, orphanTag.toString())).isEqualTo(0.0);
    }

    @Test
    @DisplayName("連續執行兩次：第二次不再有需要修正的標籤（重算本身冪等）")
    void run_twice_secondRunCorrectsNothing() {
        UUID tag = insertTag("java", 50);
        insertArticleTag(publishedArticle(), tag);

        job.run();
        TagUsageReconciliationJob.Result second = job.run();

        assertThat(second.corrected()).isZero();
        assertThat(usageCountOf(tag)).isEqualTo(1);
    }

    @Test
    @DisplayName("別的實例持有鎖時：本次跳過，計數不變")
    void run_lockHeldByAnotherInstance_skips() {
        UUID tag = insertTag("java", 50);
        redis.opsForValue().set(RedisKeyConstant.getReconciliationLockKey("tag", "tags.usage_count"), "other-instance");

        TagUsageReconciliationJob.Result result = job.run();

        assertThat(result.executed()).isFalse();
        assertThat(usageCountOf(tag)).isEqualTo(50);
    }
}
