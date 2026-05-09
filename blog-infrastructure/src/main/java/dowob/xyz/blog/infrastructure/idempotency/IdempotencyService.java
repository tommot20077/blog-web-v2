package dowob.xyz.blog.infrastructure.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * MQ event 冪等處理服務。
 *
 * <p>用法（在 consumer 中）:
 * <pre>{@code
 * @RabbitListener(queues = "...")
 * public void onEvent(SomeEvent event) {
 *     if (!idempotencyService.markProcessed(event.eventId(), CONSUMER_NAME)) {
 *         return; // 已處理過，skip
 *     }
 *     // 真正的業務邏輯
 * }
 * }</pre></p>
 *
 * <p>Trade-off：用 {@code REQUIRES_NEW} 確保 caller transaction rollback 不會連帶
 * rollback 此記錄。寧可漏處理一次也不重複扣（如 series.article_count 寧可少扣不多扣）。</p>
 *
 * <p>實作選擇：使用 {@code ON CONFLICT DO NOTHING} 的 upsert pattern，避免 exception
 * 路徑污染 outer transaction（Spring Data JDBC repo.save() 拋出 DbActionExecutionException
 * 時會 mark outer tx as rollback-only）。</p>
 *
 * <p>Bean 建立由 {@link IdempotencyAutoConfiguration} 管理（@ConditionalOnBean(JdbcTemplate.class)），
 * 避免 user / recommend 等沒 datasource 的模組啟動時找不到 JdbcTemplate 而失敗。</p>
 *
 * @author Yuan
 * @version 1.0
 */
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 標記 event 已被某 consumer 處理。
     *
     * <p>用 INSERT ... ON CONFLICT DO NOTHING 實作原子性 dedup：</p>
     * <ul>
     *   <li>第一次處理 → INSERT 成功 → affected rows = 1 → return true（caller 繼續業務）</li>
     *   <li>重送處理 → INSERT 被 CONFLICT skip → affected rows = 0 → return false（caller skip）</li>
     * </ul>
     *
     * @param eventId      event 的 dedup key
     * @param consumerName 哪個 consumer 處理（用於同 event 多 consumer 各自冪等）
     * @return true = 第一次處理；false = 已處理過要 skip
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markProcessed(UUID eventId, String consumerName) {
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
