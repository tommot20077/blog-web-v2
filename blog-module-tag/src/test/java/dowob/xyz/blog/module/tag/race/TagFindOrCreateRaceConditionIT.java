package dowob.xyz.blog.module.tag.race;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.event.TagInfo;
import dowob.xyz.blog.infrastructure.facade.TagFacade;
import dowob.xyz.blog.module.tag.model.Tag;
import dowob.xyz.blog.module.tag.service.TagNormalizationService;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RACE-08 併發整合測試（真實 Postgres、Redis；不 mock 任何約束）。
 *
 * <p>舊實作 {@code findOrCreate} 先 {@code findBySlug} 再 {@code save}：兩個交易同時建立同名／同 slug 標籤時，
 * 都讀到「不存在」，後到者撞 {@code tags_name_key}／{@code tags_slug_key}，PostgreSQL 把整個交易標為失敗——
 * 外層的 {@code createArticle} 交易整個 rollback，發文回 500。新實作以 {@code INSERT ... ON CONFLICT DO NOTHING}
 * 建立，衝突者以 slug 重讀既有標籤，不拋例外、不污染外層交易。</p>
 *
 * <p>每個案例各自包在 {@link TransactionTemplate} 內執行（等同 article 的發文交易）：若任何一次呼叫讓交易
 * 進入失敗狀態，commit 時會拋例外，測試就會失敗。{@link CyclicBarrier} 讓兩個執行緒同時出發並重複多輪；
 * 正確實作在任何交錯下結果都相同，因此斷言不會是 flaky 的來源，舊實作則會在多輪中至少落敗一次。</p>
 *
 * <p>已知殘餘風險（不在本測試範圍，亦非本次引入）：兩個交易<b>以相反順序</b>同時建立同一組「全新」多個標籤，
 * 會互相等待對方尚未提交的 INSERT 而死結（PostgreSQL 40P01，其中一個交易被中止）；舊實作同樣有此情形。
 * 需要在 {@code TagFacade.findOrCreateTags} 以固定順序建立才能根治，另案處理。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("RACE-08 併發建立標籤整合測試")
class TagFindOrCreateRaceConditionIT extends TagCleanupITSupport {

    /** 併發案例重複的輪數 */
    private static final int ROUNDS = 40;

    @Autowired private TagNormalizationService normalizationService;
    @Autowired private TagFacade tagFacade;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private StringRedisTemplate redis;

    /** 讓兩個執行緒同時執行 action（各取一個 index），回傳兩者的結果；任一執行緒拋例外會讓測試失敗。 */
    private <T> List<T> runConcurrently(IntFunction<Callable<T>> actionOfThread) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                Callable<T> action = actionOfThread.apply(i);
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return action.call();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private <T> T inTransaction(Callable<T> body) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                return body.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    @Test
    @DisplayName("兩個交易同時以同名建立標籤：都不拋例外、都 commit 成功，拿到同一個標籤，tags 只有 1 列")
    void findOrCreate_concurrentSameName_bothGetSameTagAndSingleRow() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String name = "race-same-" + round;

            List<Tag> tags = runConcurrently(i -> () -> inTransaction(() -> normalizationService.findOrCreate(name)));

            assertThat(tags.get(0).getId()).as("round %d：兩邊拿到同一個標籤", round).isEqualTo(tags.get(1).getId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags WHERE name = ?", Integer.class, name))
                    .as("round %d：tags 只 1 列", round).isEqualTo(1);
            assertThat(redis.opsForZSet().score(RedisKeyConstant.TAG_AUTOCOMPLETE_KEY, name))
                    .as("round %d：自動補全已寫入", round).isNotNull();
        }
    }

    @Test
    @DisplayName("兩個交易以不同名稱但同 slug（C++／C#）同時建立：不撞 tags_slug_key，兩邊拿到同一個標籤")
    void findOrCreate_concurrentDifferentNamesSameSlug_bothGetSameTag() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String suffix = "x" + round;
            String[] names = {suffix + "++", suffix + "#"};

            List<Tag> tags = runConcurrently(i -> () -> inTransaction(() -> normalizationService.findOrCreate(names[i])));

            assertThat(tags.get(0).getId()).as("round %d", round).isEqualTo(tags.get(1).getId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags WHERE slug = ?", Integer.class, suffix))
                    .as("round %d：同 slug 只 1 列", round).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("發文流程入口 TagFacade.findOrCreateTags：兩個交易同時帶著同一組標籤，不再讓外層交易失敗，各標籤只建立一次")
    void findOrCreateTags_concurrentSameTagList_neitherTransactionFails() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            List<String> names = List.of("race-fa-" + round, "race-fb-" + round, "race-fc-" + round);

            List<List<TagInfo>> results = runConcurrently(i -> () -> inTransaction(() -> tagFacade.findOrCreateTags(names)));

            assertThat(results.get(0)).as("round %d", round).containsExactlyElementsOf(results.get(1));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags WHERE name LIKE ?", Integer.class,
                    "race-f_-" + round)).as("round %d", round).isEqualTo(3);
        }
    }

    @Test
    @DisplayName("標籤已存在時（沒有競爭）：回傳既有標籤、不新增列、不重複寫 Redis 自動補全")
    void findOrCreate_existingTag_returnsItWithoutInserting() {
        UUID existing = insertTag("already-there", 7);

        Tag result = normalizationService.findOrCreate("Already-There");

        assertThat(result.getId()).isEqualTo(existing);
        assertThat(countTags()).isEqualTo(1);
        assertThat(usageCountOf(existing)).isEqualTo(7);
    }

    @Test
    @DisplayName("新標籤：以 usage_count = 0 建立，名稱與 slug 為正規化後的值，並寫入 Redis 自動補全")
    void findOrCreate_newTag_isInsertedWithDefaults() {
        Tag result = normalizationService.findOrCreate("  Fresh-Tag ");

        assertThat(result.getName()).isEqualTo("fresh-tag");
        assertThat(result.getSlug()).isEqualTo("fresh-tag");
        assertThat(usageCountOf(result.getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT created_at IS NOT NULL FROM tags WHERE id = ?", Boolean.class,
                result.getId())).isTrue();
        assertThat(redis.opsForZSet().score(RedisKeyConstant.TAG_AUTOCOMPLETE_KEY, "fresh-tag")).isNotNull();
    }
}
