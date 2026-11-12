package dowob.xyz.blog.infrastructure.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MQ event 冪等標記服務。
 *
 * <p>不要在 consumer 內直接呼叫；請透過
 * {@link dowob.xyz.blog.infrastructure.mq.ConsumerExecutor#executeIdempotent}，
 * 由它把標記與業務放進同一個資料庫交易。</p>
 *
 * <p>Trade-off（Z3-a 起）：標記<b>必須</b>與業務同交易（{@code Propagation.MANDATORY}），
 * 業務失敗時標記一起 rollback。舊版用 {@code REQUIRES_NEW} 立即提交標記，
 * 失敗訊息重送時會被誤判為已處理而靜默遺失；現已移除任何獨立提交的路徑。</p>
 *
 * <p>實作選擇：使用 {@code ON CONFLICT DO NOTHING} 的 upsert pattern，避免 exception
 * 路徑污染 outer transaction（Spring Data JDBC repo.save() 拋出 DbActionExecutionException
 * 時會 mark outer tx as rollback-only）。</p>
 *
 * <p>Bean 建立由 {@link IdempotencyAutoConfiguration} 管理（@ConditionalOnBean(JdbcTemplate.class)），
 * 避免 user / recommend 等沒 datasource 的模組啟動時找不到 JdbcTemplate 而失敗。</p>
 *
 * @author Yuan
 * @version 2.0
 */
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 嘗試標記 event 已被某 consumer 處理；必須在既有資料庫交易內呼叫。
     *
     * <p>用 INSERT ... ON CONFLICT DO NOTHING 實作原子性 dedup：</p>
     * <ul>
     *   <li>第一次處理 → INSERT 成功 → affected rows = 1 → return true（caller 繼續業務）</li>
     *   <li>已處理過 → INSERT 被 CONFLICT skip → affected rows = 0 → return false（caller skip）</li>
     *   <li>另一交易正在插入同一鍵但尚未結束 → 本交易的 INSERT 阻塞到對方 commit／rollback 才有結果</li>
     * </ul>
     *
     * <p>{@code processed_at} 由 Java {@code LocalDateTime.now()} 傳入，不使用資料庫 CURRENT_TIMESTAMP。
     * 沒有既有交易時 Spring 會拋 {@code IllegalTransactionStateException}。</p>
     *
     * @param eventId      event 的 dedup key
     * @param consumerName 哪個 consumer 處理（用於同 event 多 consumer 各自冪等）
     * @return true = 第一次處理；false = 已處理過要 skip
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryMark(UUID eventId, String consumerName) {
        int affected = jdbcTemplate.update(
            "INSERT INTO processed_events (event_id, consumer_name, processed_at) "
            + "VALUES (?, ?, ?) ON CONFLICT (event_id, consumer_name) DO NOTHING",
            eventId, consumerName, LocalDateTime.now()
        );
        if (affected == 0) {
            log.debug("Event {} 已被 consumer {} 處理過，skip", eventId, consumerName);
            return false;
        }
        return true;
    }
}
