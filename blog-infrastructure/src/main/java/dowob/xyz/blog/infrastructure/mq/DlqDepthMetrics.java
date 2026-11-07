package dowob.xyz.blog.infrastructure.mq;

import dowob.xyz.blog.infrastructure.config.DeadLetterTopology;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * DLQ 深度 gauge {@value #DEPTH_METRIC}（ARCH-16，決策 C7）。
 *
 * <p>全站只有一個死信 queue（{@link DeadLetterTopology#DLQ_QUEUE}）。重試耗盡、不可重試的訊息都會落到這裡，
 * 但先前完全不可見——一致性漂移只能靠翻 log 發現。本元件把它的 {@code messageCount} 暴露成 gauge（tag：{@code queue}）。</p>
 *
 * <p><b>快取，不在 scrape 時打 broker</b>：gauge 只讀 {@link #cachedDepth()} 的快取值；
 * 由 {@link #refresh()} 以固定間隔（預設 30 秒，{@code app.mq.dlq-depth.refresh-interval-ms}）查詢 broker 並更新。
 * 這樣 Prometheus 抓取頻率或並行度都不會轉嫁成 broker 的 passive-declare 壓力。</p>
 *
 * <p><b>失敗語意</b>：broker 不可用、queue 不存在或沒有 {@link AmqpAdmin}，快取一律設為 {@code NaN}（未知）並記 WARN，
 * <b>絕不外拋</b>。刻意不保留「上次的值」：過期的 0 會把「量不到」偽裝成「沒積壓」；
 * 告警規則可用 NaN／缺值偵測「量測本身失效」。未曾 refresh 過的初始值同樣是 NaN。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
public class DlqDepthMetrics {

    /** DLQ 深度 gauge 的指標名稱（命名對齊 {@code mq.consumer.*}） */
    public static final String DEPTH_METRIC = "mq.dlq.depth";

    private static final String QUEUE_TAG = "queue";

    private final ObjectProvider<AmqpAdmin> amqpAdmin;

    private final Gauge gauge;

    /** 最近一次 refresh 的結果；未知為 NaN。以 volatile 供排程執行緒寫、scrape 執行緒讀 */
    private volatile double depth = Double.NaN;

    /**
     * 建立並向全域 registry 註冊 gauge。
     *
     * @param amqpAdmin broker 管理介面；沒有 RabbitMQ 的環境為空（refresh 變 no-op）
     */
    public DlqDepthMetrics(ObjectProvider<AmqpAdmin> amqpAdmin) {
        this.amqpAdmin = amqpAdmin;
        this.gauge = Gauge.builder(DEPTH_METRIC, this, DlqDepthMetrics::cachedDepth)
                .description("DLQ 內待處理的訊息數（排程更新的快取值；NaN 代表量測失敗或尚未量測）")
                .tag(QUEUE_TAG, DeadLetterTopology.DLQ_QUEUE)
                .register(Metrics.globalRegistry);
    }

    /**
     * 啟動完成後立即量一次，避免第一個排程週期內 gauge 一直是 NaN。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void refreshOnStartup() {
        refresh();
    }

    /**
     * 查詢 broker 並更新快取；<b>絕不外拋</b>。
     */
    @Scheduled(fixedDelayString = "${app.mq.dlq-depth.refresh-interval-ms:30000}",
            initialDelayString = "${app.mq.dlq-depth.refresh-interval-ms:30000}")
    public void refresh() {
        try {
            AmqpAdmin admin = amqpAdmin.getIfAvailable();
            if (admin == null) {
                return;
            }
            QueueInformation info = admin.getQueueInfo(DeadLetterTopology.DLQ_QUEUE);
            if (info == null) {
                depth = Double.NaN;
                log.warn("查無 DLQ queue {}，{} 設為 NaN", DeadLetterTopology.DLQ_QUEUE, DEPTH_METRIC);
                return;
            }
            depth = info.getMessageCount();
        } catch (RuntimeException e) {
            depth = Double.NaN;
            log.warn("讀取 DLQ {} 深度失敗，{} 設為 NaN（不影響應用運作）", DeadLetterTopology.DLQ_QUEUE, DEPTH_METRIC, e);
        }
    }

    /**
     * gauge 讀取的快取值（不打 broker）。
     *
     * @return 最近一次成功量到的 DLQ 訊息數；未知為 {@code NaN}
     */
    public double cachedDepth() {
        return depth;
    }

    /**
     * 容器關閉時自全域 registry 移除 gauge，避免殘留對已銷毀 bean 的弱參照。
     */
    @PreDestroy
    public void close() {
        Metrics.globalRegistry.remove(gauge);
    }
}
