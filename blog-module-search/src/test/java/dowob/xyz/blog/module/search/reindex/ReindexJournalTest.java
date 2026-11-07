package dowob.xyz.blog.module.search.reindex;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ReindexJournal} 單元測試：全量重建期間的增量變動紀錄（D1）。
 *
 * <p>Redis 的實際語意（Lua 只在進行中時寫入、SPOP 取出即移除）由 {@code ArticleIndexRebuildIT} 以真 Redis 驗證；
 * 這裡鎖定 key、順序與 TTL。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("ReindexJournal 單元測試")
class ReindexJournalTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private SetOperations<String, String> setOps;
    private ReindexJournal journal;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        setOps = mock(SetOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForSet()).thenReturn(setOps);
        journal = new ReindexJournal(redis);
    }

    @Test
    @DisplayName("begin：先清掉上次殘留的變動集合，再設定進行中標記（值為新實體索引、附 TTL）——順序顛倒會刪掉剛記下的變動")
    void begin_clearsDirtySetsBeforeMarkingActive() {
        journal.begin("blog_articles_v2");

        InOrder order = inOrder(redis, valueOps);
        order.verify(redis).delete(List.of(RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY,
                RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY));
        order.verify(valueOps).set(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, "blog_articles_v2", ReindexJournal.ACTIVE_TTL);
    }

    @Test
    @DisplayName("keepAlive：延長進行中標記的 TTL")
    void keepAlive_extendsActiveMarker() {
        journal.keepAlive();

        verify(redis).expire(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, ReindexJournal.ACTIVE_TTL);
    }

    @Test
    @DisplayName("end：先移除進行中標記，再刪除變動集合")
    void end_removesMarkerThenSets() {
        journal.end();

        verify(redis).delete(List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY,
                RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY, RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY));
    }

    @Test
    @DisplayName("recordArticle：以單一 Lua 原子判斷「進行中才寫入」，keys 為標記與文章變動集合")
    void recordArticle_usesAtomicScriptOnArticleSet() {
        String uuid = UUID.randomUUID().toString();

        journal.recordArticle(uuid);

        verify(redis).execute(any(RedisScript.class),
                eq(List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY)),
                eq(uuid));
    }

    @Test
    @DisplayName("recordAuthor：同一支 Lua，寫入作者變動集合")
    void recordAuthor_usesAtomicScriptOnAuthorSet() {
        UUID author = UUID.randomUUID();

        journal.recordAuthor(author);

        verify(redis).execute(any(RedisScript.class),
                eq(List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY)),
                eq(author.toString()));
    }

    @Test
    @DisplayName("Redis 故障時 record 例外原樣往外拋（由 MQ 容器重試），不吞掉而讓重建期間的變動靜默遺失")
    void record_redisFailure_propagates() {
        when(redis.execute(any(RedisScript.class), anyList(), any())).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> journal.recordArticle("x")).hasMessage("redis down");
    }

    @Test
    @DisplayName("drainArticles／drainAuthors：以 SPOP count 取出（取出即移除）；沒有資料時回空清單")
    void drain_popsUpToMax() {
        when(setOps.pop(RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY, 200)).thenReturn(List.of("a", "b"));
        when(setOps.pop(RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY, 200)).thenReturn(null);

        assertThat(journal.drainArticles(200)).containsExactly("a", "b");
        assertThat(journal.drainAuthors(200)).isEmpty();
    }
}
