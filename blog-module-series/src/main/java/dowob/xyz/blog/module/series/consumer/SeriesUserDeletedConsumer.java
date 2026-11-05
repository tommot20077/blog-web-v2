package dowob.xyz.blog.module.series.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.series.config.SeriesRabbitMqConfig;
import dowob.xyz.blog.module.series.service.SeriesEventPublisher;
import dowob.xyz.blog.module.series.service.SeriesService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Series 模組訂閱 {@link UserDeletedEvent}：使用者刪除帳號（匿名化，DATA-02）後，刪除該使用者名下
 * 「已無保留文章」的系列。
 *
 * <p>刪帳時草稿／待審／被駁回的文章會被移除，已發布與已下架的保留，所以可能留下作者已匿名、裡面 0 篇文章的空系列
 * 出現在公開系列列表。仍有保留文章的系列不動，作者顯示為匿名。「是否仍有保留文章」的判斷見
 * {@link SeriesService#deleteSeriesWithoutRetainedArticles}（不依賴 {@code series.article_count}）。</p>
 *
 * <p>冪等性：由 {@link ConsumerExecutor#executeIdempotent} 對 (event_id, consumer_name) 做 dedup，
 * 標記與刪除在<b>同一個資料庫交易</b>內——重送會被 skip；刪除失敗則標記一起 rollback，DLQ 重投不會被誤判為已處理。
 * 業務本身也可重入（已刪的系列查不到）。</p>
 *
 * <p>事件時序：{@code SeriesDeletedEvent}（讓 article 清空成員文章的系列歸屬）在<b>交易 commit 之後</b>才發，
 * 與 {@code SeriesService#deleteSeries} 一致；發送為 best-effort，失敗由 article 端的每日孤兒校正兜底。
 * 重送被冪等跳過時不重發。</p>
 *
 * <p>ACK 策略：AUTO（正常返回即 ack；拋例外由容器重試，耗盡進 DLQ）；失敗日誌與計數由 {@link ConsumerExecutor} 統一處理。
 * 缺 {@code userUuid} 的 payload 記 warn 後 ack（重送也不會變好）。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeriesUserDeletedConsumer {

    /** 冪等消費與 ConsumerExecutor 指標共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "series.user-deleted";

    /** 系列業務服務（含刪帳連動刪除） */
    private final SeriesService seriesService;

    /** 系列事件發布元件 */
    private final SeriesEventPublisher eventPublisher;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理使用者帳號刪除事件。
     *
     * @param event 使用者帳號刪除事件
     */
    @RabbitListener(queues = SeriesRabbitMqConfig.QUEUE_SERIES_USER_DELETED)
    public void onUserDeleted(UserDeletedEvent event) {
        if (event.userUuid() == null) {
            consumerExecutor.execute(CONSUMER_NAME, () -> "eventId=" + event.eventId(),
                    () -> log.warn("使用者刪除事件缺少 userUuid，略過系列連動刪除 eventId={}", event.eventId()));
            return;
        }
        /* 每次呼叫一份新清單：body 在 rollback 後由容器重試時不會殘留上一輪的內容 */
        List<UUID> deleted = new ArrayList<>();
        consumerExecutor.executeIdempotent(CONSUMER_NAME,
                () -> "userUuid=" + event.userUuid() + ", eventId=" + event.eventId(),
                event.eventId(),
                () -> deleted.addAll(seriesService.deleteSeriesWithoutRetainedArticles(event.userUuid())));
        /* 走到這裡代表交易已 commit（或重複事件被跳過，deleted 為空）；best-effort 發事件讓 article 清空成員歸屬 */
        deleted.forEach(eventPublisher::publishDeleted);
        log.info("使用者刪除連動：已刪除無保留文章的系列 userUuid={} eventId={} series={}",
                event.userUuid(), event.eventId(), deleted.size());
    }
}
