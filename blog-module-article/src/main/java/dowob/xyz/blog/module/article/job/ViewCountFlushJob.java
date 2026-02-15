package dowob.xyz.blog.module.article.job;

import dowob.xyz.blog.module.article.service.ViewCountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 文章瀏覽計數批次刷入排程任務
 *
 * <p>
 * 每隔 5 分鐘（300,000 ms）將 Redis 中暫存的瀏覽增量批次寫入 DB，
 * 以降低 DB 寫入壓力。
 * </p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ViewCountFlushJob {

    /** 瀏覽計數服務 */
    private final ViewCountService viewCountService;

    /**
     * 批次刷入文章瀏覽計數至資料庫
     *
     * <p>由 Spring Scheduling 每 300 秒執行一次（前次結束後延遲計算）。</p>
     */
    @Scheduled(fixedDelay = 300_000)
    public void flushViewCounts() {
        log.info("開始批次寫入文章瀏覽計數至資料庫");
        viewCountService.flushViewCounts();
        log.info("批次寫入文章瀏覽計數完成");
    }
}
