package dowob.xyz.blog.module.reading.consumer;

import dowob.xyz.blog.infrastructure.event.UserDeletedEvent;
import dowob.xyz.blog.infrastructure.mq.ConsumerExecutor;
import dowob.xyz.blog.module.reading.config.ReadingRabbitMqConfig;
import dowob.xyz.blog.module.reading.mapper.ReadingUserCleanupMapper;
import dowob.xyz.blog.module.reading.service.ReadingUserCacheCleaner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reading 模組訂閱 {@link UserDeletedEvent}：使用者刪除帳號（匿名化，DATA-02）後，
 * 清除該使用者的收藏、劃線與閱讀進度（含 Redis 快取與待 flush 標記）。
 *
 * <p><b>按讚保留</b>（決策）：{@code user_article_likes} 只剩已匿名化使用者的 uuid，不含個資；
 * 刪除它會讓 {@code articles.like_count} 與實際按讚數不一致，還得對每篇文章遞減計數（併發與漂移風險，
 * 正是 DATA-06／T3 在處理的問題）。保留則計數維持正確，與「保留已發布文章」的決策一致。</p>
 *
 * <p>順序：先清 Redis（{@link ReadingUserCacheCleaner}），再於<b>同一個交易</b>內刪除三張表。
 * 先清 Redis 是為了避免 flush job 在 DB 刪除後又把殘留的 dirty 進度寫回；Redis 清除失敗時 DB 尚未動，
 * 重試即可。任何一張表刪除失敗則整體回滾，例外往外拋、由容器重試並於耗盡後進 DLQ。</p>
 *
 * <p>冪等性：Redis 清除與 {@code DELETE ... WHERE user_uuid = ?} 都天然冪等，重送只會刪除 0 筆，
 * 因此不使用 IdempotencyService。缺 {@code userUuid} 的 payload 記 warn 後 ack（重送也不會變好）。
 * 已知限制：flush job 若在「讀到 dirty 成員之後、本 consumer 刪除之前」正在 upsert，可能殘留一列進度，
 * 見 DATA-02 的每日校正建議。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingUserDeletedConsumer {

    /** ConsumerExecutor 指標與日誌共用的 consumer 識別名 */
    public static final String CONSUMER_NAME = "reading.user-deleted";

    /** 使用者刪帳連動用的 mapper（每張表一個 delete 方法） */
    private final ReadingUserCleanupMapper cleanupMapper;

    /** Redis 閱讀進度快取清除 */
    private final ReadingUserCacheCleaner cacheCleaner;

    /** 讓三張表的刪除落在同一個交易（consumer 方法本身不加 {@code @Transactional}，與專案的交易慣例一致） */
    private final TransactionTemplate transactionTemplate;

    /** MQ consumer 共用執行包裝（失敗日誌、計數、冪等） */
    private final ConsumerExecutor consumerExecutor;

    /**
     * 處理使用者帳號刪除事件。
     *
     * @param event 使用者帳號刪除事件
     */
    @RabbitListener(queues = ReadingRabbitMqConfig.QUEUE_READING_USER_DELETED)
    public void onUserDeleted(UserDeletedEvent event) {
        consumerExecutor.execute(CONSUMER_NAME,
                () -> "userUuid=" + event.userUuid() + ", eventId=" + event.eventId(),
                () -> {
                    if (event.userUuid() == null) {
                        log.warn("使用者刪除事件缺少 userUuid，略過收藏／劃線／閱讀進度連動刪除 eventId={}", event.eventId());
                        return;
                    }
                    cacheCleaner.clearProgressCache(event.userUuid());
                    int[] deleted = new int[3];
                    transactionTemplate.executeWithoutResult(status -> {
                        deleted[0] = cleanupMapper.deleteBookmarksByUserUuid(event.userUuid());
                        deleted[1] = cleanupMapper.deleteHighlightsByUserUuid(event.userUuid());
                        deleted[2] = cleanupMapper.deleteProgressByUserUuid(event.userUuid());
                    });
                    log.info("使用者刪除連動：已刪除收藏／劃線／閱讀進度 userUuid={} eventId={} bookmarks={} highlights={} progress={}",
                            event.userUuid(), event.eventId(), deleted[0], deleted[1], deleted[2]);
                });
    }
}
