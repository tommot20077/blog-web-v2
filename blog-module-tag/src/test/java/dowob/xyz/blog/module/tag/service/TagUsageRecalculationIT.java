package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.event.ArticleArchivedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleDeletedEvent;
import dowob.xyz.blog.infrastructure.event.ArticlePublishedEvent;
import dowob.xyz.blog.infrastructure.event.ArticleTagEvent;
import dowob.xyz.blog.infrastructure.event.ArticleUpdatedEvent;
import dowob.xyz.blog.module.tag.consumer.TagArticleDeletedConsumer;
import dowob.xyz.blog.module.tag.consumer.TagArticleLifecycleConsumer;
import dowob.xyz.blog.module.tag.consumer.TagUsageConsumer;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * 標籤使用計數重算整合測試（DATA-01／RACE-07，真實 Postgres＋Redis；已發布判定以 Mock 的
 * {@code ArticleFacade.filterPublishedUuids} 模擬，owner 在 article 模組）。
 *
 * <p>驗證決策 §6.3：{@code usage_count} = 已發布文章數；發布／下架／刪除事件都會讓受影響標籤重算；
 * 同一標籤的併發重算會被列鎖序列化，不會有後到的舊快照覆寫較新的結果。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("標籤使用計數重算 整合測試")
class TagUsageRecalculationIT extends TagCleanupITSupport {

    @Autowired private TagUsageRecalculator recalculator;
    @Autowired private TagUsageConsumer usageConsumer;
    @Autowired private TagArticleLifecycleConsumer lifecycleConsumer;
    @Autowired private TagArticleDeletedConsumer deletedConsumer;
    @Autowired private StringRedisTemplate redis;

    /** 目前「已發布」的文章（facade 只回傳輸入中屬於此集合者） */
    private final Set<UUID> publishedArticles = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUpFacadeAndRedis() {
        publishedArticles.clear();
        reset(articleFacade);
        when(articleFacade.filterPublishedUuids(anyCollection())).thenAnswer(inv -> {
            Set<UUID> result = new HashSet<>(inv.<Collection<UUID>>getArgument(0));
            result.retainAll(publishedArticles);
            return result;
        });
        redis.delete(RedisKeyConstant.TAG_HOT_KEY);
    }

    private UUID publishedArticle() {
        UUID uuid = UUID.randomUUID();
        publishedArticles.add(uuid);
        return uuid;
    }

    private Double hotScore(UUID tagId) {
        return redis.opsForZSet().score(RedisKeyConstant.TAG_HOT_KEY, tagId.toString());
    }

    @Test
    @DisplayName("一個標籤 3 篇文章（2 已發布、1 草稿）：重算後 usage_count=2，Redis 熱門分數=2")
    void recalculate_threeArticlesTwoPublished_usageCountIsTwo() {
        UUID tag = insertTag("java", 0);
        UUID published1 = publishedArticle();
        UUID published2 = publishedArticle();
        UUID draft = UUID.randomUUID();
        insertArticleTag(published1, tag);
        insertArticleTag(published2, tag);
        insertArticleTag(draft, tag);

        int corrected = recalculator.recalculate(List.of(tag));

        assertThat(corrected).isEqualTo(1);
        assertThat(usageCountOf(tag)).isEqualTo(2);
        assertThat(hotScore(tag)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("標籤事件重送兩次：結果仍是 2（不會像舊的 +1 一樣灌水到 4）")
    void handleArticleTagged_redelivered_countStaysTwo() {
        UUID tag = insertTag("java", 0);
        UUID a = publishedArticle();
        UUID b = publishedArticle();
        insertArticleTag(a, tag);
        insertArticleTag(b, tag);

        usageConsumer.handleArticleTagged(new ArticleTagEvent(UUID.randomUUID(), a, List.of(tag)));
        usageConsumer.handleArticleTagged(new ArticleTagEvent(UUID.randomUUID(), a, List.of(tag)));

        assertThat(usageCountOf(tag)).isEqualTo(2);
        assertThat(hotScore(tag)).isEqualTo(2.0);
    }

    @Test
    @DisplayName("一篇已發布改為下架 → 下架事件 → 重算後 usage_count=1（舊實作只增不減）")
    void archivedEvent_decrementsPublishedCount() {
        UUID tag = insertTag("java", 2);
        UUID keep = publishedArticle();
        UUID archived = publishedArticle();
        insertArticleTag(keep, tag);
        insertArticleTag(archived, tag);
        publishedArticles.remove(archived);

        lifecycleConsumer.onArticleArchived(new ArticleArchivedEvent(UUID.randomUUID(), archived, Instant.now()));

        assertThat(usageCountOf(tag)).isEqualTo(1);
        assertThat(hotScore(tag)).isEqualTo(1.0);
        assertThat(countArticleTags(archived)).as("下架不刪關聯列").isEqualTo(1);
    }

    @Test
    @DisplayName("一篇已發布被刪除 → 刪除事件 → 關聯列被刪、usage_count 重算為 1")
    void deletedEvent_removesLinksAndRecalculates() {
        UUID tag = insertTag("java", 2);
        UUID keep = publishedArticle();
        UUID deleted = publishedArticle();
        insertArticleTag(keep, tag);
        insertArticleTag(deleted, tag);
        publishedArticles.remove(deleted);

        deletedConsumer.onArticleDeleted(new ArticleDeletedEvent(UUID.randomUUID(), deleted, List.of(),
                List.of(tag), Instant.now(), null, null));

        assertThat(countArticleTags(deleted)).isZero();
        assertThat(usageCountOf(tag)).isEqualTo(1);
        assertThat(hotScore(tag)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("草稿發布（發布事件／PUT 直轉 PUBLISHED 的更新事件）→ usage_count 增加")
    void publishedAndUpdatedEvents_incrementPublishedCount() {
        UUID tag = insertTag("java", 0);
        UUID viaPublish = UUID.randomUUID();
        UUID viaPut = UUID.randomUUID();
        insertArticleTag(viaPublish, tag);
        insertArticleTag(viaPut, tag);

        publishedArticles.add(viaPublish);
        lifecycleConsumer.onArticlePublished(new ArticlePublishedEvent(UUID.randomUUID(), viaPublish, null, "t",
                LocalDateTime.now(), "s", "sum", "text", "nick", List.of()));
        assertThat(usageCountOf(tag)).isEqualTo(1);

        publishedArticles.add(viaPut);
        lifecycleConsumer.onArticleUpdated(new ArticleUpdatedEvent(UUID.randomUUID(), viaPut, null, "t",
                LocalDateTime.now(), "s", "sum", "text", "nick", List.of()));
        assertThat(usageCountOf(tag)).isEqualTo(2);
    }

    @Test
    @DisplayName("RACE-07：同一標籤併發重算會被列鎖序列化——後到者拿到鎖後才讀取，不會被先到者的舊快照覆寫")
    void recalculate_concurrentOnSameTag_laterRunIsNotOverwrittenByStaleSnapshot()
            throws InterruptedException, ExecutionException, TimeoutException {
        UUID tag = insertTag("java", 7);
        UUID article = UUID.randomUUID();
        insertArticleTag(article, tag);

        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        when(articleFacade.filterPublishedUuids(anyCollection())).thenAnswer(inv -> {
            Set<UUID> snapshot = new HashSet<>(inv.<Collection<UUID>>getArgument(0));
            snapshot.retainAll(publishedArticles);
            if (calls.incrementAndGet() == 1) {
                /* 第一個重算：在持有列鎖期間讀到舊狀態（尚未發布），然後被卡住 */
                firstInside.countDown();
                if (!releaseFirst.await(20, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("測試逾時");
                }
            }
            return snapshot;
        });

        CompletableFuture<Integer> first = CompletableFuture.supplyAsync(() -> recalculator.recalculate(List.of(tag)));
        assertThat(firstInside.await(20, TimeUnit.SECONDS)).isTrue();

        /* 第一個重算讀完舊狀態之後，文章才發布；第二個重算（對應這次發布的事件）此時開始 */
        publishedArticles.add(article);
        CompletableFuture<Integer> second = CompletableFuture.supplyAsync(() -> recalculator.recalculate(List.of(tag)));
        Thread.sleep(800);
        assertThat(second.isDone()).as("第二個重算必須等第一個放開列鎖").isFalse();
        assertThat(calls.get()).as("第二個重算在拿到鎖之前不得開始讀取").isEqualTo(1);

        releaseFirst.countDown();
        first.get(20, TimeUnit.SECONDS);
        second.get(20, TimeUnit.SECONDS);

        assertThat(usageCountOf(tag)).as("最終以較新的狀態為準").isEqualTo(1);
        assertThat(hotScore(tag)).isEqualTo(1.0);
    }
}
