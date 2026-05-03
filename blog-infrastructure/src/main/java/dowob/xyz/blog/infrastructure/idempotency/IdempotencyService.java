package dowob.xyz.blog.infrastructure.idempotency;

import dowob.xyz.blog.infrastructure.idempotency.model.ProcessedEvent;
import dowob.xyz.blog.infrastructure.idempotency.repository.ProcessedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
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
 * @author Yuan
 * @version 1.0
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IdempotencyService {

    private final ProcessedEventRepository repo;

    /**
     * 標記 event 已被某 consumer 處理。
     *
     * <p>用 UNIQUE constraint (event_id, consumer_name) 兜底：</p>
     * <ul>
     *   <li>第一次處理 → INSERT 成功 → return true（caller 繼續業務）</li>
     *   <li>重送處理 → INSERT 衝突 → return false（caller skip）</li>
     * </ul>
     *
     * @param eventId      event 的 dedup key
     * @param consumerName 哪個 consumer 處理（用於同 event 多 consumer 各自冪等）
     * @return true = 第一次處理；false = 已處理過要 skip
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markProcessed(UUID eventId, String consumerName) {
        try {
            ProcessedEvent pe = new ProcessedEvent(null, eventId, consumerName, LocalDateTime.now());
            repo.save(pe);
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("Event {} 已被 consumer {} 處理過，skip", eventId, consumerName);
            return false;
        }
    }
}
