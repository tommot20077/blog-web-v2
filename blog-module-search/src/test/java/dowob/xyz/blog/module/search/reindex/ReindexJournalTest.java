package dowob.xyz.blog.module.search.reindex;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
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
 * <p>Redis 的實際語意（Lua 只在進行中時寫入、只有持有者能續期／取出／結束、SPOP 取出即移除）由 {@code ReindexJournalIT}
 * 以真 Redis 驗證；這裡鎖定 key、參數順序與 TTL。審查 M2 起進行中標記的值是 jobId，續期／取出／結束都先比對 jobId。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@DisplayName("ReindexJournal 單元測試")
class ReindexJournalTest {

    private static final UUID JOB = UUID.fromString("3f2504e0-4f89-41d3-9a0c-0305e82c3301");
    private static final String TTL_SECONDS = String.valueOf(ReindexJournal.ACTIVE_TTL.toSeconds());

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ReindexJournal journal;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        journal = new ReindexJournal(redis);
    }

    @Test
    @DisplayName("begin：先清掉上次殘留的變動集合，再設定進行中標記（值為 jobId、附 TTL）——順序顛倒會刪掉剛記下的變動")
    void begin_clearsDirtySetsBeforeMarkingActiveWithJobId() {
        journal.begin(JOB);

        InOrder order = inOrder(redis, valueOps);
        order.verify(redis).delete(List.of(RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY,
                RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY));
        order.verify(valueOps).set(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, JOB.toString(), ReindexJournal.ACTIVE_TTL);
    }

    @Test
    @DisplayName("keepAlive：Lua compare-and-expire（標記仍是本作業才續期）；回 1 為 true、回 0（被別的作業接手或已到期）為 false")
    void keepAlive_comparesJobIdBeforeExpire() {
        when(redis.execute(any(RedisScript.class), eq(List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY)),
                eq(JOB.toString()), eq(TTL_SECONDS))).thenReturn(1L, 0L);

        assertThat(journal.keepAlive(JOB)).isTrue();
        assertThat(journal.keepAlive(JOB)).isFalse();
    }

    @Test
    @DisplayName("end：Lua compare-and-delete，只有標記仍是本作業時才刪標記與兩個集合")
    void end_comparesJobIdBeforeDeleting() {
        journal.end(JOB);

        verify(redis).execute(any(RedisScript.class), eq(List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY,
                RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY, RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY)),
                eq(JOB.toString()));
        verify(redis, org.mockito.Mockito.never()).delete(anyList());
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
    @DisplayName("drainArticles／drainAuthors：Lua 比對 jobId 後續期並 SPOP count；沒有資料時回空清單")
    void drain_isOwnerCheckedAndExtendsTtl() {
        when(redis.execute(any(RedisScript.class),
                eq(List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, RedisKeyConstant.SEARCH_REINDEX_DIRTY_ARTICLES_KEY)),
                eq(JOB.toString()), eq("200"), eq(TTL_SECONDS))).thenReturn(List.of("a", "b"));
        when(redis.execute(any(RedisScript.class),
                eq(List.of(RedisKeyConstant.SEARCH_REINDEX_ACTIVE_KEY, RedisKeyConstant.SEARCH_REINDEX_DIRTY_AUTHORS_KEY)),
                eq(JOB.toString()), eq("200"), eq(TTL_SECONDS))).thenReturn(null);

        assertThat(journal.drainArticles(JOB, 200)).containsExactly("a", "b");
        assertThat(journal.drainAuthors(JOB, 200)).isEmpty();
    }
}
