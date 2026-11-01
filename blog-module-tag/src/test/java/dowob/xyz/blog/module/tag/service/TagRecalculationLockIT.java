package dowob.xyz.blog.module.tag.service;

import dowob.xyz.blog.module.tag.repository.TagRepository;
import dowob.xyz.blog.module.tag.support.TagCleanupITSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 重算鎖定查詢與文章儲存交易的死結回歸測試（真實 Postgres）。
 *
 * <p>{@code article_tags.tag_id} 的 FK 檢查會對 {@code tags} 列取 {@code FOR KEY SHARE}，
 * 若重算用 {@code FOR UPDATE} 鎖標籤列就會與之衝突：文章儲存交易依 tagNames 順序（例如先高後低）插入 article_tags，
 * 同時重算依 id 升冪鎖定，兩邊互等 → 死結，被中止的是使用者的文章儲存。重算只改非 key 欄位 usage_count，
 * 必須用 {@code FOR NO KEY UPDATE}（與 KEY SHARE 相容，重算彼此仍互斥）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@DisplayName("重算鎖定查詢 vs 文章儲存交易 死結回歸 整合測試")
class TagRecalculationLockIT extends TagCleanupITSupport {

    @Autowired private TagRepository tagRepository;
    @Autowired private PlatformTransactionManager txManager;

    @Test
    @DisplayName("交易 A 以高→低順序插入 article_tags、交易 B 同時鎖定 {低,高}：兩者都成功，不死結")
    void lockQuery_doesNotDeadlockWithArticleTagInsertsInReverseOrder() throws Exception {
        insertTag("lock-a", 0);
        insertTag("lock-b", 0);
        /* 以資料庫的 uuid 排序決定高低（重算鎖定依資料庫的 ORDER BY id） */
        List<UUID> ascending = jdbc.queryForList("SELECT id FROM tags ORDER BY id", UUID.class);
        UUID low = ascending.get(0);
        UUID high = ascending.get(1);
        UUID article = UUID.randomUUID();

        CountDownLatch highInserted = new CountDownLatch(1);
        CountDownLatch proceedWithLow = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> articleSave = pool.submit(() -> new TransactionTemplate(txManager).executeWithoutResult(status -> {
                insertArticleTag(article, high);
                highInserted.countDown();
                await(proceedWithLow);
                insertArticleTag(article, low);
            }));
            assertThat(highInserted.await(20, TimeUnit.SECONDS)).isTrue();

            Future<?> recalculationLock = pool.submit(() -> new TransactionTemplate(txManager)
                    .executeWithoutResult(status -> tagRepository.findAllByIdInForUpdate(List.of(low, high))));
            Thread.sleep(1500);
            proceedWithLow.countDown();

            assertThatCode(() -> articleSave.get(30, TimeUnit.SECONDS))
                    .as("文章儲存交易不得被死結中止").doesNotThrowAnyException();
            assertThatCode(() -> recalculationLock.get(30, TimeUnit.SECONDS))
                    .as("重算鎖定交易不得被死結中止").doesNotThrowAnyException();
        } finally {
            proceedWithLow.countDown();
            pool.shutdownNow();
        }
        assertThat(countArticleTags(article)).isEqualTo(2);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("測試逾時");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
