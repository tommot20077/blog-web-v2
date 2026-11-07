package dowob.xyz.blog.module.search.reindex;

import dowob.xyz.blog.common.constant.RedisKeyConstant;
import dowob.xyz.blog.infrastructure.facade.ArticleFacade;
import dowob.xyz.blog.module.search.model.dto.response.SearchReindexJobResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 搜尋索引全量重建的背景作業（D1／PERF-01）。
 *
 * <p>{@code POST /api/v1/admin/search/reindex} 只呼叫 {@link #start()}：取得分散式鎖、寫入 RUNNING 作業、把重建交給
 * 本類別<b>自有的單一執行緒</b>後立即返回（端點回 202）。不使用 HTTP 執行緒（原本同步跑完、沒有逾時保護），
 * 也不使用排程池（{@code spring.task.scheduling.pool.size=4}，被重建佔住會拖慢 flush 與每日校正）；
 * 刻意不註冊成 {@code Executor} bean——Spring Boot 的 {@code applicationTaskExecutor} 在容器內已有 {@code Executor}
 * bean 時會退讓，註冊一個單執行緒池會讓其他 {@code @Async} 等用途意外落到這裡。</p>
 *
 * <p>同一時間只能有一個重建：鎖在 Redis（多實例共用）；已有作業持有時 {@link #start()} 回傳進行中的作業，端點回 409。
 * 執行緒池為 1 條執行緒、佇列 1 格：佇列那一格只為了吸收「上一個作業已解鎖、執行緒尚未歸還」的瞬間；鎖保證不會更多。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Service
public class SearchReindexService implements DisposableBean {

    /** 背景執行緒名稱 */
    public static final String THREAD_NAME = "search-reindex";

    /** 錯誤摘要長度上限（寫進作業狀態，不放整串堆疊） */
    public static final int MAX_ERROR_LENGTH = 500;

    /** 應用程式關閉時等待背景作業收尾（刪除新索引、釋放鎖、寫入 FAILED）的時間（秒） */
    static final long SHUTDOWN_GRACE_SECONDS = 30L;

    /** 作業狀態與分散式鎖 */
    private final ReindexJobStore jobStore;

    /** 重建流程 */
    private final ArticleIndexRebuilder rebuilder;

    /** 文章 facade（進度分母） */
    private final ArticleFacade articleFacade;

    /** Redis（最後重建時間） */
    private final StringRedisTemplate redis;

    /** 背景執行緒池（單一執行緒） */
    private final ExecutorService executor;

    /** 時間來源 */
    private final Clock clock;

    /**
     * 建立服務（使用自有的單一執行緒池）。
     *
     * @param jobStore      作業狀態與分散式鎖
     * @param rebuilder     重建流程
     * @param articleFacade 文章 facade
     * @param redis         Redis
     */
    @Autowired
    public SearchReindexService(ReindexJobStore jobStore, ArticleIndexRebuilder rebuilder,
                                ArticleFacade articleFacade, StringRedisTemplate redis) {
        this(jobStore, rebuilder, articleFacade, redis, newExecutor(), Clock.systemDefaultZone());
    }

    /**
     * 測試用建構子：指定執行緒池與時鐘。
     *
     * @param jobStore      作業狀態與分散式鎖
     * @param rebuilder     重建流程
     * @param articleFacade 文章 facade
     * @param redis         Redis
     * @param executor      執行緒池
     * @param clock         時鐘
     */
    SearchReindexService(ReindexJobStore jobStore, ArticleIndexRebuilder rebuilder, ArticleFacade articleFacade,
                         StringRedisTemplate redis, ExecutorService executor, Clock clock) {
        this.jobStore = jobStore;
        this.rebuilder = rebuilder;
        this.articleFacade = articleFacade;
        this.redis = redis;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * 建立單一執行緒、佇列 1 格的執行緒池（daemon，不擋 JVM 結束）。
     *
     * @return 執行緒池
     */
    private static ExecutorService newExecutor() {
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(1), runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 觸發全量重建：取得鎖、寫入 RUNNING 作業、交給背景執行緒後立即返回。
     *
     * @return 新作業（{@code started=true}），或已有作業在跑時的進行中作業（{@code started=false}）
     * @throws java.util.concurrent.RejectedExecutionException 背景執行緒無法接受工作（已釋放鎖並標記 FAILED）
     */
    public ReindexStartResult start() {
        UUID jobId = UUID.randomUUID();
        if (!jobStore.tryLock(jobId)) {
            return ReindexStartResult.alreadyRunning(jobStore.current().orElse(null));
        }
        SearchReindexJobResponse job = SearchReindexJobResponse.builder()
                .jobId(jobId)
                .state(ReindexJobState.RUNNING)
                .processed(0)
                .startedAt(now())
                .build();
        try {
            jobStore.start(job);
            executor.execute(() -> run(jobId));
        } catch (RuntimeException e) {
            failQuietly(jobId, summarize(e));
            jobStore.unlock(jobId);
            throw e;
        }
        log.info("已觸發搜尋索引全量重建 jobId={}", jobId);
        return ReindexStartResult.started(job);
    }

    /**
     * 最近一次作業（供狀態端點）。
     *
     * @return 最近一次作業；從未執行時為 empty
     */
    public Optional<SearchReindexJobResponse> currentJob() {
        return jobStore.current();
    }

    /**
     * 背景執行一次重建：寫入總數 → 重建（每批回報進度並續期鎖）→ 寫入結果 → 釋放鎖。
     *
     * @param jobId 作業 id
     */
    void run(UUID jobId) {
        try {
            jobStore.updateTotal(jobId, articleFacade.countPublished());
            long written = rebuilder.rebuild(processed -> {
                if (!jobStore.renewLock(jobId)) {
                    throw new IllegalStateException("重建鎖已遺失（TTL 到期或被其他實例取得），中止以免兩個重建同時切換 alias");
                }
                jobStore.updateProgress(jobId, processed);
            });
            jobStore.complete(jobId, written, now());
            recordLastReindexAt();
            log.info("搜尋索引全量重建作業完成 jobId={} written={}", jobId, written);
        } catch (RuntimeException e) {
            log.error("搜尋索引全量重建作業失敗 jobId={}: {}", jobId, e.getMessage(), e);
            failQuietly(jobId, summarize(e));
        } finally {
            jobStore.unlock(jobId);
        }
    }

    /**
     * 寫入「最後重建時間」（後台顯示用的附帶資訊，best-effort：失敗不讓已完成的重建變成失敗）。
     */
    private void recordLastReindexAt() {
        try {
            redis.opsForValue().set(RedisKeyConstant.SEARCH_REINDEX_AT_KEY, now().toString());
        } catch (RuntimeException e) {
            log.warn("寫入重建時間戳失敗（best-effort，索引已重建完成）：{}", e.getMessage());
        }
    }

    /**
     * 標記作業失敗；Redis 也故障時只記 warn（鎖到期後狀態端點會回報中斷）。
     *
     * @param jobId 作業 id
     * @param error 錯誤摘要
     */
    private void failQuietly(UUID jobId, String error) {
        try {
            jobStore.fail(jobId, error, now());
        } catch (RuntimeException e) {
            log.warn("寫入重建作業失敗狀態失敗 jobId={}: {}", jobId, e.getMessage());
        }
    }

    /**
     * 錯誤摘要：例外類型與訊息，截斷到 {@link #MAX_ERROR_LENGTH}。
     *
     * @param e 例外
     * @return 摘要
     */
    private static String summarize(Throwable e) {
        String summary = e.getClass().getSimpleName() + ": " + e.getMessage();
        return summary.length() <= MAX_ERROR_LENGTH ? summary : summary.substring(0, MAX_ERROR_LENGTH);
    }

    /**
     * 目前時間（Java 時鐘）。
     *
     * @return 現在
     */
    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    /**
     * 應用程式關閉：中斷背景作業（重建會在下一個檢查點中止、刪除新索引、標記 FAILED、釋放鎖），並等待收尾。
     */
    @Override
    public void destroy() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
                log.warn("搜尋索引重建背景執行緒未在 {} 秒內結束", SHUTDOWN_GRACE_SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
